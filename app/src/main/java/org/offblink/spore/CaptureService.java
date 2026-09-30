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
import org.offblink.spore.overlay.BallView;
import org.offblink.spore.overlay.CropOverlayView;
import org.offblink.spore.overlay.Suggestor;
import org.offblink.spore.panel.AnswerPanel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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

    private static volatile boolean running = false;
    /** 服务单例句柄：记录页「点进去接着对话」从静态入口喊面板 */
    private static volatile CaptureService self;
    /** 服务冷启动时补开的会话（记录页在服务未运行时点了行） */
    private static volatile String pendingSessionId;

    public static boolean isRunning() {
        return running;
    }

    /** 记录页/会话列表：打开面板并换入该会话；服务没跑就先拉起、就绪后补开 */
    public static void openSession(Context c, String sessionId) {
        if (!running) {
            pendingSessionId = sessionId;
            start(c);
            return;
        }
        CaptureService s = self;
        if (s != null) {
            s.main.post(() -> s.panel.openSession(sessionId));
        }
    }

    /**
     * 重命名/删除必须经服务侧：引擎内存里可能正持有该会话，直接改文件会被下一次
     * persist() 用内存态盖回去。服务不在 → 没有内存态，直接文件操作。
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

    /** 两阶段作答引擎与浮动作答面板（截图落盘后接力，handoff §9.2） */
    private AgentEngine engine;
    private AnswerPanel panel;

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
        panel = new AnswerPanel(this, wm, engine);
        engine.setListener(panel);
        self = this;

        if (Settings.canDrawOverlays(this)) {
            addBall();
            String pend = pendingSessionId;
            pendingSessionId = null;
            if (pend != null) {
                final String sid = pend;
                main.post(() -> panel.openSession(sid));
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
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (panel != null) {
            panel.close();
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
        int bw = dp(BallView.HANDLE_W_DP + 2 * BallView.GLOW_DP);
        int bh = dp(BallView.HANDLE_H_DP + 2 * BallView.GLOW_DP);
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
            if (proj != null) {
                // 已授权：零 Activity 启动、目标应用不离开前台（用户实测修复）
                final Point sz = displaySize();
                bg.post(() -> captureFrame(sz.x, sz.y));
                return;
            }
            // 授权已失效：不在截屏时拉授权页（会把目标应用挤回桌面 + 留灰色残窗，
            // 用户实测两条都是它）→ 只提示回 Spore 重开球开关（开关里已内置首授）
            toastRes(R.string.projection_missing);
        }

        @Override
        public void onLongPress() {
            // 球长按 = 面板开/收（点按仍是「直接截屏」，§9.1 不破）
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
                    : dp(BallView.HANDLE_W_DP + 2 * BallView.GLOW_DP);
            int bh = ball.getHeight() > 0 ? ball.getHeight()
                    : dp(BallView.HANDLE_H_DP + 2 * BallView.GLOW_DP);
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
                    // 用户/系统收回授权（下拉停止投屏等）→ 点球提示回 Spore 重授
                    proj = null;
                    if (Build.VERSION.SDK_INT >= 34) {
                        // 14+ 规则：mediaProjection 类型却无投影 → 系统会杀服务 → 切回 specialUse
                        startForeground(NOTIF_ID, buildNotification(),
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                    }
                    toastRes(R.string.projection_missing);
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
    private void captureFrame(int w, int h) {
        ImageReader reader = null;
        VirtualDisplay vd = null;
        try {
            MediaProjection p = proj;
            if (p == null) {
                return; // 授权中途被收回 → 下次点球重新授权
            }

            int dpi = getResources().getDisplayMetrics().densityDpi;
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            vd = p.createVirtualDisplay("spore-cap", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, null);

            Bitmap frame = awaitFrame(reader, w, h);
            if (frame == null) {
                toastRes(R.string.capture_timeout);
                return;
            }
            if (isNearBlack(frame)) {
                // FLAG_SECURE 判据（handoff §9.6）：黑帧 → 对方禁止截屏
                toastRes(R.string.capture_black);
                return;
            }
            final Bitmap show = frame;
            main.post(() -> showCropOverlay(show));
        } catch (Exception e) {
            toast(getString(R.string.capture_failed, String.valueOf(e)));
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

    /** 取首帧；首帧可能是合成前的黑帧，近黑就继续等，直到超时。 */
    private Bitmap awaitFrame(ImageReader reader, int w, int h) {
        long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Image img = reader.acquireLatestImage();
            if (img != null) {
                Bitmap bmp = imageToBitmap(img, w, h);
                img.close();
                if (bmp != null && !isNearBlack(bmp)) {
                    return bmp;
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

    /** 抽样判断是否近全黑（FLAG_SECURE 截出来就是纯黑）。 */
    private boolean isNearBlack(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int step = Math.max(1, (w * h) / 4000);
        int maxChannel = 0;
        for (int i = 0; i < w * h; i += step) {
            int p = bmp.getPixel(i % w, i / w);
            int m = Math.max(Math.max((p >> 16) & 0xFF, (p >> 8) & 0xFF), p & 0xFF);
            if (m > maxChannel) {
                maxChannel = m;
            }
        }
        return maxChannel < 10;
    }

    // ---------- 框选 ----------

    private void showCropOverlay(Bitmap frame) {
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
        cropView = new CropOverlayView(this, frame, cropListener);
        wm.addView(cropView, cropParams);
        suggestFromFrame(frame);
    }

    /**
     * §9.3 建议框：bundled 中文识别（无 GMS 也可用）→ Suggestor 聚类出单框 → 预填进框选层。
     * 任何失败（模型初始化 / 识别 / 无文本）都静默退化为手动拖框，不打扰用户。
     */
    private void suggestFromFrame(Bitmap frame) {
        bg.post(() -> {
            TextRecognizer recognizer = null;
            try {
                recognizer = TextRecognition.getClient(
                        new ChineseTextRecognizerOptions.Builder().build());
            } catch (Throwable t) {
                return; // 识别器起不来（设备不支持等）→ 手动框兜底
            }
            final TextRecognizer rec = recognizer;
            final int frameW = frame.getWidth();
            final int frameH = frame.getHeight();
            try {
                rec.process(InputImage.fromBitmap(frame, 0))
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
                            if (suggestion != null) {
                                main.post(() -> {
                                    if (cropView != null) {
                                        cropView.setSuggestion(suggestion[0], suggestion[1],
                                                suggestion[2], suggestion[3]);
                                    }
                                });
                            }
                        })
                        .addOnFailureListener(e -> {
                            // 识别失败 → 手动框（设计内兜底）
                        })
                        .addOnCompleteListener(t -> rec.close());
            } catch (Throwable t) {
                rec.close(); // 输入非法等 → 手动框兜底
            }
        });
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
