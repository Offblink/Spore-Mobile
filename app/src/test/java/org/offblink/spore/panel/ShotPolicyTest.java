package org.offblink.spore.panel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.offblink.spore.agent.Session;

import java.io.File;
import java.io.IOException;

/**
 * 原生面板 user 行的题图判定：有文件就画、文件不在退回「题目截图」占位、
 * 没图就什么都不画（与 web panel.js 的 {@code m.hasImage && m.imagePath} 口径一致，
 * 多加一道「文件真在」——同步下载没落地时不能留一块空白）。
 */
public class ShotPolicyTest {

    private static Session.Msg msg(boolean hasImage, String path) {
        Session.Msg m = new Session.Msg();
        m.hasImage = hasImage;
        m.imagePath = path;
        return m;
    }

    @Test
    public void 有文件就画图不占位() throws IOException {
        File f = File.createTempFile("spore-shot", ".jpg");
        try {
            Session.Msg m = msg(true, f.getAbsolutePath());
            assertTrue(ShotPolicy.showShot(m));
            assertFalse(ShotPolicy.needsPlaceholder(m));
        } finally {
            f.delete();
        }
    }

    @Test
    public void 文件不在退回占位() {
        File gone = new File(
                System.getProperty("java.io.tmpdir"), "spore-no-such-shot.jpg");
        gone.delete(); // 兜底：上一轮残留会把这条断言变成假绿
        Session.Msg m = msg(true, gone.getAbsolutePath());
        assertFalse(ShotPolicy.showShot(m));
        assertTrue(ShotPolicy.needsPlaceholder(m));
    }

    @Test
    public void 路径为空或空串也算画不了图() {
        assertFalse(ShotPolicy.showShot(msg(true, null)));
        assertTrue(ShotPolicy.needsPlaceholder(msg(true, null)));
        assertFalse(ShotPolicy.showShot(msg(true, "")));
        assertTrue(ShotPolicy.needsPlaceholder(msg(true, "")));
    }

    @Test
    public void 没图就不画也不占位() {
        Session.Msg m = msg(false, "");
        m.text = "补充一句";
        assertFalse(ShotPolicy.showShot(m));
        assertFalse(ShotPolicy.needsPlaceholder(m));
    }

    @Test
    public void 空消息两个判定都为假() {
        assertFalse(ShotPolicy.showShot(null));
        assertFalse(ShotPolicy.needsPlaceholder(null));
    }
}
