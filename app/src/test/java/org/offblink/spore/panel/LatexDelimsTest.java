package org.offblink.spore.panel;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * ext-latex 4.6.2 只认 {@code $$…$$}：归一化层要把契约的四类分隔符都换成它认得的形态，
 * 且字面美元/价格/跨行这些边界不许被误配。纯函数，JVM 直测。
 */
public class LatexDelimsTest {

    @Test
    public void 单美元行内公式换成双美元() {
        assertEquals("$$x^2$$", LatexDelims.normalizeForMarkwon("$x^2$"));
        assertEquals("行内 $$a^2+b^2=c^2$$ 公式",
                LatexDelims.normalizeForMarkwon("行内 $a^2+b^2=c^2$ 公式"));
    }

    @Test
    public void 反斜杠括号两种都换成双美元() {
        assertEquals("$$x$$", LatexDelims.normalizeForMarkwon("\\(x\\)"));
        assertEquals("$$E=mc^2$$", LatexDelims.normalizeForMarkwon("\\[E=mc^2\\]"));
        // 块级可跨行
        assertEquals("$$\na+b\n$$", LatexDelims.normalizeForMarkwon("\\[\na+b\n\\]"));
    }

    @Test
    public void 双美元原样不动() {
        assertEquals("$$a+b$$", LatexDelims.normalizeForMarkwon("$$a+b$$"));
        assertEquals("行内 $$a$$ 公式", LatexDelims.normalizeForMarkwon("行内 $$a$$ 公式"));
    }

    @Test
    public void 转义美元是字面且不参与配对() {
        assertEquals("转义 $5 是字面美元", LatexDelims.normalizeForMarkwon("转义 \\$5 是字面美元"));
        assertEquals("$5 与 $$x$$", LatexDelims.normalizeForMarkwon("\\$5 与 $x$"));
    }

    @Test
    public void 价格不被当成公式_契约第二条() {
        assertEquals("单价 $5，$8 元", LatexDelims.normalizeForMarkwon("单价 $5，$8 元"));
        assertEquals("单价 $5，公式 $$x$$ 元",
                LatexDelims.normalizeForMarkwon("单价 $5，公式 $x$ 元"));
    }

    @Test
    public void 行内不跨行且闭合侧前不许空白() {
        assertEquals("$a\nb$", LatexDelims.normalizeForMarkwon("$a\nb$"));
        assertEquals("$a b $", LatexDelims.normalizeForMarkwon("$a b $"));
    }

    @Test
    public void 无公式文本逐字节不变() {
        assertEquals("普通回答，没有公式 1+1=2。",
                LatexDelims.normalizeForMarkwon("普通回答，没有公式 1+1=2。"));
        assertEquals("", LatexDelims.normalizeForMarkwon(""));
        assertEquals("", LatexDelims.normalizeForMarkwon(null));
    }
}
