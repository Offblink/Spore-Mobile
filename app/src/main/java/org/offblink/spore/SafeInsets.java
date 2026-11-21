package org.offblink.spore;

import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 安全区（系统栏 + 挖孔）落到原生几何上。
 *
 * <p>为什么需要：targetSdk 37 + Android 15 起系统**强制 edge-to-edge**，{@code statusBarColor}
 * 不再生效，Activity 内容直接画到状态栏/导航栏底下——网页顶栏的 12px padding 就从屏幕顶端
 * 开始算，看着「贴边」。这里统一在原生侧把系统栏高度垫出来。
 *
 * <p>为什么可以无条件调用：非 edge-to-edge 的机型/系统版本上，decor 已把 systemBars 消费掉，
 * 这里读到的就是 0，不会二次垫。
 *
 * <p>为什么 WebView 垫外边距而不是 padding：WebView 的 padding 不参与页面视口计算，
 * {@code position: fixed} 的底栏会落进 padding 里（贴到导航栏后面）；外边距是实打实缩小
 * 视口，页面 CSS 一行不用改。露出来的那一圈是父容器（窗口底），颜色与页面 --bg 同色。
 */
final class SafeInsets {

    private SafeInsets() {
    }

    /** 系统栏 + 挖孔：只算 systemBars 在药丸/挖孔屏上顶部会差一截。 */
    static Insets system(WindowInsetsCompat insets) {
        return insets.getInsets(WindowInsetsCompat.Type.systemBars()
                | WindowInsetsCompat.Type.displayCutout());
    }

    /** 整页视图（WebView / 页面根）整体避开系统栏。 */
    static void margin(View v) {
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, insets) -> {
            applyMargin(view, insets);
            return insets;
        });
        ViewCompat.requestApplyInsets(v);
    }

    /** {@link WebInsets} 自己装了监听（要借派发时机），那条里调这个共用实现。 */
    static void applyMargin(View view, WindowInsetsCompat insets) {
        Insets sys = system(insets);
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
        if (m.topMargin == sys.top && m.bottomMargin == sys.bottom) {
            return;
        }
        m.topMargin = sys.top;
        m.bottomMargin = sys.bottom;
        view.setLayoutParams(m);
    }

    /**
     * 相机页的 chrome 条（顶栏 / 底部控件）避让系统栏：在布局自带的 padding 上叠加 insets，
     * 取景预览仍然全屏，不裁画面。
     */
    static void pad(View v, boolean top, boolean bottom) {
        final int baseTop = v.getPaddingTop();
        final int baseBottom = v.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, insets) -> {
            Insets sys = system(insets);
            int t = top ? baseTop + sys.top : view.getPaddingTop();
            int b = bottom ? baseBottom + sys.bottom : view.getPaddingBottom();
            if (t != view.getPaddingTop() || b != view.getPaddingBottom()) {
                view.setPadding(view.getPaddingLeft(), t, view.getPaddingRight(), b);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(v);
    }

    /** 贴底按钮：在布局自带的 bottomMargin 上叠加导航栏高度（padding 会把按钮撑高，不是避让）。 */
    static void marginBottom(View v) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        final int base = ((ViewGroup.MarginLayoutParams) lp).bottomMargin;
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, insets) -> {
            Insets sys = system(insets);
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            int want = base + sys.bottom;
            if (m.bottomMargin != want) {
                m.bottomMargin = want;
                view.setLayoutParams(m);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(v);
    }

    /**
     * 相机页系统栏图标固定浅色：这两页底色是黑的，浅色主题下默认的深色图标会看不见
     * （取景从状态栏底下穿过去，图标就压在全黑的画面上）。
     */
    static void lightSystemBars(android.app.Activity a) {
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(
                a.getWindow(), a.getWindow().getDecorView());
        c.setAppearanceLightStatusBars(false);
        c.setAppearanceLightNavigationBars(false);
    }
}
