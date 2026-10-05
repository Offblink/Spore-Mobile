package org.offblink.spore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.junit.Test;

import java.io.File;

/**
 * SporeLog 操作日志环形（设置页日志卡的数据源）。
 * 钳位：追加/尾读、异常行形状、超容截半保尾部、清空与空态。
 */
public class SporeLogTest {

    private static final class FakeContext extends ContextWrapper {
        final File files;

        FakeContext(File files) {
            super(null);
            this.files = files;
        }

        @Override
        public File getFilesDir() {
            return files;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            throw new UnsupportedOperationException("日志不碰 prefs");
        }
    }

    private static FakeContext fresh(String name) {
        File dir = new File("C:/tmp/scratch/spore-log-feature/ut", name);
        deleteRec(dir);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new FakeContext(dir);
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRec(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    @Test
    public void 追加与尾读按时间序() {
        FakeContext c = fresh("basic");
        SporeLog.i(c, "sync 第一条");
        SporeLog.i(c, "sync 第二条");
        String s = SporeLog.read(c);
        assertTrue(s.contains("第一条"));
        assertTrue(s.contains("第二条"));
        assertTrue(s.indexOf("第一条") < s.indexOf("第二条"));
        assertTrue("时间戳+级别前缀",
                s.matches("(?s).*\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} INFO .*"));
    }

    @Test
    public void 异常行带类名与首栈帧() {
        FakeContext c = fresh("err");
        try {
            throw new IllegalStateException("boom");
        } catch (IllegalStateException e) {
            SporeLog.e(c, "sync 上行失败", e);
        }
        String s = SporeLog.read(c);
        assertTrue(s.contains("ERROR"));
        assertTrue(s.contains("IllegalStateException: boom"));
        assertTrue(s.contains(" @ "));
    }

    @Test
    public void 超容截半保尾部() {
        FakeContext c = fresh("trim");
        String pad = "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
        for (int i = 0; i < 4000; i++) {
            SporeLog.i(c, "行" + i + " " + pad);
        }
        File f = new File(c.getFilesDir(), "spore.log");
        assertTrue("环形必须有上限，实测=" + f.length(), f.length() <= 64_000 + 200);
        String s = SporeLog.read(c);
        assertTrue("截半必须保住最新尾部", s.contains("行3999"));
        assertTrue("读取端截到 64KB", s.length() <= 64_000 + 10);
    }

    @Test
    public void 清空与空态不抛() {
        FakeContext c = fresh("clear");
        assertEquals("", SporeLog.read(c));
        SporeLog.i(c, "x");
        SporeLog.clear(c);
        assertEquals("", SporeLog.read(c));
        SporeLog.clear(c); // 重复清空不抛
    }
}
