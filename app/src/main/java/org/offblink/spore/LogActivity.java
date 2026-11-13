package org.offblink.spore;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 运行日志（设置页「运行日志」入口进入）：正文 + 刷新/复制/清空都在 web 层
 * （{@code assets/web/logs.html}）。桥只做剪贴板与环形文件读写，红线同设置页：
 * 日志不含 token/apiKey。
 */
public class LogActivity extends AppCompatActivity {

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        web = findViewById(R.id.web);
        web.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.spore_window_bg));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setSupportZoom(false);
        WebView.setWebContentsDebuggingEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Host(), "Spore");
        web.loadUrl("file:///android_asset/web/logs.html");
    }

    /** JS → 原生（JavaBridge 线程）；剪贴板必须回主线程 */
    public class Host {

        @JavascriptInterface
        public String theme() {
            return SporeSettings.load(LogActivity.this).resolvedTheme(LogActivity.this);
        }

        @JavascriptInterface
        public String logRead() {
            return SporeLog.read(LogActivity.this);
        }

        @JavascriptInterface
        public boolean logCopy() {
            final String text = SporeLog.read(LogActivity.this);
            if (text == null || text.isEmpty()) {
                return false;
            }
            runOnUiThread(() -> {
                ClipboardManager cm =
                        (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("spore-log", text));
                Toast.makeText(LogActivity.this, "日志已复制", Toast.LENGTH_SHORT).show();
            });
            return true;
        }

        @JavascriptInterface
        public void logClear() {
            SporeLog.clear(LogActivity.this);
        }

        @JavascriptInterface
        public void close() {
            runOnUiThread(() -> LogActivity.this.finish());
        }
    }
}
