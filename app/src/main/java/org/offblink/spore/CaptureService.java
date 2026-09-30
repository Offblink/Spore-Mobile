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

import org.offblink.spore.agent.AgentEngine;
import org.offblink.spore.overlay.BallView;
import org.offblink.spore.overlay.CropOverlayView;
import org.offblink.spore.panel.AnswerPanel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 悬浮球 + 截屏采集的前台服务（handoff §9）。
 *
 * 链路：点球 → CaptureConsentActivity 拿系统授权 → 本服务 MediaProjection → VirtualDisplay →
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

    public static boolean isRunning() {
        return running;
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

        if (Settings.canDrawOverlays(this)) {
            addBall();
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
        bgThread.quitSafely();
        running = false;
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
        if (Build.VERSION.SDK_INT >= 29) {
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
        int bw = dp(56);
        ballParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        ballParams.gravity = Gravity.TOP | Gravity.LEFT;
        ballParams.x = sz.x - bw / 2;              // 右缘半挂
        ballParams.y = Math.max(0, sz.y / 2 - bw / 2);

        ball = new BallView(this, ballListener);
        wm.addView(ball, ballParams);
        ballAttached = true;
    }

    private final BallView.Listener ballListener = new BallView.Listener() {
        @Override
        public void onTap() {
            try {
                Intent i = new Intent(CaptureService.this, CaptureConsentActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                // 后台启动限制（Android 10+）会拦这里 → 回退方案见 handoff §9.7②
                toast(getString(R.string.capture_failed, String.valueOf(e)));
            }
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
            // 松手吸边：半挂到较近的一侧，纵向夹回屏幕内
            Point sz = displaySize();
            int bw = ball.getWidth() > 0 ? ball.getWidth() : dp(56);
            int bh = ball.getHeight() > 0 ? ball.getHeight() : dp(56);
            boolean left = (ballParams.x + bw / 2) < sz.x / 2;
            ballParams.x = left ? -bw / 2 : sz.x - bw / 2;
            ballParams.y = Math.max(0, Math.min(ballParams.y, sz.y - bh));
            updateBall();
        }
    };

    private void updateBall() {
        if (ballAttached && ball != null && ball.getParent() != null) {
            wm.updateViewLayout(ball, ballParams);
        }
    }

    // ---------- 截屏 ----------

    private void handleProject(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            toastRes(R.string.capture_cancelled);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toastRes(R.string.status_no_overlay);
            stopSelf();
            return;
        }
        final Point sz = displaySize();
        bg.post(() -> captureFrame(resultCode, data, sz.x, sz.y));
    }

    private void captureFrame(int resultCode, Intent data, int w, int h) {
        MediaProjection proj = null;
        ImageReader reader = null;
        VirtualDisplay vd = null;
        try {
            proj = mpm.getMediaProjection(resultCode, data);
            // Android 14+：createVirtualDisplay 前必须注册回调，否则 SecurityException
            proj.registerCallback(new MediaProjection.Callback() { }, main);

            int dpi = getResources().getDisplayMetrics().densityDpi;
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            vd = proj.createVirtualDisplay("spore-cap", w, h, dpi,
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
            if (proj != null) {
                proj.stop();
            }
            if (reader != null) {
                reader.close();
            }
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
    }

    private final CropOverlayView.Listener cropListener = new CropOverlayView.Listener() {
        @Override
        public void onCropped(Bitmap crop) {
            bg.post(() -> {
                File saved = saveCrop(crop);
                main.post(() -> {
                    removeCropView();
                    if (saved != null) {
                        toast(getString(R.string.crop_saved, saved.getName()));
                        // 接力：面板弹出 → 两阶段作答开跑
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
