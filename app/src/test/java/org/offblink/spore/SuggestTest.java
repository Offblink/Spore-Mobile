package org.offblink.spore;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.offblink.spore.overlay.Suggestor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 建议框选择器契约（镜像 handoff §9.3 判据）：
 * 单框、题块优先、块太小/无文本 = null（调用方退手动拖框）。
 * 坐标系 = 冻结帧像素；断言用不等式（pad/聚类阈值可调），不钉具体像素。
 */
public class SuggestTest {

    private static final int FRAME_W = 1080;
    private static final int FRAME_H = 2400;

    private static Suggestor.Line line(int l, int t, int r, int b, String text) {
        return new Suggestor.Line(l, t, r, b, text);
    }

    /** 三行题干+选项聚成一块，建议框覆盖整块（含外扩 pad） */
    @Test
    public void mergesQuestionBlockAndCoversIt() {
        List<Suggestor.Line> lines = Arrays.asList(
                line(80, 300, 1000, 360, "1、下列哪个说法是正确的？"),
                line(80, 380, 600, 430, "A. 说法甲"),
                line(80, 450, 600, 500, "B. 说法乙"));
        int[] box = Suggestor.suggest(lines, FRAME_W, FRAME_H);
        assertNotNull(box);
        assertTrue("left 不大于块左", box[0] <= 80);
        assertTrue("top 不大于块顶", box[1] <= 300);
        assertTrue("right 不小于块右", box[2] >= 1000);
        assertTrue("bottom 不小于块底", box[3] >= 500);
        // 不得撑满整帧（说明真按块聚类，而不是偷懒返回全屏）
        assertTrue(box[2] - box[0] < FRAME_W);
    }

    /** 标题（无问句信号）与题块分开时，选中题块而不是上方标题 */
    @Test
    public void prefersQuestionOverTitle() {
        List<Suggestor.Line> lines = Arrays.asList(
                line(80, 100, 400, 150, "语文随堂练习"),
                line(80, 300, 1000, 360, "2、下列计算正确的是（  ）？"),
                line(80, 380, 600, 430, "A. 2+2=5"));
        int[] box = Suggestor.suggest(lines, FRAME_W, FRAME_H);
        assertNotNull(box);
        assertTrue("题块在下，选中框应从题干附近开始", box[1] >= 250);
    }

    /** 空输入 → null（退手动框） */
    @Test
    public void noTextReturnsNull() {
        assertNull(Suggestor.suggest(new ArrayList<>(), FRAME_W, FRAME_H));
        assertNull(Suggestor.suggest(null, FRAME_W, FRAME_H));
    }

    /** 太小的块没资格当建议框：宽度不足帧宽 10% 一律不建议（哪怕带问号） */
    @Test
    public void tinyBlockRejected() {
        List<Suggestor.Line> lines = Arrays.asList(
                line(500, 1000, 530, 1040, "1+1=?"));
        assertNull(Suggestor.suggest(lines, FRAME_W, FRAME_H));
    }

    /** 楼顶/页脚两块同高文本不跨列粘连：问句块独立成块且胜出 */
    @Test
    public void columnsDoNotGlue() {
        List<Suggestor.Line> lines = Arrays.asList(
                line(60, 2000, 400, 2050, "答案在最后"),
                line(520, 2000, 1020, 2050, "第 3 题，哪一项是正确的？"));
        int[] box = Suggestor.suggest(lines, FRAME_W, FRAME_H);
        assertNotNull(box);
        assertTrue("应选中右列问句块", box[0] > 400);
    }

    /** 信号等价时取先（上方）出现的块——平手规则钉死，防止实现漂移 */
    @Test
    public void tiesPickTopmost() {
        List<Suggestor.Line> lines = Arrays.asList(
                line(80, 400, 1000, 500, "第一问，哪个对？"),
                line(80, 900, 1000, 1000, "第二问，哪个对？"));
        int[] box = Suggestor.suggest(lines, FRAME_W, FRAME_H);
        assertNotNull(box);
        assertTrue("平手取上方块", box[1] < 700);
    }
}
