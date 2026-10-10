package org.offblink.spore.panel;

import org.offblink.spore.agent.Session;

import java.io.File;

/**
 * user 行「题目截图 vs 占位文字」的判定（与 web 端 {@code panel.js rowHtml} 同口径）：
 * <ul>
 *   <li>有图标记 + 路径真能落到文件 → 画图；</li>
 *   <li>有图标记但文件不在（同步下载没落地 / 图被清走）→ 不能留一块空白，退回
 *       「题目截图」占位气泡；</li>
 *   <li>本来就没图 → 什么都不画，气泡照常显示补充文字。</li>
 * </ul>
 * 纯函数、零 Android 依赖，JVM 单测直测（{@code ShotPolicyTest}）。
 */
public final class ShotPolicy {

    private ShotPolicy() {
    }

    /** 该不该画题图：图标记 + 非空路径 + 文件真在 */
    public static boolean showShot(Session.Msg m) {
        return m != null && m.hasImage && m.imagePath != null && !m.imagePath.isEmpty()
                && new File(m.imagePath).isFile();
    }

    /** 画不了图却有图标记 → 气泡退回「题目截图」占位（不然是块空气） */
    public static boolean needsPlaceholder(Session.Msg m) {
        return m != null && m.hasImage && !showShot(m);
    }
}
