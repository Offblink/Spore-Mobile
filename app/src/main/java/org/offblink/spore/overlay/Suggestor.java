package org.offblink.spore.overlay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 建议框选择器（handoff §9.3）：ML Kit 文本行 → 纵向聚类 → 选最像题的块 → 输出单框。
 * 纯 JVM 几何/文本逻辑（零 Android 依赖，单测钉行为）；坐标系 = 冻结帧像素。
 *
 * 判据存档（handoff §9.3）：单帧坐标回归不可靠、文本聚类无语义 → 只给「建议 + 一次确认」，
 * 误判成本从「搜错题」降为「拖一下」；题目区域是几百 px 大框，粗误差满足「目标 ≥ 2× 误差」。
 * 检测不到 / 块太小 → 返回 null，调用方退化为手动拖框（桌面原样）。
 */
public final class Suggestor {

    /** 一行识别结果（冻结帧像素坐标） */
    public static final class Line {
        public final int left, top, right, bottom;
        public final String text;

        public Line(int left, int top, int right, int bottom, String text) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.text = text == null ? "" : text;
        }
    }

    /** 题号开头：`1.` `2、` `3．` `4)` `5）`（前导空白可有可无） */
    private static final Pattern NUMBERING = Pattern.compile("^\\s*\\d+\\s*[.、．)）]");
    /** 题干常见词（命中 +10，一票多词也只加一次） */
    private static final String[] CUES = {"下列", "选择", "判断", "如图", "关于", "说法", "正确", "错误",
            "多少", "等于", "计算", "求解", "公式", "实验", "如右图"};

    private Suggestor() {
    }

    /**
     * @param lines   ML Kit 文本行（可为空）
     * @param frameW  冻结帧宽（像素），块太小 = 不建议
     * @param frameH  冻结帧高
     * @return 单框 {left, top, right, bottom}（帧坐标、含外扩 padding），无可用块 → null
     */
    public static int[] suggest(List<Line> lines, int frameW, int frameH) {
        if (lines == null || lines.isEmpty() || frameW <= 0 || frameH <= 0) {
            return null;
        }
        List<Line> usable = new ArrayList<>();
        for (Line l : lines) {
            if (!l.text.trim().isEmpty() && l.right > l.left && l.bottom > l.top) {
                usable.add(l);
            }
        }
        if (usable.isEmpty()) {
            return null;
        }
        usable.sort(Comparator.comparingInt((Line l) -> l.top).thenComparingInt(l -> l.left));

        List<Cluster> clusters = cluster(usable);

        int minW = (int) (0.10 * frameW);
        int minH = Math.max(36, (int) (0.015 * frameH));

        Cluster best = null;
        double bestScore = 0;
        for (Cluster c : clusters) {
            if (c.width() < minW || c.height() < minH) {
                continue;   // 小块没资格当建议框（手动拖才够准）
            }
            double s = c.score();
            if (best == null || s > bestScore) {
                best = c;
                bestScore = s;
            }
        }
        if (best == null) {
            return null;
        }
        return pad(best.left, best.top, best.right, best.bottom, frameW, frameH);
    }

    /** 纵向相邻（间隙 ≤ 1.6 行高）且水平重叠 ≥ 20%（窄者计）→ 同块；行序自上而下 */
    private static List<Cluster> cluster(List<Line> sorted) {
        List<Cluster> out = new ArrayList<>();
        for (Line line : sorted) {
            List<Cluster> hits = new ArrayList<>();
            for (Cluster c : out) {
                if (c.canJoin(line)) {
                    hits.add(c);
                }
            }
            if (hits.isEmpty()) {
                out.add(new Cluster(line));
                continue;
            }
            Cluster first = hits.get(0);
            first.add(line);
            for (int i = 1; i < hits.size(); i++) {
                first.absorb(hits.get(i));
                out.remove(hits.get(i));
            }
        }
        return out;
    }

    private static int[] pad(int l, int t, int r, int b, int frameW, int frameH) {
        int p = (int) Math.max(8, Math.min(24, 0.02 * Math.max(r - l, b - t)));
        return new int[]{
                Math.max(0, l - p),
                Math.max(0, t - p),
                Math.min(frameW, r + p),
                Math.min(frameH, b + p)};
    }

    private static final class Cluster {
        int left, top, right, bottom;
        int lastLineH;
        int chars;
        final StringBuilder sb = new StringBuilder();

        Cluster(Line l) {
            left = l.left;
            top = l.top;
            right = l.right;
            bottom = l.bottom;
            lastLineH = l.bottom - l.top;
            add(l);
        }

        void add(Line l) {
            left = Math.min(left, l.left);
            top = Math.min(top, l.top);
            right = Math.max(right, l.right);
            bottom = Math.max(bottom, l.bottom);
            lastLineH = l.bottom - l.top;
            sb.append(' ').append(l.text);
            chars += l.text.trim().length();
        }

        void absorb(Cluster c) {
            left = Math.min(left, c.left);
            top = Math.min(top, c.top);
            right = Math.max(right, c.right);
            bottom = Math.max(bottom, c.bottom);
            sb.append(' ').append(c.sb);
            chars += c.chars;
        }

        boolean canJoin(Line l) {
            int gap = l.top - bottom;
            if (gap > 1.6 * lastLineH) {
                return false;
            }
            int overlap = Math.min(right, l.right) - Math.max(left, l.left);
            int minW = Math.min(width(), l.right - l.left);
            return minW > 0 && overlap >= 0.2 * minW;
        }

        int width() {
            return right - left;
        }

        int height() {
            return bottom - top;
        }

        /** 题面信号 + 文本量；平手取先出现（上方）的块 */
        double score() {
            String text = sb.toString();
            double s = Math.min(chars, 150) / 10.0;
            if (text.indexOf('？') >= 0 || text.indexOf('?') >= 0) {
                s += 40;
            }
            if (NUMBERING.matcher(firstLine()).find()) {
                s += 25;
            }
            for (String cue : CUES) {
                if (text.contains(cue)) {
                    s += 10;
                    break;  // 多词也只加一次，防止关键词堆叠压过问号
                }
            }
            return s;
        }

        private String firstLine() {
            String raw = sb.toString().trim();
            int nl = raw.indexOf(' ');
            return nl >= 0 ? raw.substring(0, nl) : raw;
        }
    }
}
