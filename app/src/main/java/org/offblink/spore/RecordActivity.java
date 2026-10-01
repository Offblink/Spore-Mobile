package org.offblink.spore;

import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

import org.offblink.spore.agent.Session;
import org.offblink.spore.agent.SessionStore;

/**
 * 搜题记录（web 皮，第四轮「三面全进」）：
 * 列表 = 15/页 分页 + 「全部⇄收藏」筛选 + 页内模态（成功留列表原地）；
 * 点行 = <b>独立详情视图</b>（第四轮反馈 2①：不再借悬浮抽屉），详情追问经
 * {@link CaptureService#followup} 走同一引擎、轮询刷新（详情页不是引擎监听器）。
 * 转场：finish 挂反向动画；硬件返回先问页内 Host.back（详情回列表）。
 */
public class RecordActivity extends AppCompatActivity {

    private WebView web;
    /** JS 握过 ready() 才允许推 onState / 问 Host.back；JavaBridge 线程写、主线程读 */
    private volatile boolean webReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_record);

        web = findViewById(R.id.web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setSupportZoom(false);
        // web 皮排错入口：chrome://inspect + 页面 console 落 logcat（tag spore-web）
        WebView.setWebContentsDebuggingEnabled(true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d("spore-web", m.message() + " [" + m.sourceId() + ":" + m.lineNumber() + "]");
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Host(), "Spore");
        web.loadUrl("file:///android_asset/web/record.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        pushState(); // 回页即刷新（面板回合结束已落盘，所见即所得）
    }

    /** 硬件返回：先给页内消费（详情 → 列表）；列表态才退出（挂反向转场） */
    @Override
    public void onBackPressed() {
        if (web == null || !webReady) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("Host.back()", v -> {
            if (!"true".equals(v)) {
                runOnUiThread(() -> RecordActivity.super.onBackPressed());
            }
        });
    }

    /** 返回统一出口（页内顶栏「‹」走桥 close → finish）：挂平移反向转场（原路出去） */
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

    private void pushState() {
        if (!webReady) {
            return;
        }
        web.evaluateJavascript("Host.onState(" + stateJson() + ")", null);
    }

    /** {sessions:[…]} —— metaJson 自带异常兜底，恒可拼 */
    private String stateJson() {
        return "{\"sessions\":" + SessionStore.metaJson(this) + "}";
    }

    /**
     * JS → 原生宿主对象 {@code window.Spore}（JavaBridge 线程）：
     * 文件 IO 直跑；finish 必须主线程。
     */
    public class Host {

        @JavascriptInterface
        public String ready() {
            webReady = true;
            return stateJson();
        }

        @JavascriptInterface
        public String sessions() {
            return stateJson();
        }

        /** 单会话完整 JSON（详情视图数据源）；不存在/损坏回 {} */
        @JavascriptInterface
        public String session(String id) {
            Session s = SessionStore.load(RecordActivity.this, id == null ? "" : id);
            if (s == null) {
                return "{}";
            }
            try {
                return SessionStore.toJson(s).toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String image(String path) {
            return SessionStore.imageDataUrl(path);
        }

        /** 「保存到相册」：全屏查看器的保存按钮（见 common.js openShot） */
        @JavascriptInterface
        public boolean saveImage(String path) {
            return SessionStore.saveToGallery(RecordActivity.this, path);
        }

        @JavascriptInterface
        public boolean rename(String id, String name) {
            return CaptureService.renameSession(RecordActivity.this, id, name);
        }

        @JavascriptInterface
        public boolean delete(String id) {
            return CaptureService.deleteSession(RecordActivity.this, id);
        }

        @JavascriptInterface
        public boolean fav(String id) {
            return CaptureService.toggleFav(RecordActivity.this, id);
        }

        /** ok | busy | gone（服务没跑会排队补发，见 CaptureService.followup） */
        @JavascriptInterface
        public String followup(String id, String text) {
            return CaptureService.followup(RecordActivity.this, id, text);
        }

        @JavascriptInterface
        public void close() {
            runOnUiThread(RecordActivity.this::finish);
        }
    }
}
