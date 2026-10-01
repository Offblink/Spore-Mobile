package org.offblink.spore;

import android.graphics.Bitmap;

/**
 * 冻结帧质量取证（第九轮·黑屏悬案）。
 *
 * 背景：真机「ML 未识别 → 整屏预选 → 裁出来还是黑的」。判读 = 进框选的那张帧本身就黑/半黑
 * （图层未合成完 / 该机取帧通道异常），但旧代码只在**三轮全失败**时留样本，且近黑判据是
 * maxChannel&lt;10（噪声黑 10~60 全放行）→「过闸成功但帧脏」的现场一张样本都不留。
 * 本类把「这张帧到底长什么样」变成可落盘、可在 JVM 单测的数字：
 * mean（亮度均值）、max（最大通道）、deadPercent（死黑采样点占比）、4×4 逐格亮度均值
 * （黑斑在哪一目了然：左半黑 = 未合成层，规则黑带 = secure/overlay，全暗 = 另一回事）。
 *
 * 纯 int 像素数学（{@link #compute}），不碰 android.graphics —— Bitmap 只在 {@link #of}
 * 做适配，所以 test/ 下能直接喂合成像素断言。
 */
public final class FrameQuality {

    /** 近全黑判据（沿旧 isNearBlack）：全帧最大通道 &lt; 10 = 只拦真零缓冲/纯黑。 */
    public static final int NEAR_BLACK_MAX = 10;
    /** 死黑采样点：三通道全 &lt; 8（真 0 缓冲 / 未合成图层）。 */
    static final int DEAD_MAX = 8;
    /** 死黑占比 ≥ 25% → 判「可疑帧」（大片未合成 / 局部 FLAG_SECURE 黑）。 */
    static final int SUSPECT_DEAD_PERCENT = 25;
    /** 逐格网格边长：4×4 亮度均值。 */
    public static final int GRID = 4;
    /** 采样点上限（沿旧抽样密度 4000 点，整屏 2.6 M 像素约 1/650）。 */
    static final int SAMPLES = 4000;

    /** 像素源：Bitmap，或单测里的合成像素表。 */
    public interface PixelReader {
        int argb(int x, int y);
    }

    public final int width;
    public final int height;
    /** 0-255 亮度均值（BT.601：299/587/114，与 CropOverlayView.isDark 同族）。 */
    public final int mean;
    public final int max;
    /** 死黑采样点占比（百分数，四舍五入）。 */
    public final int deadPercent;
    /** GRID×GRID 逐格亮度均值，行优先。 */
    public final int[] grid;

    private FrameQuality(int width, int height, int mean, int max, int deadPercent, int[] grid) {
        this.width = width;
        this.height = height;
        this.mean = mean;
        this.max = max;
        this.deadPercent = deadPercent;
        this.grid = grid;
    }

    /** 只拦真全黑（旧 isNearBlack 语义，`max &lt; 10`）。 */
    public boolean nearBlack() {
        return max < NEAR_BLACK_MAX;
    }

    /** 大片死黑 = 合成未完成 / 局部 FLAG_SECURE —— 取帧硬化与取证弹窗的触发判据。 */
    public boolean suspect() {
        return deadPercent >= SUSPECT_DEAD_PERCENT;
    }

    /** 一行统计（文件名 / 日志 / 弹窗共用）；sep 由调用方给（文件名不能带空格）。 */
    public String describe(String sep) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("mean").append(mean).append(sep)
                .append("max").append(max).append(sep)
                .append("dead").append(deadPercent).append("pct").append(sep)
                .append("grid");
        for (int i = 0; i < grid.length; i++) {
            sb.append(i == 0 ? "" : "-").append(grid[i]);
        }
        return sb.toString();
    }

    /** 一行 4×4 网格（日志/弹窗里单列）。 */
    public String gridText() {
        StringBuilder sb = new StringBuilder(48);
        for (int r = 0; r < GRID; r++) {
            if (r > 0) {
                sb.append(" / ");
            }
            for (int c = 0; c < GRID; c++) {
                sb.append(c == 0 ? "" : " ").append(grid[r * GRID + c]);
            }
        }
        return sb.toString();
    }

    public static FrameQuality of(Bitmap bmp) {
        return compute(bmp.getWidth(), bmp.getHeight(), bmp::getPixel);
    }

    /**
     * 单趟采样：均匀跳过点（步长 = 面积/4000），同时算均值、最大通道、死黑占比与逐格均值。
     * 一张空尺寸帧（n==0）按「亮」处理（mean 255、max 0 → 仍判近黑，与旧 meanLuma 一致）。
     */
    public static FrameQuality compute(int w, int h, PixelReader px) {
        int cells = GRID * GRID;
        long[] cellSum = new long[cells];
        int[] cellN = new int[cells];
        long sum = 0;
        int max = 0;
        int dead = 0;
        int n = 0;
        long total = (long) w * h;
        if (total <= 0) {
            return new FrameQuality(w, h, 255, 0, 0, new int[cells]);
        }
        int step = (int) Math.max(1, total / SAMPLES);
        for (long i = 0; i < total; i += step) {
            int x = (int) (i % w);
            int y = (int) (i / w);
            int p = px.argb(x, y);
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            int m = Math.max(r, Math.max(g, b));
            int luma = (r * 299 + g * 587 + b * 114) / 1000;
            sum += luma;
            n++;
            if (m > max) {
                max = m;
            }
            if (m < DEAD_MAX) {
                dead++;
            }
            int cell = Math.min(GRID - 1, y * GRID / h) * GRID + Math.min(GRID - 1, x * GRID / w);
            cellSum[cell] += luma;
            cellN[cell]++;
        }
        int[] grid = new int[cells];
        for (int c = 0; c < cells; c++) {
            grid[c] = cellN[c] == 0 ? 0 : (int) (cellSum[c] / cellN[c]);
        }
        return new FrameQuality(w, h, (int) (sum / n), max, Math.round(100f * dead / n), grid);
    }
}
