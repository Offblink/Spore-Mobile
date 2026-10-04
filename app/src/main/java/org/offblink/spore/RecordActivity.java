package org.offblink.spore;

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
import org.offblink.spore.agent.SubjectsStore;

/**
 * 搜题记录（web 皮）：
 * 列表 = 一滑到底的长页面 + 「全部⇄收藏」筛选 + 页内模态（成功留列表原地）；
 * 长按卡片进多选（单选框涂抹连选，批量收藏/移入科目/删除）；
 * 点行 = <b>独立详情视图</b>（第四轮反馈 2①：不再借悬浮抽屉），详情追问经
 * {@link CaptureService#followup} 走同一引擎、轮询刷新（详情页不是引擎监听器）。
 * 转场：主题 SporeAnim 接管（进右滑入/回右滑出）；硬件返回先问页内 Host.back
 * （详情回列表 → 退多选）。
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

    /** 硬件返回：先给页内消费（详情 → 列表 → 退多选）；列表态才退出 */
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

    private void pushState() {
        if (!webReady) {
            return;
        }
        web.evaluateJavascript("Host.onState(" + stateJson() + ")", null);
    }

    /** {sessions:[…], subjects:[…]} —— metaJson/SubjectsStore 自带异常兜底，恒可拼 */
    private String stateJson() {
        return "{\"sessions\":" + SessionStore.metaJson(this)
                + ",\"subjects\":" + SubjectsStore.metaJson(this) + "}";
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
            // 该会话正在生成 → 用引擎的内存活对象：文件里的 status 会被归一成 done、
            // 消息也是上一回合的旧数据（第九轮并行：详情页每秒轮询要看的就是生成中的现场）
            Session live = CaptureService.liveSession(id);
            Session s = live != null ? live
                    : SessionStore.load(RecordActivity.this, id == null ? "" : id);
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

        // ---------------------------------------------------------------- 科目（三件套的桥）

        /** 新建科目 → 返回整行 JSON（web 取 id/name）；名字非法 → null */
        @JavascriptInterface
        public String subjCreate(String name) {
            org.json.JSONObject o = SubjectsStore.create(RecordActivity.this, name);
            return o == null ? null : o.toString();
        }

        /** 重命名科目（只改名不动成员；同 Spore 远端 renameSubject） */
        @JavascriptInterface
        public boolean subjRename(String id, String name) {
            return SubjectsStore.rename(RecordActivity.this, id, name);
        }

        /** 删科目：先清全部会话引用再摘行（悬挂引用防护在 CaptureService 里） */
        @JavascriptInterface
        public boolean subjDelete(String id) {
            return CaptureService.deleteSubject(RecordActivity.this, id);
        }

        /** 会话移入科目；subjectId 空 = 移出（回未分组） */
        @JavascriptInterface
        public boolean subjAssign(String sessionId, String subjectId) {
            return CaptureService.assignSubject(RecordActivity.this, sessionId, subjectId);
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
