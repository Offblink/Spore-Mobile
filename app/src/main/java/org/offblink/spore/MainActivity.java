package org.offblink.spore;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * 搜题记录主页（占位）+ 悬浮球开关与权限引导。
 * 悬浮窗唯一的存在理由：答题类 App 禁止切出，球让用户在对方 App 之上完成「截题 → 作答」。
 */
public class MainActivity extends AppCompatActivity {

    private Button btnBall;
    private TextView tvStatus;
    private ActivityResultLauncher<Intent> overlayLauncher;
    private ActivityResultLauncher<String> notifLauncher;
    /** 首授在**开球开关时**过掉（用户拍板）：截屏时不再启动任何授权页 */
    private ActivityResultLauncher<Intent> consentLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        btnBall = findViewById(R.id.btn_ball);
        tvStatus = findViewById(R.id.tv_status);

        overlayLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (Settings.canDrawOverlays(this)) {
                        CaptureService.start(this);
                    }
                    refreshStatus();
                });

        notifLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> { });

        consentLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    Intent data = result.getData();
                    if (result.getResultCode() == RESULT_OK && data != null) {
                        Intent i = new Intent(this, CaptureService.class);
                        i.setAction(CaptureService.ACTION_PROJECT);
                        i.putExtra(CaptureService.EXTRA_RESULT_CODE, result.getResultCode());
                        i.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
                        startService(i);
                    } else {
                        Toast.makeText(this, R.string.consent_denied, Toast.LENGTH_LONG).show();
                    }
                    refreshStatus();
                });

        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }

        btnBall.setOnClickListener(v -> toggleBall());

        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btn_record).setOnClickListener(v ->
                startActivity(new Intent(this, RecordActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void toggleBall() {
        if (CaptureService.isRunning()) {
            CaptureService.stop(this);
        } else if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
        } else {
            // 先起服务（FGS 就绪，Android 14+ 要求先 startForeground 才能拿投影），
            // 紧接着在应用内过首授——截屏时永远零弹窗、零前台抢占（用户实测拍板）
            CaptureService.start(this);
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            consentLauncher.launch(mpm.createScreenCaptureIntent());
        }
        refreshStatus();
    }

    /**
     * 华为/部分国产 ROM 不响应 ACTION_MANAGE_OVERLAY_PERMISSION → 兜底跳应用详情页。
     * （兼容性风险见 handoff §10）
     */
    private void requestOverlayPermission() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            overlayLauncher.launch(i);
        } catch (ActivityNotFoundException e) {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            overlayLauncher.launch(i);
            Toast.makeText(this, R.string.overlay_open_manual, Toast.LENGTH_LONG).show();
        }
    }

    private void refreshStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean running = CaptureService.isRunning();
        btnBall.setText(running ? R.string.stop_ball : R.string.start_ball);
        tvStatus.setText(!overlay ? R.string.status_no_overlay
                : running ? R.string.status_running : R.string.status_idle);
    }
}
