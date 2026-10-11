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

    /** 页面落定后复采一次几何的延时。 */
    private static final long RELOG_MS = 800;

    private WebInsets() {
    }

    /** 等「布局完成 + insets 到手」再 loadUrl（只加载一次）。 */
    static void loadWhenReady(WebView web, String url) {
        final boolean[] laid = {false};
        final boolean[] sized = {false};
        final boolean[] loaded = {false};
        final Runnable tryLoad = () -> {
            if (laid[0] && sized[0] && !loaded[0]) {
                loaded[0] = true;
                web.loadUrl(url);
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
                tryLoad.run();
                return true;
            }
        });
        ViewCompat.setOnApplyWindowInsetsListener(web, (v, insets) -> {
            // 只借这次派发当时机，不消费、不改布局（返回原 insets 让系统照旧处理）
            sized[0] = true;
            tryLoad.run();
            return insets;
        });
        web.postDelayed(() -> {
            laid[0] = true;
            sized[0] = true;
            tryLoad.run();
        }, FALLBACK_MS);
    }

    /** 带几何诊断的 WebViewClient：页面落定那一刻与 800ms 后各记一笔。 */
    static WebViewClient loggingClient() {
        return new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                logGeometry(view, "load");
                view.postDelayed(() -> logGeometry(view, "+" + RELOG_MS + "ms"), RELOG_MS);
            }
        };
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
