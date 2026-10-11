package org.offblink.spore;

import android.webkit.WebView;

import androidx.core.view.ViewCompat;

/**
 * 页面加载与窗口 insets 的时序（2026-10-11 用户实测的「一点进设置页底栏就卡」根因）。
 *
 * <p>底栏是 {@code position:fixed}：WebView 若在系统栏 insets 到位之前就把页面上屏，
 * Chromium 会先按**整屏**算一次视口（本机是 edge-to-edge，内容本来就铺到系统栏底下），
 * 等 insets 派发下来视口一缩，{@code fixed} 的底栏就「从很底部（还被系统栏压掉一截）
 * 立刻往上跳一下」。settings 页内容最长，跳幅最扎眼，所以先被发现。
 *
 * <p>修法不是补 padding（那会和系统已有的 inset 叠成双层），而是**把加载推后到 insets
 * 落定的那一刻**——视口从头就是最终尺寸，页面首帧即终局。万一窗口压根不派发 insets
 * （老设备/非 edge-to-edge），用兜底延时照常加载，不能把页面卡住。
 */
final class WebInsets {

    /** insets 迟迟不来时的兜底（ms）：此时视口不会变，早一点加载也无害。 */
    private static final long FALLBACK_MS = 80;

    private WebInsets() {
    }

    /** 等窗口 insets 派发后再 loadUrl（只加载一次）。 */
    static void loadAfterInsets(WebView web, String url) {
        final boolean[] loaded = {false};
        final Runnable load = () -> {
            if (!loaded[0]) {
                loaded[0] = true;
                web.loadUrl(url);
            }
        };
        ViewCompat.setOnApplyWindowInsetsListener(web, (v, insets) -> {
            // 只借这次派发当时机，不消费、不改布局（返回原 insets 让系统照旧处理）
            v.post(load);
            return insets;
        });
        web.postDelayed(load, FALLBACK_MS);
    }
}
