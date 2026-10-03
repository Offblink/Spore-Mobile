package org.offblink.spore;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
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

import org.json.JSONException;
import org.json.JSONObject;
import org.offblink.spore.sync.SporeSyncState;
import org.offblink.spore.sync.SyncClient;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 扫码配对：扫 Spore-GUI 主窗左下角头像的二维码（载荷 {@code {v:1, api, token}}，
 * design/02 认证节拍板）→ 弹确认框（<b>接口地址可改</b>——GUI 现在发的 api 是它本机
 * 回环地址，手机必须改成电脑局域网 IP 才连得上）→ {@code GET /users/me} 连通校验
 * → 落 {@link SporeSyncState}（游标清零，下轮全量交换）。
 *
 * <p>取景 = CameraX Preview + ImageAnalysis（KEEP_ONLY_LATEST）+ ML Kit QR 解码
 * （bundled 模型，无 GMS 依赖，同中文识别口径）。同码只受理一次；确认框取消/连不上
 * 都回到取景（handled 复位）。
 */
public class PairActivity extends AppCompatActivity {

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
    /** 最近一次解出码的时刻（坏码/好码都算——视线离开判定的基准） */
    private volatile long lastQrAt;
    /** 最近提醒过的坏码原文：同码在视野里不重复弹（弹串根因，见 onQr） */
    private volatile String lastBadRaw;
    /** 单实例复用：Toast 队列一旦被灌满，移开视线后还会排队放一串 */
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
                Thread t = new Thread(r, "spore-pair-scan");
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

    /** 帧 → QR（分析线程）。busy 闸防并发 process；关帧一律在 complete 里做 */
    private void analyzeFrame(ImageProxy image) {
        // 视线离开判定（每帧都跑，哪怕本帧被跳过）：>QR_LEAVE_MS 没再解出任何码，
        // 坏码去重作废——回到视野重新对准时允许再提醒一次
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

    // ---------------------------------------------------------------- 配对

    /** 识别到码：受理一次 → 解析载荷 → 确认框。不是 Spore 码 → 提示一次（同码在视野里不重弹） */
    private void onQr(String raw) {
        lastQrAt = SystemClock.uptimeMillis();
        if (!handled.compareAndSet(false, true)) {
            return;
        }
        JSONObject payload = null;
        try {
            JSONObject o = new JSONObject(raw);
            String api = o.optString("api", "");
            String token = o.optString("token", "");
            if (!api.isEmpty() && !"null".equals(api)
                    && !token.isEmpty() && !"null".equals(token)) {
                payload = o;
            }
        } catch (JSONException ignored) {
            // 落到下面的空判
        }
        if (payload == null) {
            handled.set(false);
            // 弹串根因（用户实测）：坏码在视野里每帧都解出 → 立刻重武装 → 每帧一弹，
            // Toast 队列被灌满，视线移开后还在排队放。同码整个视野期只提醒一次；
            // 离开 1.5s（analyzeFrame 的 QR_LEAVE_MS）后去重作废，再对准再提醒一次。
            if (!raw.equals(lastBadRaw)) {
                lastBadRaw = raw;
                runOnUiThread(() -> {
                    if (badToast == null) {
                        badToast = Toast.makeText(PairActivity.this,
                                R.string.pair_bad_qr, Toast.LENGTH_SHORT);
                    } else {
                        badToast.cancel(); // 绝不排队：旧的没放完就先掐掉
                    }
                    badToast.show();
                });
            }
            return;
        }
        lastBadRaw = null; // 扫到真码：坏码去重一并作废
        JSONObject finalPayload = payload;
        runOnUiThread(() -> showConfirm(finalPayload));
    }

    private void showConfirm(JSONObject payload) {
        View body = getLayoutInflater().inflate(R.layout.dialog_pair, null);
        EditText apiEdit = body.findViewById(R.id.pair_api);
        TextView tokenView = body.findViewById(R.id.pair_token);
        TextView errorView = body.findViewById(R.id.pair_error);
        TextView loopbackView = body.findViewById(R.id.pair_loopback);

        String api = payload.optString("api", "").trim();
        String token = payload.optString("token", "").trim();
        apiEdit.setText(api);
        tokenView.setText(getString(R.string.pair_token_got, token.length()));
        if (api.contains("127.0.0.1") || api.contains("localhost")) {
            loopbackView.setVisibility(View.VISIBLE);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.pair_title)
                .setView(body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.pair_connect, null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (!isFinishing()) {
                handled.set(false); // 取消/连不上 → 回取景继续扫
                status.setText(R.string.pair_scanning);
            }
        });
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String api2 = apiEdit.getText().toString().trim();
                    if (api2.isEmpty()) {
                        errorView.setText(R.string.pair_api_empty);
                        errorView.setVisibility(View.VISIBLE);
                        return;
                    }
                    TextView btn = (TextView) v;
                    btn.setEnabled(false);
                    btn.setText(R.string.pair_connecting);
                    errorView.setVisibility(View.GONE);
                    verify(api2, token, dialog, errorView, btn);
                }));
        status.setText(R.string.pair_title);
        dialog.show();
    }

    /** 连通校验（后台线程）：GET /users/me 通了才落配对态、关页 */
    private void verify(String api, String token, AlertDialog dialog,
                        TextView errorView, TextView btn) {
        new Thread(() -> {
            try {
                JSONObject me = SyncClient.me(api, token);
                String nick = me == null ? "" : me.optString("nickname", "");
                if (nick.isEmpty() || "null".equals(nick)) {
                    nick = me == null ? "" : me.optString("username", "");
                }
                if (nick.isEmpty() || "null".equals(nick)) {
                    nick = "PC";
                }
                String nickname = nick;
                SporeSyncState st = SporeSyncState.load(this);
                st.pair(this, api, token, nickname); // 游标清零：换配对 = 全量交换
                runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.pair_done, nickname),
                            Toast.LENGTH_SHORT).show();
                    finish();
                });
            } catch (Exception e) {
                String m = e.getMessage();
                if (m == null || m.isEmpty()) {
                    m = e.getClass().getSimpleName();
                }
                final String errMsg = m;
                runOnUiThread(() -> {
                    if (dialog.isShowing()) {
                        errorView.setText(getString(R.string.pair_verify_failed, errMsg));
                        errorView.setVisibility(View.VISIBLE);
                        btn.setEnabled(true);
                        btn.setText(R.string.pair_connect);
                    }
                });
            }
        }, "spore-pair-verify").start();
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
