package org.offblink.spore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.function.IntBinaryOperator;

/**
 * 取帧取证判据契约（第九轮黑屏悬案）。
 *
 * 钉住三件事：
 * 1. 近黑判据与旧 isNearBlack 完全一致（`max &lt; 10`，边界 10 放行）——取帧轮询行为不许漂；
 * 2. 「可疑帧」判据 = 死黑采样点占比 ≥ 25%，**不是**整屏偏暗：夜间深色界面（无真 0 像素）
 *    不许被判可疑（否则真机上每次截屏都多取两次帧 + 弹一次取证窗 = 用户拍的「误杀」）；
 * 3. 4×4 网格能把黑斑定位到格（左半黑 / 单角黑块两种真实形态）。
 */
public class FrameQualityTest {

    private static final int W = 40;
    private static final int H = 40;
    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;

    private static FrameQuality of(IntBinaryOperator px) {
        return FrameQuality.compute(W, H, (x, y) -> px.applyAsInt(x, y));
    }

    private static FrameQuality flat(int argb) {
        return of((x, y) -> argb);
    }

    /** 全黑帧：近黑 + 可疑 + 网格全 0（FLAG_SECURE / 真零缓冲形态） */
    @Test
    public void allBlackIsNearBlackAndSuspect() {
        FrameQuality q = flat(BLACK);
        assertTrue(q.nearBlack());
        assertTrue(q.suspect());
        assertEquals(0, q.mean);
        assertEquals(0, q.max);
        assertEquals(100, q.deadPercent);
        for (int c : q.grid) {
            assertEquals(0, c);
        }
    }

    /** 亮帧干净：不进重合成、不写取证弹窗 */
    @Test
    public void brightFrameIsClean() {
        FrameQuality q = flat(WHITE);
        assertFalse(q.nearBlack());
        assertFalse(q.suspect());
        assertEquals(255, q.mean);
        assertEquals(255, q.max);
        assertEquals(0, q.deadPercent);
    }

    /** 近黑判据边界：max 恰为 10 = 放行（旧代码 `< 10`，改了就换了行为） */
    @Test
    public void maxChannelTenIsNotNearBlack() {
        FrameQuality q = flat(0xFF0A0A0A);
        assertEquals(10, q.max);
        assertFalse(q.nearBlack());
    }

    /**
     * 深灰噪声屏（无真 0 像素）：旧近黑判据放行，新可疑判据也**必须**放行——
     * 这是「夜间深色界面不误杀」的那条线（均匀暗 ≠ 大片死黑）。
     */
    @Test
    public void uniformDarkNoiseIsNotSuspect() {
        FrameQuality q = flat(0xFF1E1E1E);
        assertFalse(q.nearBlack());
        assertFalse(q.suspect());
        assertEquals(0, q.deadPercent);
        assertEquals(30, q.mean); // 0x1E=30，三通道等值 → BT.601 均值仍是 30
    }

    /** 左半黑 / 右半亮 = 「图层只合成了一半」的形态：可疑，且网格左两列 0、右两列 255 */
    @Test
    public void halfBlackFrameIsSuspectAndGridSplitsIt() {
        FrameQuality q = of((x, y) -> x < W / 2 ? BLACK : WHITE);
        assertTrue(q.suspect());
        assertEquals(50, q.deadPercent);
        assertEquals(127, q.mean); // 半黑半白：127.5 取整
        for (int r = 0; r < FrameQuality.GRID; r++) {
            assertEquals("row " + r + " col 0", 0, q.grid[r * FrameQuality.GRID]);
            assertEquals("row " + r + " col 1", 0, q.grid[r * FrameQuality.GRID + 1]);
            assertEquals("row " + r + " col 2", 255, q.grid[r * FrameQuality.GRID + 2]);
            assertEquals("row " + r + " col 3", 255, q.grid[r * FrameQuality.GRID + 3]);
        }
    }

    /** 单角黑块（右下 1/4 亮、其余黑）：黑斑落在哪一格必须看得出来 */
    @Test
    public void gridLocatesBlotchCell() {
        FrameQuality q = of((x, y) -> (x >= W / 2 && y >= H / 2) ? WHITE : BLACK);
        assertTrue(q.suspect());
        assertEquals(75, q.deadPercent);
        // 亮块 = 网格右下 2×2 格（下标 10/11/14/15），左上角格必然是 0
        assertEquals(0, q.grid[0]);
        assertEquals(255, q.grid[10]);
        assertEquals(255, q.grid[11]);
        assertEquals(255, q.grid[14]);
        assertEquals(255, q.grid[15]);
    }

    /** 一行统计自描述：均值/最大/死黑占比齐全，网格 16 格全带（文件名与日志都吃它） */
    @Test
    public void describeCarriesStatsAndGrid() {
        String s = flat(0xFF1E1E1E).describe("_");
        assertTrue(s.startsWith("mean30_max30_dead0pct_grid"));
        assertEquals("网格 16 格 = 15 个分隔符", 15,
                s.length() - s.replace("-", "").length());
    }
}
