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

import org.offblink.spore.overlay.PhotoCropView;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 拍照搜题（主页入口，全页跑在应用内，不走悬浮窗那套）：
 * CameraX 取景按快门 → <b>立即关掉相机</b>、进应用内的框选页（PhotoCropView）：
 * 左上「取消」、右上「↻ 旋转（顺时针 90°）」、底部只有「确定」一个按钮 →
 * 确认后落盘 → {@link CaptureService#openPhotoSearch} 直接开搜题记录详情页看答案生成。
 */
public class PhotoActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 1001;
    /** 拍照帧长边上限：给框选留足裁剪余量（落盘还会再压到 1600），同时掐掉大帧的内存峰值 */
    private static final int FRAME_MAX_LONG = 2400;

    private FrameLayout root;
    private PreviewView preview;
    private View shutter;
    private android.widget.TextView hint;
    private View cancelBtn;
    private View rotateBtn;
    private View confirmBtn;
    private ProcessCameraProvider provider;
    private ImageCapture imageCapture;
    private ExecutorService exec;
    private PhotoCropView cropView;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo);

        root = findViewById(R.id.photo_root);
        preview = findViewById(R.id.photo_preview);
        shutter = findViewById(R.id.photo_shutter);
        hint = findViewById(R.id.photo_hint);
        cancelBtn = findViewById(R.id.photo_cancel);
        rotateBtn = findViewById(R.id.photo_rotate);
        confirmBtn = findViewById(R.id.photo_confirm);

        shutter.setOnClickListener(v -> takePhoto());
        rotateBtn.setOnClickListener(v -> {
            if (cropView != null) {
                cropView.rotate();
            }
        });
        confirmBtn.setOnClickListener(v -> confirmCrop());
        // 取消：框选态 = 这张重拍（回取景，相机重新起来）；取景态 = 退出本页
        cancelBtn.setOnClickListener(v -> {
            if (cropView != null) {
                backToViewfinder();
            } else {
                finish();
            }
        });

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
     * → 主线程进框选页，<b>顺手关掉相机</b>（拍完取景没用了，别白亮着）。
     */
    private void takePhoto() {
        if (imageCapture == null || busy || cropView != null) {
            return;
        }
        busy = true;
        shutter.setAlpha(0.4f);
        imageCapture.takePicture(exec, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy ip) {
                try {
                    Bitmap frame = decodeFrame(ip);
                    if (frame == null) {
                        runOnUiThread(() -> failBusy(R.string.photo_decode_failed));
                        return;
                    }
                    runOnUiThread(() -> {
                        busy = false;
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
                runOnUiThread(() -> failBusy(
                        getString(R.string.photo_failed, e.getClass().getSimpleName())));
            }
        });
    }

    private void failBusy(int msgRes) {
        busy = false;
        shutter.setAlpha(1f);
        Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show();
    }

    private void failBusy(String msg) {
        busy = false;
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

    // ---------------------------------------------------------------- 框选页（应用内）

    private void showCrop(Bitmap frame) {
        // 拍完立即关相机（用户拍板）：解绑即停预览与出帧，页内只剩这张冻结帧
        if (provider != null) {
            provider.unbindAll();
        }
        preview.setVisibility(View.GONE);
        hint.setVisibility(View.GONE);
        shutter.setVisibility(View.GONE);
        rotateBtn.setVisibility(View.VISIBLE);
        confirmBtn.setVisibility(View.VISIBLE);

        cropView = new PhotoCropView(this, frame);
        root.addView(cropView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /** 底栏「确定」：没框/太小先提示；过了就落盘 → 起回合并跳记录详情 */
    private void confirmCrop() {
        if (cropView == null || busy) {
            return;
        }
        if (cropView.selectionTooSmall()) {
            Toast.makeText(this, R.string.photo_warn_pick, Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap crop = cropView.takeCrop();
        if (crop == null) {
            Toast.makeText(this, R.string.photo_warn_pick, Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        confirmBtn.setAlpha(0.5f);
        // 落盘在后台（JPEG 压缩别背主线程），交活后本页即退 —— 记录详情页接力
        exec.execute(() -> {
            File saved = CaptureService.saveCrop(PhotoActivity.this, crop);
            runOnUiThread(() -> {
                busy = false;
                confirmBtn.setAlpha(1f);
                if (saved == null) {
                    Toast.makeText(PhotoActivity.this,
                            R.string.capture_failed_write, Toast.LENGTH_SHORT).show();
                    return;
                }
                CaptureService.openPhotoSearch(PhotoActivity.this, saved.getAbsolutePath());
                finish();
            });
        });
    }

    private void backToViewfinder() {
        if (cropView != null) {
            root.removeView(cropView);
            cropView = null;
        }
        rotateBtn.setVisibility(View.GONE);
        confirmBtn.setVisibility(View.GONE);
        hint.setVisibility(View.VISIBLE);
        shutter.setVisibility(View.VISIBLE);
        preview.setVisibility(View.VISIBLE);
        startCamera(); // 相机在进框选时被解绑了，回取景再起
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
