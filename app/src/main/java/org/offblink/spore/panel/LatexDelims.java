package org.offblink.spore.panel;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 原生面板（Markwon + ext-latex）的分隔符归一化。
 *
 * <p>为什么需要它：ext-latex 4.6.2 实测**只认 {@code $$…$$}**（inline processor 的正则是
 * {@code (\${2})([\s\S]+?)\1}），不认单 {@code $}，也不认 {@code \(…\)} / {@code \[…\]}；
 * 而产品契约（与 WebView 面 md.js、GUI 面 latex_render.py 一致）是四类分隔符全支持。
 * 这里把契约口径归一到 ext-latex 认识的形式：
 * <ul>
 *   <li>{@code $$…$$} 原样（块级/行内由 Markwon 自己判）；</li>
 *   <li>行内 {@code \(…\)} 与单 {@code $…$} → {@code $$…$$}（同一行内仍是行内）；</li>
 *   <li>块级 {@code \[…\]} → {@code $$…$$}（配合插件 blocksLegacy）；</li>
 *   <li>{@code \$} 是字面美元、不参与配对（先摘成哨兵，最后还原）。</li>
 * </ul>
 *
 * <p>单 {@code $} 的配对沿用契约四条：开 {@code $} 后非空白、内容不含 {@code $} 与换行、
 * 闭 {@code $} 前非空白、闭 {@code $} 后不是 ASCII 数字（治「单价 $5，$8 元」）。
 * 纯函数、无 Android 依赖，单测直测。
 */
public final class LatexDelims {

    private LatexDelims() {
    }

    /** 字面美元（{@code \$}）的临时哨兵：正文里不会出现的控制字符 + 标记 */
    private static final String SENTINEL = "\u0000D";

    /** 单 {@code $…$}：开侧后非空白、内容无 $ 无换行、闭侧前非空白、闭侧后不是数字 */
    private static final Pattern INLINE_DOLLAR = Pattern.compile(
            "(?<!\\$)\\$(?!\\$)(?=\\S)([^\\n$]+?)(?<=\\S)\\$(?!\\$)(?![0-9])");

    /** 把契约四类分隔符归一到 Markwon ext-latex 认得的 {@code $$…$$} 形态 */
    public static String normalizeForMarkwon(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        // 1) \$ 先摘走：它是字面美元，不参与任何配对（后文的 $ 也配不到它头上）
        String s = text.replace("\\$", SENTINEL);
        // 2) 块级 \[…\] 与行内 \(…\) → $$…$$
        s = s.replace("\\[", "$$").replace("\\]", "$$");
        s = s.replace("\\(", "$$").replace("\\)", "$$");
        // 3) 单 $…$ → $$…$$（lookaround 保证已经成对的 $$…$$ 不被二次改写）
        //    注意：替换串里 $ 是模板元字符，必须 quoteReplacement 落地
        Matcher m = INLINE_DOLLAR.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("$$" + m.group(1) + "$$"));
        }
        m.appendTail(sb);
        s = sb.toString();
        // 4) 还原字面美元
        return s.replace(SENTINEL, "$");
    }
}
