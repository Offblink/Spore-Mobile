package org.offblink.spore;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.offblink.spore.overlay.CropOverlayView;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 拍照搜题（主页入口）：CameraX 取景按快门 → 冻结帧框选（带顺时针 90° 旋转键，
 * 仅本页的框选有，截屏路径没有）→ 落盘 → {@link CaptureService#openPhotoSearch}
 * <b>不进悬浮面板</b>，直接开搜题记录详情页看答案生成（用户拍板的流程）。
 * 框选是页内视图不是悬浮窗 —— 本页全程在前台，不需要悬浮窗权限；回答引擎在
 * CaptureService 里（与记录页追问同一条路），悬浮窗权限只在服务起不来时才拦。
 */
public class PhotoActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 1001;
    /** 拍照帧长边上限：给框选留足裁剪余量（落盘还会再压到 1600），同时掐掉 12MP 全尺寸的内存峰值 */
    private static final int FRAME_MAX_LONG = 2400;

    private FrameLayout root;
    private PreviewView preview;
    private View shutter;
    private android.widget.TextView hint;
    private ProcessCameraProvider provider;
    private ImageCapture imageCapture;
    private ExecutorService exec;
    private CropOverlayView cropView;
    private boolean capturing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo);

        root = findViewById(R.id.photo_root);
        preview = findViewById(R.id.photo_preview);
        shutter = findViewById(R.id.photo_shutter);
        hint = findViewById(R.id.photo_hint);
        findViewById(R.id.photo_close).setOnClickListener(v -> finish());
        shutter.setOnClickListener(v -> takePhoto());

        exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "spore-photo");
            t.setDaemon(true);
            return t;
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            hint.setText(R.string.photo_denied);
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA) {
            return;
        }
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            hint.setText(R.string.photo_denied);
        }
    }

    // ---------------------------------------------------------------- 相机

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                provider = future.get();
            } catch (Exception e) {
                hint.setText(getString(R.string.photo_failed,
                        e.getClass().getSimpleName()));
                return;
            }
            Preview previewUse = new Preview.Builder().build();
            previewUse.setSurfaceProvider(preview.getSurfaceProvider());
            imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build();
            provider.unbindAll();
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUse, imageCapture);
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * 快门 → 后台解 JPEG + 旋转 + 降采样（一步矩阵完成，峰值只有原帧 + 小图）
     → 主线程进框选。
     */
    private void takePhoto() {
        if (imageCapture == null || capturing || cropView != null) {
            return;
        }
        capturing = true;
        shutter.setAlpha(0.4f);
        imageCapture.takePicture(exec, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy ip) {
                try {
                    Bitmap frame = decodeFrame(ip);
                    if (frame == null) {
                        runOnUiThread(() -> failShutter(R.string.photo_decode_failed));
                        return;
                    }
                    runOnUiThread(() -> {
                        capturing = false;
                        shutter.setAlpha(1f);
                        showCrop(frame);
                    });
                } finally {
                    ip.close();
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException e) {
                Log.w("spore-photo", "capture failed: " + e);
                runOnUiThread(() -> failShutter(
                        getString(R.string.photo_failed, e.getClass().getSimpleName())));
            }
        });
    }

    private void failShutter(int msgRes) {
        capturing = false;
        shutter.setAlpha(1f);
        Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show();
    }

    private void failShutter(String msg) {
        capturing = false;
        shutter.setAlpha(1f);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /**
     * JPEG 字节 → 位图，按 EXIF 朝向一次矩阵做完<b>旋转 + 降采样</b>到长边 ≤2400。
     * 不走 ImageProxy.toBitmap()：JPEG 格式支持各版本不齐，平面字节解码是恒通的老路。
     */
    private static Bitmap decodeFrame(ImageProxy ip) {
        ImageProxy.PlaneProxy[] planes = ip.getPlanes();
        if (planes.length == 0) {
            return null;
        }
        ByteBuffer buf = planes[0].getBuffer();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        Bitmap raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (raw == null) {
            return null;
        }
        int rot = ip.getImageInfo().getRotationDegrees() % 360;
        // 旋转 90/270 后宽高对调：按旋转后的尺寸算缩放，一步到位
        int rw = (rot == 90 || rot == 270) ? raw.getHeight() : raw.getWidth();
        int rh = (rot == 90 || rot == 270) ? raw.getWidth() : raw.getHeight();
        float scale = Math.min(1f, (float) FRAME_MAX_LONG / Math.max(rw, rh));
        Matrix m = new Matrix();
        m.postRotate(rot);
        m.postScale(scale, scale);
        Bitmap out = Bitmap.createBitmap(raw, 0, 0,
                raw.getWidth(), raw.getHeight(), m, true);
        if (out != raw) {
            raw.recycle();
        }
        return out;
    }

    // ---------------------------------------------------------------- 框选（页内视图）

    private void showCrop(Bitmap frame) {
        hint.setVisibility(View.GONE);
        shutter.setVisibility(View.GONE);
        cropView = new CropOverlayView(this, frame, cropListener, true);
        root.addView(cropView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private final CropOverlayView.Listener cropListener = new CropOverlayView.Listener() {
        @Override
        public void onCropped(Bitmap crop) {
            // 落盘在后台（JPEG 压缩别背主线程），交活后本页即退 —— 记录详情页接力
            exec.execute(() -> {
                File saved = CaptureService.saveCrop(PhotoActivity.this, crop);
                runOnUiThread(() -> {
                    if (saved == null) {
                        Toast.makeText(PhotoActivity.this,
                                R.string.capture_failed_write, Toast.LENGTH_SHORT).show();
                        backToViewfinder();
                        return;
                    }
                    CaptureService.openPhotoSearch(PhotoActivity.this, saved.getAbsolutePath());
                    finish();
                });
            });
        }

        @Override
        public void onClose() {
            backToViewfinder(); // ✕ = 这张重拍（顶栏 ✕ 才是退出本页）
        }
    };

    private void backToViewfinder() {
        if (cropView != null) {
            root.removeView(cropView);
            cropView = null;
        }
        hint.setVisibility(View.VISIBLE);
        shutter.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (provider != null) {
            provider.unbindAll();
        }
        if (exec != null) {
            exec.shutdown();
        }
    }
}
