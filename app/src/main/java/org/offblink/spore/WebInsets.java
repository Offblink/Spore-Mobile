package org.offblink.spore;

import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.view.ViewCompat;

/**
 * 页面加载与窗口几何的时序（2026-10-11 用户实测「一点进设置页底栏先沉在很底部、再往上跳」）。
 *
 * <p>底栏是 {@code position:fixed}：它的位置既随**视口**变，也随自身的 bottom 偏移变。
 * 任何「加载之后才落定」的几何输入都会让它当场跳一下。这类输入目前有两个：
 * ① WebView 自己的尺寸（页面上屏早于第一次布局 → Chromium 先按旧尺寸算视口）；
 * ② 窗口 insets（迟到会让视口缩一截）。
 * 所以加载推迟到「布局完成 + insets 到手」之后，另留兜底延时，别把页面卡住。
 *
 * <p>页面里也把可能迟到的 CSS 输入删干净了（不用 {@code env(safe-area-inset-*)}、
 * 不写 {@code viewport-fit=cover}）——那两样正是会「先是 0、随后才有值」的东西。
 *
 * <p>{@link #loggingClient()} 把 WebView 尺寸与页面看到的视口/底栏矩形写进操作日志
 * （设置页日志卡可一键复制）：底栏跳这类时序问题靠它出真数据，别再靠猜。
 */
final class WebInsets {

    /** 兜底：insets/预绘制迟迟不来时照常加载（此时视口不会再变，早加载也无害）。 */
    private static final long FALLBACK_MS = 150;

    /** insets 静默这么久才算几何落定（等的是「系统栏那一下」彻底落完，不是第一次派发）。 */
    private static final long QUIET_MS = 90;

    /** 完全没收到 insets 时，等这么久就认为这台机器不会再有几何变化，照常加载。 */
    private static final long NO_INSET_MS = 120;

    /** 总上限：别让页面迟迟不上屏。 */
    private static final long MAX_WAIT_MS = 300;

    /** 页面落定后复采一次几何的延时。 */
    private static final long RELOG_MS = 800;

    private WebInsets() {
    }

    /**
     * 等窗口几何**落定**（布局完成 + insets 派发完并静默一拍）再 loadUrl，只加载一次。
     *
     * <p>关键是「静默」而不是「收到过」：系统栏 insets 可能分几次派发（状态栏、导航栏各一次，
     * 或先后落），只等第一次仍会让页面上屏后视口再缩一截 —— 那正是底栏跳一下的根因。
     */
    static void loadWhenReady(WebView web, String url) {
        final boolean[] loaded = {false};
        final boolean[] laid = {false};
        final long[] lastInset = {-1L};
        final long t0 = System.currentTimeMillis();
        final Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (loaded[0]) {
                    return;
                }
                long now = System.currentTimeMillis();
                boolean quiet = lastInset[0] > 0 && now - lastInset[0] >= QUIET_MS;
                boolean noInsets = lastInset[0] < 0 && now - t0 >= NO_INSET_MS;
                if (laid[0] && (quiet || noInsets || now - t0 >= MAX_WAIT_MS)) {
                    loaded[0] = true;
                    web.loadUrl(url);
                    return;
                }
                web.postDelayed(this, 30);
            }
        };
        // 第一次预绘制 = 布局已按最终尺寸算过（WebView 的 onSizeChanged 早于此）
        web.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                android.view.ViewTreeObserver vto = web.getViewTreeObserver();
                if (vto.isAlive()) {
                    vto.removeOnPreDrawListener(this);
                }
                laid[0] = true;
                return true;
            }
        });
        ViewCompat.setOnApplyWindowInsetsListener(web, (v, insets) -> {
            // 只借派发当时机，不消费、不改布局（返回原 insets 让系统照旧处理）
            lastInset[0] = System.currentTimeMillis();
            return insets;
        });
        web.postDelayed(poll, 30);
        web.postDelayed(poll, FALLBACK_MS);
        web.postDelayed(poll, MAX_WAIT_MS);
    }

    /** 带几何诊断的 WebViewClient：页面落定那一刻、+800ms 各记一笔，另加一条 1.2s 的几何时间线。 */
    static WebViewClient loggingClient() {
        return new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                logGeometry(view, "load");
                view.postDelayed(() -> logGeometry(view, "+" + RELOG_MS + "ms"), RELOG_MS);
                timeline(view);
            }
        };
    }

    /**
     * 入场后 1.2s 内每 50ms 采一次「视口高 + 底栏 top」，**只留变化点**（没变就一条都不多）。
     * 这是给「底栏入场跳一下」用的真数据：若时间线里 innerHeight 或 bar 变过，就是几何迟到，
     * 变的时机与幅度直接摆在日志里；若一条变化都没有，说明问题不在几何，得另找。
     */
    private static void timeline(WebView web) {
        web.evaluateJavascript("(function(){var a=[],t0=Date.now();"
                + "(function s(){var b=document.querySelector('.tabbar');"
                + "var r=b?b.getBoundingClientRect():null;"
                + "a.push([Date.now()-t0,Math.round(innerHeight),r?Math.round(r.top):-1]);"
                + "if(Date.now()-t0<1200){setTimeout(s,50);return;}"
                + "var out=[],last=null;for(var i=0;i<a.length;i++){"
                + "if(!last||a[i][1]!==last[1]||a[i][2]!==last[2]){out.push(a[i]);last=a[i];}}"
                + "window.__geom=out;})();})()", null);
        web.postDelayed(() -> web.evaluateJavascript("JSON.stringify(window.__geom||[])", v ->
                SporeLog.i(web.getContext(), "geom[timeline t,innerH,barTop] " + v)), RELOG_MS + 700);
    }

    /**
     * 把「WebView 尺寸」与「页面看到的视口/底栏矩形」写进操作日志。
     * 判读：两次采样若 innerHeight 或底栏 bottom 变了，就是几何迟到（视口缩了一截）；
     * 若两次完全一样，那底栏跳就不是几何问题，另找渲染层的原因。
     */
    private static void logGeometry(WebView web, String tag) {
        String js = "JSON.stringify({iw:Math.round(innerWidth),ih:Math.round(innerHeight),"
                + "vh:window.visualViewport?Math.round(visualViewport.height):-1,"
                + "dpr:devicePixelRatio,bar:(function(){var b=document.querySelector('.tabbar');"
                + "if(!b)return null;var r=b.getBoundingClientRect();"
                + "return [Math.round(r.top),Math.round(r.bottom)];})()})";
        web.evaluateJavascript(js, v -> SporeLog.i(web.getContext(),
                "geom[" + tag + "] view=" + web.getWidth() + "x" + web.getHeight() + " " + v));
    }
}
