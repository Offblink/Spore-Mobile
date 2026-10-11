package org.offblink.spore;

import android.webkit.WebView;

import androidx.core.view.ViewCompat;

/**
 * 页面加载与窗口几何的时序：等窗口几何**落定**（布局完成 + insets 派发完并静默一拍）再 loadUrl。
 *
 * <p>为什么值得等：WebView 若在 insets 到位前就把页面上屏，Chromium 会先按「整屏」算一次视口，
 * insets 一到视口缩水，页面里 position:fixed / 100vh 之类的东西会当场挪一下。底部玻璃条
 * （glassbar.css，2026-10-11 加回）正是 position: fixed，所以这道闸继续服役。
 *
 * <p>「静默」而不是「收到过」：系统栏 insets 可能分几次派发，只等第一次仍会晚一步。
 */
final class WebInsets {

    /** insets 静默这么久才算几何落定。 */
    private static final long QUIET_MS = 90;

    /** 完全没收到 insets 时，等这么久就认为这台机器不会再有几何变化，照常加载。 */
    private static final long NO_INSET_MS = 120;

    /** 总上限：别让页面迟迟不上屏。 */
    private static final long MAX_WAIT_MS = 300;

    private WebInsets() {
    }

    /** 等几何落定再 loadUrl（只加载一次）。 */
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
        web.postDelayed(poll, MAX_WAIT_MS);
    }
}
