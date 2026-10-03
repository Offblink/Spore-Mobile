package org.offblink.spore;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;
import org.offblink.spore.sync.SporeSyncState;
import org.offblink.spore.sync.SyncEngine;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设置页（web 皮）：读写 SporeSettings（端点/key/模型/检索代理/两阶段开关），
 * 外加「配对与同步」卡的桥（pairInfo/scan/syncStart/unpair，见 settings.js）。
 * 8+7 字段经桥 ready/save 落本机 SharedPreferences；
 * apiKey 红线同 SporeSettings——只活在本机，绝不进日志/仓库。
 * isConfigured() 的判定仍归 SporeSettings，页面只做「空了会拦」的警示提示。
 * 同步状态里的 token 不出 {@link SporeSyncState#toJson()}（红线同 apiKey）。
 */
public class SettingsActivity extends AppCompatActivity {

    private WebView web;

    /** 同步专用单线程（静态：跑在后台，不挂在页面生命周期上） */
    private static final ExecutorService SYNC_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spore-sync");
        t.setDaemon(true);
        return t;
    });

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

    /** 从扫码页/同步回来：让页面重拉配对态（页面已定义 __pairRefresh 才生效） */
    @Override
    protected void onResume() {
        super.onResume();
        web.evaluateJavascript("window.__pairRefresh && window.__pairRefresh()", null);
    }

    /** 返回统一出口：顶栏「返回」（桥 close）与系统返回键都过这里，挂平移反向转场 */
    @Override
    public void finish() {
        super.finish();
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE,
                    R.anim.spore_back_in, R.anim.spore_back_out);
        } else {
            //noinspection deprecation
            overridePendingTransition(R.anim.spore_back_in, R.anim.spore_back_out);
        }
    }

    /**
     * JS → 原生宿主对象 {@code window.Spore}（跑在 JavaBridge 线程）。
     * ready/save 都是 prefs 读写不碰 UI；只有 close/scan/unpair 的 UI 动作要切主线程。
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

        /** 配对与同步卡状态帧：{paired, api, nick, lastSyncAt, lastSyncMsg, running}（无 token） */
        @JavascriptInterface
        public String pairInfo() {
            JSONObject o = SporeSyncState.load(SettingsActivity.this).toJson();
            try {
                o.put("running", SyncEngine.isRunning());
            } catch (org.json.JSONException ignored) {
            }
            return o.toString();
        }

        /** 扫码配对页（相机权限在 PairActivity 里要） */
        @JavascriptInterface
        public void scan() {
            runOnUiThread(() -> startActivity(
                    new Intent(SettingsActivity.this, PairActivity.class)));
        }

        /** 立即同步（后台线程跑一轮；重复点由 isRunning 挡） */
        @JavascriptInterface
        public void syncStart() {
            if (SyncEngine.isRunning()) {
                return;
            }
            final android.content.Context app = getApplicationContext();
            SYNC_EXEC.execute(() -> SyncEngine.run(app));
        }

        /** 取消配对：原生确认框（页面不自带模态），确认后落状态并通知页面刷新 */
        @JavascriptInterface
        public void unpair() {
            runOnUiThread(() -> new AlertDialog.Builder(SettingsActivity.this)
                    .setTitle(R.string.pair_unpair_title)
                    .setMessage(R.string.pair_unpair_body)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.pair_unpair_yes, (d, w) -> {
                        SporeSyncState.load(SettingsActivity.this)
                                .unpair(SettingsActivity.this);
                        web.evaluateJavascript(
                                "window.__pairRefresh && window.__pairRefresh()", null);
                    })
                    .show());
        }

        /** 顶栏返回：finish 必须主线程，动画挂在 finish() 覆写里 */
        @JavascriptInterface
        public void close() {
            runOnUiThread(SettingsActivity.this::finish);
        }
    }
}
