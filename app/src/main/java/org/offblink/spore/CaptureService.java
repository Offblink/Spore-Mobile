package org.offblink.spore;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import org.offblink.spore.agent.AgentEngine;
import org.offblink.spore.agent.Session;
import org.offblink.spore.agent.SessionStore;
import org.offblink.spore.agent.SubjectsStore;
import org.offblink.spore.overlay.BallView;
import org.offblink.spore.overlay.CropOverlayView;
import org.offblink.spore.overlay.Suggestor;
import org.offblink.spore.panel.AnswerPanel;
import org.offblink.spore.panel.NativeAnswerPanel;
import org.offblink.spore.panel.Panel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 悬浮球 + 截屏采集的前台服务（handoff §9）。
 *
 * 链路：**开球开关时在应用内过首授**（MainActivity → handleProject 存投影，常驻持有）→
 * 每次点球服务侧直接取帧（不启动 Activity、不弹确认，目标应用不离前台）→
 * ImageReader 取首帧 → 黑帧检测（FLAG_SECURE 判据）→ 冻结帧框选（CropOverlayView）→ 裁剪落盘。
 * 裁剪参数沿用桌面端：MAX_CROP_LONG=1600、JPEG 0.82、MIN_W/MIN_H=60/40dp。
 */
public class CaptureService extends Service {

    public static final String ACTION_PROJECT = "org.offblink.spore.action.PROJECT";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    static final int MAX_CROP_LONG = 1600;
    static final int JPEG_QUALITY = 82;

    private static final String CHANNEL_ID = "spore_fgs";
    private static final int NOTIF_ID = 1;
    private static final long FRAME_TIMEOUT_MS = 3000;
    /** 取帧尝试上限：首抓 + 至多两次「重建 VD 强制重新合成」（干净帧一次即返回）。 */
    private static final int GRAB_TRIES = 3;
    /** 识别无结论的等待上限：到点按「识别超时」提示（与文案一致，避免用户干等）。 */
    private static final long ML_TIMEOUT_MS = 8000;

    private static volatile boolean running = false;
    /** 服务单例句柄：记录页「点进去接着对话」从静态入口喊面板 */
    private static volatile CaptureService self;
    /** 服务冷启动时排队的追问（记录详情页在服务未运行时提交的追问） */
    private static volatile String pendingFollowupId;
    private static volatile String pendingFollowupText;

    public static boolean isRunning() {
        return running;
    }

    /**
     * 改名/删除/收藏必须经服务侧桥：引擎内存态持有会话时直接改文件会被下次 persist 盖回。
     * 服务不在 → 没有内存态，直接文件操作。
     */
    public static boolean renameSession(Context c, String id, String name) {
        CaptureService s = self;
        if (s != null) {
            return s.engine.renameSession(id, name);
        }
        Session sess = SessionStore.load(c, id);
        if (sess == null) {
            return false;
        }
        sess.title = name;
        SessionStore.save(c, sess);
        return true;
    }

    public static boolean deleteSession(Context c, String id) {
        CaptureService s = self;
        if (s != null) {
            return s.engine.deleteSession(id);
        }
        SessionStore.delete(c, id);
        return true;
    }

    /**
     * 同步下行套用一条会话（SyncEngine.pull 调）：引擎在场 → 内存态优先
     * （在看的整个换、在途回合的跳过，见 {@code AgentEngine.applyPulled}）；
     * 服务没跑 → 直接 {@link SessionStore#saveQuiet} 落盘（不抬 touched，下行是外部权威）。
     */
    public static boolean applyFromSync(Context c, Session pulled) {
        CaptureService s = self;
        if (s != null) {
            return s.engine.applyPulled(pulled);
        }
        SessionStore.saveQuiet(c, pulled);
        return true;
    }

    /** 投影授权是否在手（主页状态胶囊）；服务没跑 = 没有 */
    public static boolean hasProjection() {
        CaptureService s = self;
        return s != null && s.proj != null;
    }

    /** 收藏切换走服务侧（同 rename/delete 的理由：引擎内存态优先，防被下次 persist 盖回） */
    public static boolean toggleFav(Context c, String id) {
        CaptureService s = self;
        if (s != null) {
            return s.engine.toggleFav(id);
        }
        Session sess = SessionStore.load(c, id);
        if (sess == null) {
            return false;
        }
        sess.fav = !sess.fav;
        SessionStore.save(c, sess);
        return true;
    }

    /**
     * 移入/移出科目（同 rename/fav 的双路：服务在跑走引擎内存态）。subjectId 空 = 移出。
     * 目标科目不存在 → false（防悬挂引用，kit design/03 §三）。已是该科目 → true 且不动文件。
     */
    public static boolean assignSubject(Context c, String id, String subjectId) {
        String target = (subjectId == null || subjectId.isEmpty()) ? null : subjectId;
        if (target != null && !SubjectsStore.exists(c, target)) {
            return false;
        }
        CaptureService s = self;
        if (s != null) {
            return s.engine.assignSubject(id, target);
        }
        Session sess = SessionStore.load(c, id);
        if (sess == null) {
            return false;
        }
        if (java.util.Objects.equals(sess.subjectId, target)) {
            return true;
        }
        sess.subjectId = target;
        SessionStore.saveActive(c, sess);
        return true;
    }

    /**
     * 删科目（kit design/03 §三 语义）：<b>先清引用、再摘科目行</b>——顺序反了中途失败会留
     * 悬挂引用；反过来则最坏留下一个空科目，用户再点一次删除即可收敛（幂等）。
     * 清引用走引擎内存态（在途回合的 persist 不会写回旧 subjectId），服务没跑则全量扫盘。
     * 摘行成功时 SubjectsStore.remove 内记科目墓碑（同步上行 deleted=1 传播到 PC）；
     * 同步下行的墓碑也走这里（会多记一笔本地回声账，销账口径见 SyncEngine.pullPhase）。
     */
    public static boolean deleteSubject(Context c, String subjectId) {
        if (subjectId == null || subjectId.isEmpty()) {
            return false;
        }
        CaptureService s = self;
        if (s != null) {
            s.engine.clearSubjectRefs(subjectId);
        } else {
            SessionStore.clearSubject(c, subjectId, null);
        }
        return SubjectsStore.remove(c, subjectId);
    }

    /**
     * 记录详情页追问：与面板同一引擎，走同一会话。服务没跑 → 排队补发
     * （onCreate 里的 pendingFollowup 机制）。返回 ok | busy | gone，bridge 直接把非 ok 当 toast。
     * busy = **这个会话**已有回合在跑（别的会话在跑不影响它）。
     */
    public static String followup(Context c, String id, String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() || id == null || id.isEmpty()) {
            return "gone";
        }
        if (!running) {
            pendingFollowupId = id;
            pendingFollowupText = t;
            start(c);
            return "ok";
        }
        CaptureService s = self;
        if (s == null) {
            return "gone";
        }
        if (s.engine.isBusy(id)) {
            return "busy";
        }
        if (!s.engine.loadSession(id)) {
            return "gone";
        }
        if (!s.engine.sendFollowup(t)) {
            return "busy";
        }
        return "ok";
    }

    /**
     * 该会话有在途回合 → 引擎的内存活对象（记录详情页轮询用）：
     * 磁盘文件里的 status 会被 fromJson 归一成 done、消息也停在上一回合末。
     */
    public static Session liveSession(String id) {
        CaptureService s = self;
        return s == null ? null : s.engine.liveSession(id);
    }

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, CaptureService.class));
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, CaptureService.class));
    }

    private WindowManager wm;
    private MediaProjectionManager mpm;
    private HandlerThread bgThread;
    private Handler bg;
    private Handler main;
    /** 取帧序号：连点球时旧一次的识别结论/提示不许盖到新一次（见 recognizeThenCrop）。 */
    private volatile int captureSeq;

    /** 两阶段作答引擎与浮动作答面板（截图落盘后接力，handoff §9.2） */
    private AgentEngine engine;
    /** 第五轮分叉：web（默认）/ 原生（auto 判定华为鸿蒙，见 SporeSettings.panelNative） */
    private Panel panel;

    private BallView ball;
    private WindowManager.LayoutParams ballParams;
    private boolean ballAttached;
    private int gestureStartX, gestureStartY;

    private CropOverlayView cropView;
    private WindowManager.LayoutParams cropParams;

    /**
     * 一次授权、全程持有（2026-09-30 用户实测定的设计）：授权弹窗只在首次出现；
     * 之后每次点球 = 服务侧直接取帧，**不启动任何 Activity、不弹系统确认**，
     * 目标应用全程留在前台（每次授权都会把它挤回桌面、截到桌面——用户实测的根因）。
     * 被系统/用户收回（onStop）→ 置空，下次点球走重新授权。
     */
    private volatile MediaProjection proj;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        bgThread = new HandlerThread("spore-capture");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());
        main = new Handler(Looper.getMainLooper());

        createChannel();
        startAsForeground();

        engine = new AgentEngine(this);
        panel = SporeSettings.load(this).panelNative()
                ? new NativeAnswerPanel(this, wm, engine)
                : new AnswerPanel(this, wm, engine);
        engine.setListener(panel);
        self = this;
        CrashLog.hb(this, "create");

        if (Settings.canDrawOverlays(this)) {
            addBall();
            final String pfId = pendingFollowupId;
            final String pfText = pendingFollowupText;
            pendingFollowupId = null;
            pendingFollowupText = null;
            if (pfId != null && pfText != null) {
                main.post(() -> {
                    // 已发出 = 服务冷启动的排队补发成功；否则给明确提示
                    if (!(engine.loadSession(pfId) && engine.sendFollowup(pfText))) {
                        toastRes(R.string.busy_wait);
                    }
                });
            }
        } else {
            toastRes(R.string.status_no_overlay);
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_PROJECT.equals(intent.getAction())) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            handleProject(resultCode, data);
        }
        // 第五轮根因修复：投影在长检索/锁屏时被系统收回，mediaProjection→specialUse
        // 切换一旦失败系统会杀服务——NOT_STICKY 意味着球+面板窗（都挂在服务上）永不回来，
        // 用户看到的就是「悬浮窗崩了」且无 Java 栈。STICKY 拉回后 onCreate 重建一切：
        // 无投影 → 走已有的「授权失效」提示，下次点球重授权。
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (panel != null) {
            panel.close();
            panel.destroy(); // WebView 是重对象：服务死了必须显式销毁，别留进程级泄漏
        }
        if (engine != null) {
            engine.shutdown();
        }
        removeViewQuietly(cropView);
        cropView = null;
        removeViewQuietly(ball);
        ball = null;
        ballAttached = false;
        MediaProjection p = proj;
        proj = null;
        if (p != null) {
            p.stop();
        }
        bgThread.quitSafely();
        running = false;
        self = null;
        CrashLog.hb(this, "destroy");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- 前台通知 ----------

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void startAsForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+ 硬规则（AVD 实测 SecurityException）：mediaProjection 类型的 FGS
            // 在持有投影授权前 startForeground 会崩 → 先以 specialUse 起前台，
            // 拿到授权后（handleProject）再切到 mediaProjection 类型
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    // ---------- 悬浮球 ----------

    private void addBall() {
        Point sz = displaySize();
        int glow = dp(BallView.GLOW_DP);
        int bw = dp(BallView.BALL_DP + 2 * BallView.GLOW_DP);
        int bh = dp(BallView.BALL_DP + 2 * BallView.GLOW_DP);
        ballParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        ballParams.gravity = Gravity.TOP | Gravity.LEFT;
        ballParams.x = sz.x - bw + glow;           // 平边贴右缘（光晕不入界）
        ballParams.y = Math.max(0, sz.y / 2 - bh / 2);

        ball = new BallView(this, ballListener);
        wm.addView(ball, ballParams);
        ballAttached = true;
    }

    private final BallView.Listener ballListener = new BallView.Listener() {
        @Override
        public void onTap() {
            if (cropView != null) {
                // 第五轮：框选开着时再点球 = 收起（曾因二次截屏覆盖字段泄漏旧窗 →
                // 暗幕叠加成「全黑」+ 界面关不掉的无限截屏态）
                removeCropView();
                return;
            }
            if (proj != null) {
                // 已授权：零 Activity 启动、目标应用不离开前台（用户实测修复）
                final Point sz = displaySize();
                bg.post(() -> captureFrame(sz.x, sz.y, 0));
                return;
            }
            // 授权已失效：不在截屏时拉授权页（会把目标应用挤回桌面 + 留灰色残窗，
            // 用户实测两条都是它）→ 只提示回 Spore 重开球开关（开关里已内置首授）
            toastRes(R.string.projection_missing);
        }

        @Override
        public void onLongPress() {
            // 球长按 = 面板开/关（第八轮拍板：不再连带弹会话列表，列表由面板内 💬 手动开）
            panel.toggle();
        }

        @Override
        public void onGestureStart() {
            gestureStartX = ballParams.x;
            gestureStartY = ballParams.y;
        }

        @Override
        public void onGestureMove(int totalDx, int totalDy) {
            ballParams.x = gestureStartX + totalDx;
            ballParams.y = gestureStartY + totalDy;
            updateBall();
        }

        @Override
        public void onGestureEnd() {
            // 松手吸边：贴到较近的一侧（平边贴屏），纵向夹回屏幕内
            Point sz = displaySize();
            int glow = dp(BallView.GLOW_DP);
            int bw = ball.getWidth() > 0 ? ball.getWidth()
                    : dp(BallView.BALL_DP + 2 * BallView.GLOW_DP);
            int bh = ball.getHeight() > 0 ? ball.getHeight()
                    : dp(BallView.BALL_DP + 2 * BallView.GLOW_DP);
            boolean left = (ballParams.x + bw / 2) < sz.x / 2;
            ballParams.x = left ? -glow : sz.x - bw + glow;
            ballParams.y = Math.max(0, Math.min(ballParams.y, sz.y - bh));
            ball.setSide(left);
            updateBall();
        }
    };

    private void updateBall() {
        if (ballAttached && ball != null && ball.getParent() != null) {
            wm.updateViewLayout(ball, ballParams);
        }
    }

    // ---------- 截屏 ----------

    /** MainActivity 首授回调转来的授权结果：只**存投影**，不取帧（截屏由点球触发） */
    private void handleProject(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            return; // MainActivity 已 toast 未授权
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // 14+ 顺序铁律（AVD 两头实测）：授权框返回后必须**先**把 FGS 切成
                // mediaProjection 类型（此刻对话框授权已登记，能过校验），
                // **再** getMediaProjection（它反过来要求 MP 类型 FGS 在跑）
                startForeground(NOTIF_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            }
            // Android 14+：createVirtualDisplay 前必须注册回调，否则 SecurityException
            proj = mpm.getMediaProjection(resultCode, data);
            proj.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    // 用户/系统收回授权（锁屏/长闲置/下拉停止投屏）→ 提示回 Spore 重授。
                    // 这是主线程回调：任何一步抛出都会直接杀进程（第五轮崩溃家族），
                    // 全体包 try/catch 落盘；类型切不上去就交给 STICKY 重建。
                    try {
                        proj = null;
                        if (Build.VERSION.SDK_INT >= 34) {
                            // 14+ 规则：mediaProjection 类型却无投影 → 系统会杀服务 → 切回 specialUse
                            startForeground(NOTIF_ID, buildNotification(),
                                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                        }
                        toastRes(R.string.projection_missing);
                    } catch (Throwable t) {
                        CrashLog.write(CaptureService.this, t, "projection.onStop");
                    }
                }
            }, main);
        } catch (Exception e) {
            toast(getString(R.string.capture_failed, String.valueOf(e)));
        }
    }

    /**
     * 从**持有中的**投影取一帧：每次点球临时建 VirtualDisplay + ImageReader，读完即释放
     * （不 stop 投影，授权保持存活）。不常驻 VD 是刻意的：缓冲占满后生产者会停更，
     * 下次读到的是陈旧帧——临时建取帧才能保证是「此刻」的屏幕。
     */
    private void captureFrame(int w, int h, int attempt) {
        MediaProjection p = proj;
        if (p == null) {
            return; // 授权中途被收回 → 下次点球重新授权
        }
        final int seq = ++captureSeq;
        Bitmap[] seen = new Bitmap[1];
        Bitmap frame = null;
        FrameQuality best = null;
        int dup = 0;
        // 第九轮（黑屏悬案）：首抓可能拿到「合成未完成」的帧——视觉特征就是**大片死黑**
        // （图层还没上屏 / 局部 FLAG_SECURE）。旧判据近黑只拦 max<10，噪声黑/半黑全放行 →
        // ML 在黑帧上必然空手 → 整屏预选 → 裁出来还是黑。这里只做两件事，都不加闸：
        //   ① 多帧取「最亮的一张」：半黑帧必然比完整帧暗，取最亮 = 取最完整；
        //   ② 可疑帧重建虚拟显示强制重新合成再取（静态屏下 VD 可能不再产新帧，重抓=同一张）。
        // 干净帧（正常屏幕）第一次就返回：正常路径零额外耗时，可疑帧多花几百毫秒。
        for (int i = 0; i < GRAB_TRIES; i++) {
            Bitmap f = grabFrame(p, w, h, seen);
            if (f == null) {
                break; // 全近黑/超时 → 走下面的重试与取证分支
            }
            FrameQuality q = FrameQuality.of(f);
            if (frame != null && f.sameAs(frame)) {
                dup++; // 逐像素撞车 = 重建 VD 也没拿到新帧（合成根本没动）
            }
            if (best == null || q.mean > best.mean) {
                if (frame != null) {
                    frame.recycle();
                }
                frame = f;
                best = q;
            } else {
                f.recycle();
            }
            if (!q.suspect()) {
                break;
            }
        }
        if (frame == null) {
            if (attempt < 2) {
                // 首帧迟到/合成未就绪：自动重试（用户拍板——别让用户再点一次球）
                bg.postDelayed(() -> captureFrame(w, h, attempt + 1), 600);
                return;
            }
            if (seen[0] != null) {
                // 三轮只等到近黑帧（FLAG_SECURE / 取帧失败）→ 黑屏 toast + 留样本定罪
                FrameDiag.save(this, seen[0], FrameQuality.of(seen[0]), 0, "black");
                toastRes(R.string.capture_black);
            } else {
                toastRes(R.string.capture_timeout);
            }
            return;
        }
        // 第七轮用户拍板：整屏 meanLuma「屏幕太暗」闸**撤掉**——屏幕没问题时它误杀。
        // 纯黑兜底交给裁剪级 crop_warn_dark（区域判定，比整屏均值准）。
        // 第九轮：只因大片死黑多取几帧，**绝不因此拒绝进框选**（不回头加闸）。
        // 第九轮后半（用户拍板，推翻 R7）：**ML 认不出字就不进截屏界面**，直接 toast 请重试。
        // Round 23（2026-10-09）：ML 识别改成**可选**（默认关，对齐 MV3/GUI 两端）——
        // 关着就不建识别器、不跑 OCR，直接进框选手动拖；开着走原样（含上面那条硬门禁）。
        final Bitmap show = frame;
        final FrameQuality fq = best;
        final int dupCount = dup;
        bg.post(() -> FrameDiag.save(this, show, fq, dupCount, "frame")); // 取证不拖 UI
        if (SporeSettings.load(this).mlSuggest) {
            recognizeThenCrop(show, seq); // 认出字才 showCropOverlay，否则只剩 toast
        } else {
            // 关着 = 无建议框、无 OCR、无门禁。**必须回主线程**：captureFrame 跑在 bg 线程，
            // wm.addView 在非 UI 线程会 CalledFromWrongThreadException —— Round 23 实装时漏了，
            // 而默认就是关着（等于默认路径必崩，单测/CI 无 instrumentation 抓不到，Round 24 修）
            main.post(() -> showCropOverlay(show, null));
        }
    }

    /**
     * 建一次临时 VirtualDisplay + ImageReader 取一帧，读完即释放（不 stop 投影）。
     * 不常驻 VD 是刻意的：缓冲占满后生产者会停更，下次读到的是陈旧帧；
     * 每轮重建 VD 同时兼作「强制重新合成」——可疑帧就靠它要一张新帧。
     */
    private Bitmap grabFrame(MediaProjection p, int w, int h, Bitmap[] lastSeen) {
        ImageReader reader = null;
        VirtualDisplay vd = null;
        try {
            int dpi = getResources().getDisplayMetrics().densityDpi;
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            vd = p.createVirtualDisplay("spore-cap", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, null);
            return awaitFrame(reader, w, h, lastSeen);
        } catch (Exception e) {
            toast(getString(R.string.capture_failed, String.valueOf(e)));
            return null;
        } finally {
            if (vd != null) {
                vd.release();
            }
            if (reader != null) {
                reader.close();
            }
            // 不再 proj.stop()：授权持久持有，投影生命周期归服务与 onStop 回调管
        }
    }

    /**
     * 取首帧；首帧可能是合成前的黑帧，近黑就继续等，直到超时。
     * {@code lastSeen[0]} 挽留最后一帧：全程没等到非黑帧时供黑屏取证留样本。
     */
    private Bitmap awaitFrame(ImageReader reader, int w, int h, Bitmap[] lastSeen) {
        long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Image img = reader.acquireLatestImage();
            if (img != null) {
                Bitmap bmp = imageToBitmap(img, w, h);
                img.close();
                if (bmp != null) {
                    lastSeen[0] = bmp;
                    if (!FrameQuality.of(bmp).nearBlack()) {
                        return bmp;
                    }
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private Bitmap imageToBitmap(Image img, int w, int h) {
        Image.Plane[] planes = img.getPlanes();
        if (planes.length == 0) {
            return null;
        }
        Image.Plane plane = planes[0];
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        if (pixelStride == 4 && rowStride == w * 4) {
            buf.rewind();
            bmp.copyPixelsFromBuffer(buf);
        } else {
            // 行间有 padding：逐行打包成连续缓冲再拷
            ByteBuffer packed = ByteBuffer.allocate(w * 4 * h);
            byte[] row = new byte[w * 4];
            for (int y = 0; y < h; y++) {
                buf.position(y * rowStride);
                buf.get(row, 0, w * 4);
                packed.put(row);
            }
            packed.rewind();
            bmp.copyPixelsFromBuffer(packed);
        }
        return bmp;
    }

    // ---------- 框选 ----------

    private void showCropOverlay(Bitmap frame, int[] suggestion) {
        if (!Settings.canDrawOverlays(this)) {
            toastRes(R.string.status_no_overlay);
            stopSelf();
            return;
        }
        cropParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        cropParams.gravity = Gravity.TOP | Gravity.LEFT;
        removeCropView(); // 防重入：绝不允许第二张框选叠上去（暗幕叠加 = 全黑 + 关不掉）
        // Round 24（2026-10-09）：**ML 关着时不画底部「搜」pill、松手即搜**
        // （与桌面两端「松手即采纳」同口径，用户拍板）；开着维持第六轮：确认只属于「搜」按钮
        cropView = new CropOverlayView(this, frame, cropListener,
                !SporeSettings.load(this).mlSuggest);
        wm.addView(cropView, cropParams);
        // 预选框随层一起上：setSuggestion 对「布局未到位」自带暂存回放
        if (suggestion != null) {
            cropView.setSuggestion(suggestion[0], suggestion[1], suggestion[2], suggestion[3]);
        }
    }

    /**
     * §9.3 建议框：bundled 中文识别（无 GMS 也可用）→ Suggestor 聚类出单框 → 预填 → **才进框选层**。
     *
     * 第九轮用户拍板（推翻 R7 的「失败就整屏预选」）：**ML 认不出字 = 不进截屏界面**，
     * 直接 toast「识别超时，请重试」。理由：真机上认不出字那种场景多半伴随脏帧/禁截，
     * 进去了也只能裁出黑的，不如让用户重截一次；整屏预选只是把症状顺延到成片。
     * 识别器起不来 / 识别失败 / 没认出字 / 超过 {@link #ML_TIMEOUT_MS} 无结论 → 同一条 toast，
     * 且由 settled 原子门闩保证**只结一次**（超时后迟到的识别结果不会再弹出框选层）。
     */
    private void recognizeThenCrop(Bitmap frame, int seq) {
        final int frameW = frame.getWidth();
        final int frameH = frame.getHeight();
        final AtomicBoolean settled = new AtomicBoolean(false);
        // 连点球时旧一次的结论不许盖到新一次上（迟到的 toast / 旧帧框选层）
        final Runnable giveUp = () -> {
            if (settled.compareAndSet(false, true) && seq == captureSeq) {
                toastRes(R.string.capture_ml_failed);
            }
        };
        bg.postDelayed(giveUp, ML_TIMEOUT_MS); // 识别器挂死也不能让用户干等（文案即超时）
        TextRecognizer recognizer;
        try {
            recognizer = TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build());
        } catch (Throwable t) {
            giveUp.run(); // 识别器起不来（设备不支持等）
            return;
        }
        try {
            recognizer.process(InputImage.fromBitmap(frame, 0))
                    .addOnSuccessListener(text -> {
                        List<Suggestor.Line> lines = new ArrayList<>();
                        for (Text.TextBlock block : text.getTextBlocks()) {
                            for (Text.Line line : block.getLines()) {
                                Rect box = line.getBoundingBox();
                                if (box != null && !line.getText().isEmpty()) {
                                    lines.add(new Suggestor.Line(box.left, box.top,
                                            box.right, box.bottom, line.getText()));
                                }
                            }
                        }
                        final int[] suggestion = Suggestor.suggest(lines, frameW, frameH);
                        if (suggestion == null) {
                            giveUp.run(); // 没认出字 → 同一条提示，不进框选
                            return;
                        }
                        if (settled.compareAndSet(false, true) && seq == captureSeq) {
                            main.post(() -> showCropOverlay(frame, suggestion));
                        }
                    })
                    .addOnFailureListener(e -> giveUp.run())
                    .addOnCompleteListener(t -> recognizer.close());
        } catch (Throwable t) {
            recognizer.close();
            giveUp.run();
        }
    }

    private final CropOverlayView.Listener cropListener = new CropOverlayView.Listener() {
        @Override
        public void onCropped(Bitmap crop) {
            bg.post(() -> {
                File saved = saveCrop(crop);
                main.post(() -> {
                    removeCropView();
                    if (saved != null) {
                        // 无感（用户拍板）：截完不弹「已保存」，面板直接接力
                        panel.openWithCapture(saved);
                    } else {
                        toast(getString(R.string.capture_failed, "write failed"));
                    }
                });
            });
        }

        @Override
        public void onClose() {
            removeCropView();
        }
    };

    private File saveCrop(Bitmap crop) {
        Bitmap out = crop;
        int longest = Math.max(crop.getWidth(), crop.getHeight());
        if (longest > MAX_CROP_LONG) {
            float scale = (float) MAX_CROP_LONG / longest;
            out = Bitmap.createScaledBitmap(crop,
                    Math.round(crop.getWidth() * scale),
                    Math.round(crop.getHeight() * scale), true);
        }
        File dir = new File(getFilesDir(), "captures");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String name = "cap_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".jpg";
        File f = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
            return f;
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        } finally {
            if (out != crop) {
                out.recycle();
            }
        }
    }

    private void removeCropView() {
        removeViewQuietly(cropView);
        cropView = null;
    }

    // ---------- 工具 ----------

    private void removeViewQuietly(android.view.View v) {
        if (v != null && v.getParent() != null) {
            try {
                wm.removeView(v);
            } catch (Exception ignored) {
                // 窗口可能已被系统移除
            }
        }
    }

    private Point displaySize() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            return new Point(b.width(), b.height());
        }
        Point p = new Point();
        //noinspection deprecation
        wm.getDefaultDisplay().getSize(p);
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toastRes(int resId) {
        toast(getString(resId));
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
    }
}
