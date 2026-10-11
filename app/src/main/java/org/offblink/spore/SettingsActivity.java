package org.offblink.spore;

import android.content.Intent;
import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;
import org.offblink.spore.sync.SporeSyncState;
import org.offblink.spore.sync.SyncEngine;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设置页（web 皮）：读写 SporeSettings（端点/key/模型/检索代理/两阶段开关），
 * 外加「配对与同步」卡的桥（pairInfo/scan/syncStart/unpair，见 settings.js）。
 * 9+7 字段经桥 ready/save 落本机 SharedPreferences；
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
        web.loadUrl("file:///android_asset/web/settings.html" + fromHash());
    }

    /**
     * 底栏入场行程的来源 tab（主页/记录开我们时经 {@link MainActivity#EXTRA_FROM} 带进来）：
     * 页内胶囊从那一格弹射到「设置」这一格。用 URL 片段（#）而不是 query——
     * file:///android_asset 的资源解析不认 query。白名单取值，别把外部串拼进 URL。
     */
    private String fromHash() {
        Intent it = getIntent();
        String from = it == null ? null : it.getStringExtra(MainActivity.EXTRA_FROM);
        return ("home".equals(from) || "records".equals(from)) ? "#from=" + from : "";
    }

    /** 从扫码页/同步回来：让页面重拉配对态（页面已定义 __pairRefresh 才生效） */
    @Override
    protected void onResume() {
        super.onResume();
        web.evaluateJavascript("window.__pairRefresh && window.__pairRefresh()", null);
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

        /** 操作日志尾部（spore.log ≤64KB）；红线：日志本身不含 token/apiKey */
        @JavascriptInterface
        public String logRead() {
            return SporeLog.read(SettingsActivity.this);
        }

        /** 清空操作日志 */
        @JavascriptInterface
        public void logClear() {
            SporeLog.clear(SettingsActivity.this);
        }

        /** 复制整份日志到剪贴板（剪贴板 API 必须走原生；日志本身不含 token/apiKey）。
         * @return true = 已复制；false = 日志为空（页面据此提示） */
        @JavascriptInterface
        public boolean logCopy() {
            final String text = SporeLog.read(SettingsActivity.this);
            if (text == null || text.isEmpty()) {
                return false;
            }
            runOnUiThread(() -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("spore-log", text));
            });
            return true;
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

        /** 取消配对：应用皮确认框（SporePairDialog + dialog_unpair，零原生 chrome） */
        @JavascriptInterface
        public void unpair() {
            runOnUiThread(() -> {
                View body = getLayoutInflater().inflate(R.layout.dialog_unpair, null);
                Dialog dialog = new Dialog(SettingsActivity.this, R.style.SporePairDialog);
                dialog.setContentView(body);
                dialog.setCancelable(true);
                body.findViewById(R.id.unpair_no).setOnClickListener(v -> dialog.dismiss());
                body.findViewById(R.id.unpair_yes).setOnClickListener(v -> {
                    SporeSyncState.load(SettingsActivity.this)
                            .unpair(SettingsActivity.this);
                    web.evaluateJavascript(
                            "window.__pairRefresh && window.__pairRefresh()", null);
                    dialog.dismiss();
                });
                dialog.show();
            });
        }

        /** 顶栏返回：finish 必须主线程，动画挂在 finish() 覆写里 */
        @JavascriptInterface
        public void close() {
            runOnUiThread(SettingsActivity.this::finish);
        }

        /** 底栏切页：设置 → 记录。关自己再开目标（栈不叠页，返回键恒回主页）。
         *  2→1 是「往回」的 tab：主题默认转场是前进方向（新页自右滑入），跟胶囊向左的行程
         *  正好相反 → 这一跳换成反向转场，页面与胶囊同向。 */
        @JavascriptInterface
        public void openRecords() {
            runOnUiThread(() -> {
                startActivity(new Intent(SettingsActivity.this, RecordActivity.class)
                        .putExtra(MainActivity.EXTRA_FROM, "settings"));
                finish();
                backwardTransition();
            });
        }

        /** 反向转场：34+ 用 overrideActivityTransition；本 App 未启用预测性返回，
         *  旧 API 仍生效，两边都调，哪个平台认哪个。 */
        private void backwardTransition() {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN,
                        R.anim.slide_in_left, R.anim.slide_out_right);
            }
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
        }
    }
}
