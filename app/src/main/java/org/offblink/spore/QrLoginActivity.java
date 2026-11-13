package org.offblink.spore;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import org.json.JSONObject;
import org.offblink.spore.sync.ServerConnector;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 扫码登录服务器（2026-11 重构：取代桌面扫码配对与账号口令直连）。
 *
 * <p>服务器登录页展示二维码，载荷 {@code {v:1, api, ticket}}（也接受
 * {@code spore://login?api=…&ticket=…}）。识别后直接 {@code POST {api}/auth/qr/exchange}
 * 换 JWT，成功落 {@link org.offblink.spore.sync.SporeSyncState} 并关页；失败留页可重扫。
 * 协议文档：{@code docs/qr-login-server.md}。
 *
 * <p>取景 = CameraX Preview + ImageAnalysis（KEEP_ONLY_LATEST）+ ML Kit QR 解码
 * （bundled 模型，无 GMS 依赖）。同码只受理一次；失败后自动重新武装。
 */
public class QrLoginActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 1001;
    /** 连续这么久解不出任何码 = 视线离开：坏码去重作废（再对准可再提醒一次） */
    private static final long QR_LEAVE_MS = 1500;

    private PreviewView preview;
    private TextView status;
    private TextView torchBtn;
    private ProcessCameraProvider provider;
    private Camera camera;
    private BarcodeScanner scanner;
    private ExecutorService analyzeExec;
    private final AtomicBoolean handled = new AtomicBoolean(false);
    private final AtomicBoolean analyzing = new AtomicBoolean(false);
    private boolean torchOn;
    private volatile long lastQrAt;
    private volatile String lastBadRaw;
    private Toast badToast;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pair);

        preview = findViewById(R.id.pair_preview);
        status = findViewById(R.id.pair_status);
        torchBtn = findViewById(R.id.pair_torch);
        findViewById(R.id.pair_close).setOnClickListener(v -> finish());
        torchBtn.setOnClickListener(v -> toggleTorch());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            status.setText(R.string.pair_camera_denied);
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
            status.setText(R.string.pair_camera_denied);
        }
    }

    // ---------------------------------------------------------------- 相机

    private void startCamera() {
        status.setText(R.string.pair_scanning);
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                provider = future.get();
            } catch (Exception e) {
                status.setText(getString(R.string.capture_failed,
                        e.getClass().getSimpleName()));
                return;
            }
            Preview previewUse = new Preview.Builder().build();
            previewUse.setSurfaceProvider(preview.getSurfaceProvider());

            ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
            analyzeExec = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "spore-qr-scan");
                t.setDaemon(true);
                return t;
            });
            scanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build());
            analysis.setAnalyzer(analyzeExec, this::analyzeFrame);

            provider.unbindAll();
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUse, analysis);
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFrame(ImageProxy image) {
        if (lastBadRaw != null
                && SystemClock.uptimeMillis() - lastQrAt > QR_LEAVE_MS) {
            lastBadRaw = null;
        }
        if (handled.get() || !analyzing.compareAndSet(false, true)) {
            image.close();
            return;
        }
        if (image.getImage() == null) {
            analyzing.set(false);
            image.close();
            return;
        }
        InputImage input = InputImage.fromMediaImage(
                image.getImage(), image.getImageInfo().getRotationDegrees());
        scanner.process(input)
                .addOnSuccessListener(barcodes -> {
                    for (Barcode b : barcodes) {
                        String raw = b.getRawValue();
                        if (!TextUtils.isEmpty(raw)) {
                            onQr(raw);
                            break;
                        }
                    }
                })
                .addOnCompleteListener(t -> {
                    analyzing.set(false);
                    image.close();
                });
    }

    private void toggleTorch() {
        if (camera == null) {
            return;
        }
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
        torchBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this,
                        torchOn ? R.color.spore_pink : R.color.spore_pair_btn_bg)));
    }

    // ---------------------------------------------------------------- 登录

    /** 识别到码：解析载荷 → 直接换 token（失败自动重新武装，可继续扫） */
    private void onQr(String raw) {
        lastQrAt = SystemClock.uptimeMillis();
        if (!handled.compareAndSet(false, true)) {
            return;
        }
        String api = "";
        String ticket = "";
        try {
            if (raw.startsWith("{")) {
                JSONObject o = new JSONObject(raw);
                api = o.optString("api", "");
                ticket = o.optString("ticket", "");
            } else {
                Uri u = Uri.parse(raw);
                if ("spore".equals(u.getScheme()) && "login".equals(u.getHost())) {
                    api = u.getQueryParameter("api");
                    ticket = u.getQueryParameter("ticket");
                    if (api == null) {
                        api = "";
                    }
                    if (ticket == null) {
                        ticket = "";
                    }
                }
            }
        } catch (Exception ignored) {
            // 落到空判
        }
        api = api.trim();
        ticket = ticket.trim();
        if (api.isEmpty() || ticket.isEmpty()) {
            handled.set(false);
            // 同码在视野里只提醒一次（离开 1.5s 后去重作废），Toast 绝不排队
            if (!raw.equals(lastBadRaw)) {
                lastBadRaw = raw;
                runOnUiThread(() -> {
                    if (badToast == null) {
                        badToast = Toast.makeText(QrLoginActivity.this,
                                R.string.pair_bad_qr, Toast.LENGTH_SHORT);
                    } else {
                        badToast.cancel();
                    }
                    badToast.show();
                });
            }
            return;
        }
        lastBadRaw = null;
        status.setText(R.string.qr_login_waiting);
        final String fApi = api;
        final String fTicket = ticket;
        new Thread(() -> {
            JSONObject r = ServerConnector.exchangeQr(this, fApi, fTicket);
            boolean ok = r != null && r.optBoolean("ok", false);
            runOnUiThread(() -> {
                if (ok) {
                    String nick = r.optString("nick", "服务器");
                    Toast.makeText(this, getString(R.string.pair_done, nick),
                            Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    String m = r == null ? "登录失败"
                            : r.optString("message", "登录失败");
                    status.setText(getString(R.string.pair_verify_failed, m));
                    handled.set(false); // 票错了/网断了：允许继续扫下一张
                }
            });
        }, "spore-qr-login").start();
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (provider != null) {
            provider.unbindAll();
        }
        if (scanner != null) {
            scanner.close();
        }
        if (analyzeExec != null) {
            analyzeExec.shutdown();
        }
    }
}
