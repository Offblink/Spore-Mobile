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

import org.offblink.spore.agent.SessionStore;

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
        if (!maybeShowCrashReport()) {
            maybeShowFrameDiag();
        }
    }

    /**
     * 下次开主页：上次有崩溃栈/服务被异常杀 → 弹给用户（栈可复制发给开发者）。
     * 这是真机抓栈的唯一通道——悬浮窗死在别的 App 前台，logcat 拿不到。
     *
     * @return true = 已弹（调用方别叠第二个诊断弹窗）
     */
    private boolean maybeShowCrashReport() {
        String crash = CrashLog.readCrash(this);
        // 心跳（create 无 destroy）不弹：force-stop/划后台都会留这个形态，误报率过高且会
        // 堵死正常操作（第六轮实测）。文件仍保留（CrashLog.readHeartbeat）供排错手动查。
        if (crash.isEmpty()) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("上次运行发生崩溃（栈见下，点复制发给开发者）：\n\n").append(crash.trim());
        String msg = sb.length() > 6000 ? sb.substring(0, 3000) + "\n……\n" + sb.substring(sb.length() - 2600) : sb.toString();
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("检测到上次异常终止")
                .setMessage(msg)
                .setPositiveButton("知道了", (d, w) -> CrashLog.clearCrash(this))
                .setNeutralButton("复制", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("spore-crash", msg));
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                })
                .show();
        return true;
    }

    /**
     * 第九轮黑屏悬案：上次取帧「过闸但帧脏」（大片死黑 = 合成未完成 / 局部禁截）→ 弹给用户。
     * 「保存到相册」把**进框选之前的那张原始帧**存进 Pictures/Spore —— 真机不能 adb 取文件，
     * 这是现场帧回传开发者的唯一通道（配合 files/captures 里的成片一起判读）。
     */
    private void maybeShowFrameDiag() {
        String diag = FrameDiag.readPending(this);
        if (diag.isEmpty()) {
            return;
        }
        String[] lines = diag.split("\n");
        String path = lines[0].trim();
        if (path.isEmpty() || !new java.io.File(path).isFile()) {
            FrameDiag.clearPending(this); // 样本已被清理 → 别弹空窗
            return;
        }
        StringBuilder stats = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            stats.append(lines[i]).append('\n');
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("上次截屏取到的画面不完整")
                .setMessage("取帧里有大片黑色斑块（图层没合成完，或该界面禁止截屏）。"
                        + "已把那张原始帧存下来：点「保存到相册」，再去相册 Pictures/Spore "
                        + "找到它发给我，就能定位黑屏。\n\n帧统计：\n" + stats.toString().trim())
                .setPositiveButton("保存到相册", (d, w) -> {
                    boolean ok = SessionStore.saveToGallery(this, path);
                    Toast.makeText(this, ok
                                    ? "已存到相册 Pictures/Spore，把它发给我"
                                    : "保存失败（样本可能已被清理）",
                            Toast.LENGTH_LONG).show();
                    if (ok) {
                        FrameDiag.clearPending(this);
                    }
                })
                .setNegativeButton("知道了", (d, w) -> FrameDiag.clearPending(this))
                .show();
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

        /** 搜题记录页：新页自右平移进入、主页左移让位（转场由主题 SporeAnim 接管） */
        @JavascriptInterface
        public void openRecords() {
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, RecordActivity.class)));
        }

        /** 设置页：同上 */
        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, SettingsActivity.class)));
        }
    }
}
