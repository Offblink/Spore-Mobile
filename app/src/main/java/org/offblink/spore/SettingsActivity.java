package org.offblink.spore;

import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 设置页（web 皮）：读写 SporeSettings（端点/key/模型/检索代理/两阶段开关）。
 * 8 字段经桥 ready/save 落本机 SharedPreferences；
 * apiKey 红线同 SporeSettings——只活在本机，绝不进日志/仓库。
 * isConfigured() 的判定仍归 SporeSettings，页面只做「空了会拦」的警示提示。
 */
public class SettingsActivity extends AppCompatActivity {

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        web = findViewById(R.id.web);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setSupportZoom(false);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Host(), "Spore");
        web.loadUrl("file:///android_asset/web/settings.html");
    }

    /** 返回统一出口：顶栏「返回」（桥 close）与系统返回键都过这里，挂反向转场 */
    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.spore_back_in, R.anim.spore_back_out);
    }

    /**
     * JS → 原生宿主对象 {@code window.Spore}（跑在 JavaBridge 线程）。
     * ready/save 都是 prefs 读写不碰 UI；只有 close 的 finish 要切主线程。
     */
    public class Host {

        /** 首帧：整份设置 JSON（含 apiKey——本机 WebView，红线同原生页） */
        @JavascriptInterface
        public String ready() {
            return SporeSettings.load(SettingsActivity.this).toJson().toString();
        }

        /** 只覆盖传入 JSON 里出现的键；脏入参由 applyJson 自己拦（不落盘） */
        @JavascriptInterface
        public void save(String json) {
            SporeSettings.applyJson(SettingsActivity.this, json == null ? "{}" : json);
        }

        /** 顶栏返回：finish 必须主线程，动画挂在 finish() 覆写里 */
        @JavascriptInterface
        public void close() {
            runOnUiThread(SettingsActivity.this::finish);
        }
    }
}
