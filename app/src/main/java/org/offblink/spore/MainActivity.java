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
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * 主页（web 皮）+ 悬浮球开关与权限引导。
 * 皮在 assets/web/main.html（字标 / 状态胶囊 / 主 CTA / 分组导航卡），权限、授权与
 * 服务开关仍全在原生——悬浮窗唯一的存在理由：答题类 App 禁止切出，球让用户在对方
 * App 之上完成「截题 → 作答」。
 */
public class MainActivity extends AppCompatActivity {

    private WebView web;
    /** JS 调过 ready() 后才允许推 onState（桥握手门闩）；JavaBridge 线程写、主线程读 */
    private volatile boolean webReady;
    private ActivityResultLauncher<Intent> overlayLauncher;
    private ActivityResultLauncher<String> notifLauncher;
    /** 首授在**开球开关时**过掉（用户拍板）：截屏时不再启动任何授权页 */
    private ActivityResultLauncher<Intent> consentLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        web = findViewById(R.id.web);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setSupportZoom(false);
        // web 皮排错入口：chrome://inspect 可挂 + 页面 console 落 logcat（桥断/JS 异常不再盲猜）
        WebView.setWebContentsDebuggingEnabled(true);
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                Log.d("spore-web", m.message() + " [" + m.sourceId() + ":" + m.lineNumber() + "]");
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Host(), "Spore");

        overlayLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (Settings.canDrawOverlays(this)) {
                        startBallWithConsent();
                    }
                    pushState();
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
                    pushState();
                });

        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }

        web.loadUrl("file:///android_asset/web/main.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回页即刷新：悬浮窗权限/服务状态可能在系统授权页里被改掉
        pushState();
    }

    /**
     * 状态全量快照 → {@code Host.onState(...)}。只在 JS 握过手后推，且必须主线程。
     */
    private void pushState() {
        if (!webReady) {
            return;
        }
        web.evaluateJavascript("Host.onState(" + stateJson() + ")", null);
    }

    /** 值域只有三个布尔，手工拼就是合法 JSON = 合法 JS 字面量 */
    private String stateJson() {
        return "{\"overlay\":" + Settings.canDrawOverlays(this)
                + ",\"running\":" + CaptureService.isRunning()
                + ",\"projection\":" + CaptureService.hasProjection() + "}";
    }

    private void toggleBall() {
        if (CaptureService.isRunning()) {
            CaptureService.stop(this);
        } else if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
        } else {
            startBallWithConsent();
        }
        pushState();
    }

    /**
     * 开球 = 起服务（FGS 就绪，Android 14+ 要求先 startForeground 才能拿投影）
     * + **应用内**过首授——截屏时永远零弹窗、零前台抢占（用户实测拍板）。
     */
    private void startBallWithConsent() {
        CaptureService.start(this);
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        consentLauncher.launch(mpm.createScreenCaptureIntent());
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

    /**
     * JS → 原生宿主对象 {@code window.Spore}（跑在 JavaBridge 线程）。
     * 只有加 {@link JavascriptInterface} 的 public 方法对页面可见。
     */
    public class Host {

        /** 首帧：置握手门闩并返回状态快照；开关后 JS 延迟 400ms 重拉也走这里 */
        @JavascriptInterface
        public String ready() {
            webReady = true;
            return stateJson();
        }

        /** 开/关悬浮球：起服务与发 Intent 都必须主线程（这里不能用 Host 自身的 this 引用） */
        @JavascriptInterface
        public void toggleBall() {
            runOnUiThread(MainActivity.this::toggleBall);
        }

        /** 「截屏授权已失效 · 点此重新授权」：球照跑、只重过授权（不许走 toggleBall——那会把球关了） */
        @JavascriptInterface
        public void reauth() {
            runOnUiThread(MainActivity.this::startBallWithConsent);
        }

        /** 搜题记录页：新页右侧滑入、主页左移淡出 */
        @JavascriptInterface
        public void openRecords() {
            runOnUiThread(() -> {
                startActivity(new Intent(MainActivity.this, RecordActivity.class));
                overridePendingTransition(R.anim.spore_nav_in, R.anim.spore_nav_out);
            });
        }

        /** 设置页：同上 */
        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(() -> {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                overridePendingTransition(R.anim.spore_nav_in, R.anim.spore_nav_out);
            });
        }
    }
}
