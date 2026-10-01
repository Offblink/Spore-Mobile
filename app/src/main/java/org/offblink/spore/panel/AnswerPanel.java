package org.offblink.spore.panel;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.offblink.spore.CrashLog;
import org.offblink.spore.R;
import org.offblink.spore.agent.AgentEngine;
import org.offblink.spore.agent.Session;
import org.offblink.spore.agent.SessionStore;

import java.io.File;

/**
 * 浮动作答面板 —— WebView 宿主（第四轮架构切换「三面全进 web」）：
 * 原生只剩窗口管理 + 桥，DOM/CSS/行为全在 {@code assets/web/panel.html}（桌面 drawer 复刻）。
 *
 * <p>契约（改一边必须同步另一边，JS 侧见 assets/web/common.js 头注）：
 * <ul>
 *   <li>JS → 原生：{@code window.Spore} = 本类带 {@link JavascriptInterface} 的方法；</li>
 *   <li>原生 → JS：{@code Host.onEvent(ev)} 流式增量、{@code Host.onState(st)} 全量快照；
 *       只在 JS 调过 {@link #ready()} 后才推（webReady 门闩）。</li>
 *   <li>结构性事件（session-new / answer-start / chat-start / turn-end / error）之后
 *       追推一次全量 state —— 流式增量只带 delta，消息列表本体靠 state 补。</li>
 * </ul>
 *
 * <p>窗口参数与旧版一致：TYPE_APPLICATION_OVERLAY、高 55%、可聚焦（输入法）；
 * WebView 实例跨 show/close 复用（重开零重载），服务销毁时 {@link #destroy()}。
 */
public final class AnswerPanel implements Panel {

    private static final String PAGE = "file:///android_asset/web/panel.html";

    private final Context ctx;
    private final WindowManager wm;
    private final AgentEngine engine;
    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private WebView web;
    private WindowManager.LayoutParams params;
    private int baseHeight;
    private int baseY;
    private boolean visible;
    /** JS 已调过 ready()：没这道闩，首屏加载前的推送全是空跑 */
    private boolean webReady;
    private int dragStartX;
    private int dragStartY;

    public AnswerPanel(Context ctx, WindowManager wm, AgentEngine engine) {
        this.ctx = ctx;
        this.wm = wm;
        this.engine = engine;
    }

    // ---------------------------------------------------------------- 生命周期（CaptureService 调用面，签名不许变）

    /** 截图落盘后调用：开面板 + 起两阶段回合 */
    public void openWithCapture(File crop) {
        show();
        engine.newCaptureTurn(crop.getAbsolutePath(), "");
    }

    /** 球长按 = 面板开/关（第八轮拍板：不再连带弹会话列表，列表由面板内 💬 手动开） */
    @Override
    public void toggle() {
        if (!visible) {
            show();
            return;
        }
        close();
    }

    public void show() {
        if (visible) {
            return;
        }
        if (root == null) {
            build();
        }
        visible = true;
        Point sz = displaySize();
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                Math.round(sz.y * 0.55f),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // 可聚焦（否则输入法拉不起来）；KEEP_SCREEN_ON = 面板开着就锁不了屏——
                // 锁屏/闲置会触发系统收回投屏 → 杀服务 → 悬浮窗全灭（第五轮根因链）
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = 0;
        params.y = Math.round(sz.y * 0.45f);
        baseHeight = params.height;
        baseY = params.y;
        try {
            wm.addView(root, params);
        } catch (Exception e) {
            // 悬浮窗权限被系统拒了：退回去，别把服务带崩
            visible = false;
            Toast.makeText(ctx, R.string.status_no_overlay, Toast.LENGTH_SHORT).show();
            return;
        }
        // 重开时页面还活着 → 立刻全量刷新；首开由 JS 的 ready() 接管
        pushState();
        // 打开永远是干净面板：上次开着的会话列表不跟出来（第八轮拍板）
        eval("window.SporePanel && SporePanel.closeList()");

        // MV3 进场：上滑 + 淡入 .42s（同旧版）
        root.setTranslationY(params.height * 0.3f);
        root.setAlpha(0f);
        root.animate().translationY(0f).alpha(1f)
                .setDuration(420)
                .setInterpolator(AnimationUtils.loadInterpolator(
                        ctx, android.R.interpolator.fast_out_slow_in))
                .start();
    }

    public void close() {
        if (!visible) {
            return;
        }
        visible = false;
        final View dying = root;
        if (dying != null && params != null) {
            dying.animate().translationY(params.height * 0.3f).alpha(0f)
                    .setDuration(240)
                    .setInterpolator(AnimationUtils.loadInterpolator(
                            ctx, android.R.interpolator.fast_out_slow_in))
                    .withEndAction(() -> {
                        try {
                            wm.removeView(dying);
                        } catch (Exception ignored) {
                            // 窗口可能已被系统移除
                        }
                    })
                    .start();
        }
    }

    /** 服务 onDestroy 时调：WebView 是重对象，必须显式销毁 */
    public void destroy() {
        visible = false;
        if (root != null) {
            try {
                wm.removeViewImmediate(root);
            } catch (Exception ignored) {
                // 未 attach 或已被移除
            }
        }
        if (web != null) {
            try {
                web.destroy();
            } catch (Exception ignored) {
            }
            web = null;
        }
        root = null;
    }

    // ---------------------------------------------------------------- 宿主搭建（一次，跨 show/close 复用）

    private void build() {
        root = new FrameLayout(ctx);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        // 返回键：IME 开着时先被 IME 吃掉（收键盘）；空闲时落到这 → 收面板
        root.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                close();
                return true;
            }
            return false;
        });

        web = new WebView(ctx);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(false);
        s.setAllowFileAccess(true); // file:///android_asset 默认即通，显式声明免得 OEM 改默认
        // 透明底：面板页的圆角外透出屏幕内容（窗口 TRANSLUCENT + 页面 body 透明）
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(this, "Spore");
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        attachImeWatch();
        web.loadUrl(PAGE);
    }

    /**
     * 键盘不许盖住 composer：量 {@code getWindowVisibleDisplayFrame} 的被遮高度，
     * 键盘出现就收窄窗口（顶边不动 = 底边抬到键盘上沿），收起复原。
     * 阈值 80dp 免得把导航栏/状态栏误判成键盘。
     */
    private void attachImeWatch() {
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (!visible || params == null) {
                return;
            }
            Rect vis = new Rect();
            root.getWindowVisibleDisplayFrame(vis);
            int covered = (baseY + baseHeight) - vis.bottom;
            int th = (int) (80 * ctx.getResources().getDisplayMetrics().density);
            if (covered > th) {
                int targetH = Math.max(dp(160), baseHeight - covered);
                if (targetH != params.height) {
                    params.height = targetH;
                    params.y = baseY; // 顶边锚定，只缩底边
                    try {
                        wm.updateViewLayout(root, params);
                    } catch (Exception ignored) {
                    }
                }
            } else if (params.height != baseHeight) {
                params.height = baseHeight;
                params.y = baseY;
                try {
                    wm.updateViewLayout(root, params);
                } catch (Exception ignored) {
                }
            }
        });
    }

    // ---------------------------------------------------------------- 引擎事件 → JS（主线程）

    @Override
    public void onEvent(JSONObject ev) {
        if (!visible || !webReady || web == null) {
            return; // 收起期间只丢刷新：状态本体都在 session，重开时全量重绘（同旧版）
        }
        eval("Host.onEvent(" + safe(ev.toString()) + ")");
        switch (ev.optString("type")) {
            case "session-new":
            case "answer-start":
            case "chat-start":
            case "turn-end":
            case "error":
                // 增量事件不带消息列表本体（新 user/answer 行在引擎里）→ 追推全量
                pushState();
                break;
            default:
                break;
        }
    }

    private void pushState() {
        if (!visible || !webReady || web == null) {
            return;
        }
        eval("Host.onState(" + safe(stateJson()) + ")");
    }

    private String stateJson() {
        try {
            JSONObject o = new JSONObject();
            Session cur = engine.session();
            o.put("session", SessionStore.toJson(cur));
            o.put("sessions", SessionStore.metaJson(ctx));
            // busy 只看**当前会话**：别的会话在生成绝不锁这里（第九轮并行拍板）
            o.put("busy", engine.isBusy(cur.id));
            // 在生成中的会话 id 列表：列表行打「生成中」标，让并行看得见
            JSONArray running = new JSONArray();
            for (String id : engine.runningIds()) {
                running.put(id);
            }
            o.put("running", running);
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    private void eval(String js) {
        main.post(() -> {
            if (web != null) {
                web.evaluateJavascript(js, null);
            }
        });
    }

    /** org.json 不转义 U+2028/U+2029（行分隔符）：直接进 evaluateJavascript 会截断 JS 字符串字面量 */
    private static String safe(String json) {
        return json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }

    // ---------------------------------------------------------------- JS 桥（@JavascriptInterface 跑在 JavaBridge 线程）

    /** 桥线程兜底：JavaBridge 上任何未捕获 Throwable = 进程死（第五轮崩溃家族）→ 落盘回安全值 */
    private interface BridgeBody<T> {
        T get() throws Throwable;
    }

    private <T> T bridge(String what, T fallback, BridgeBody<T> body) {
        try {
            return body.get();
        } catch (Throwable t) {
            CrashLog.write(ctx, t, "bridge." + what);
            return fallback;
        }
    }

    /** 首屏握手：置闩 + 返回全量快照；球长按挂起的抽屉在这里补开 */
    @JavascriptInterface
    public String ready() {
        return bridge("ready", "{}", () -> {
            webReady = true;
            String json = stateJson();
            return json;
        });
    }

    /** 主动拉全量快照（JS 在动作后自行刷新用） */
    @JavascriptInterface
    public String state() {
        return bridge("state", "{}", () -> stateJson());
    }

    /** 截图 data URL：按 path 只取一次，前端缓存（见 common.js imageFor） */
    @JavascriptInterface
    public String image(String path) {
        return bridge("image", "", () -> SessionStore.imageDataUrl(path));
    }

    /** 「保存到相册」：全屏查看器的保存按钮（见 common.js openShot） */
    @JavascriptInterface
    public boolean saveImage(String path) {
        return bridge("saveImage", false, () -> SessionStore.saveToGallery(ctx, path));
    }

    @JavascriptInterface
    public void followup(String text) {
        bridge("followup", null, () -> {
            if (!engine.sendFollowup(text)) {
                // 同一会话已有回合在跑（不同会话并行不受影响）→ 给个明确提示
                main.post(() -> Toast.makeText(ctx, R.string.busy_wait, Toast.LENGTH_SHORT).show());
            }
            return null;
        });
    }

    @JavascriptInterface
    public void stop() {
        bridge("stop", null, () -> {
            engine.cancel();
            return null;
        });
    }

    @JavascriptInterface
    public void verifyNow() {
        bridge("verifyNow", null, () -> {
            engine.verifyOnly();
            return null;
        });
    }

    @JavascriptInterface
    public boolean fav(String id) {
        return bridge("fav", false, () -> engine.toggleFav(id));
    }

    @JavascriptInterface
    public boolean open(String id) {
        return bridge("open", false, () -> engine.loadSession(id));
    }

    @JavascriptInterface
    public boolean rename(String id, String name) {
        return bridge("rename", false, () -> engine.renameSession(id, name));
    }

    @JavascriptInterface
    public boolean delete(String id) {
        return bridge("delete", false, () -> engine.deleteSession(id));
    }

    /** 顶栏「—」收起 */
    @JavascriptInterface
    public void hide() {
        bridge("hide", null, () -> {
            main.post(this::close);
            return null;
        });
    }

    /**
     * 顶栏拖动：WebView 接管触摸后原生 header 监听没了，由 panel.js 转发。
     * dx/dy 是**设备像素**（JS 端已乘 devicePixelRatio）；down 只记起点。
     */
    @JavascriptInterface
    public void drag(String action, int dx, int dy) {
        bridge("drag", null, () -> {
            main.post(() -> {
                if (!visible || params == null || root == null) {
                    return;
                }
                if ("down".equals(action)) {
                    dragStartX = params.x;
                    dragStartY = params.y;
                    return;
                }
                if (!"move".equals(action)) {
                    return;
                }
                Point sz = displaySize();
                int maxX = Math.max(0, sz.x - (root.getWidth() > 0 ? root.getWidth() : sz.x));
                params.x = clamp(dragStartX + dx, 0, maxX);
                params.y = clamp(dragStartY + dy, 0, Math.max(0, sz.y - params.height));
                try {
                    wm.updateViewLayout(root, params);
                } catch (Exception ignored) {
                }
            });
            return null;
        });
    }

    // ---------------------------------------------------------------- 工具

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private Point displaySize() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            return new Point(b.width(), b.height());
        }
        Point p = new Point();
        //noinspection deprecation
        wm.getDefaultDisplay().getSize(p);
        return p;
    }
}
