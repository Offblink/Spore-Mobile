package org.offblink.spore;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃留证（第五轮：真机「长检索后悬浮窗崩」抓不到栈的教训）。
 * 两条证据线：
 * 1. 未捕获异常 / 主线程与桥线程的致命 Throwable → files/crash.log，下次开主页弹给用户截图；
 * 2. CaptureService 心跳 files/service.hb 记 create/destroy 序列——
 *    有 create 没 destroy = 进程被系统杀（LMK/FGS 回收），无 Java 栈也留指纹。
 * 红线：CrashLog 自身绝不许抛（失败静默）；文件不含 apiKey。
 */
public final class CrashLog {

    private CrashLog() {
    }

    private static File file(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), "crash.log");
    }

    private static File hbFile(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), "service.hb");
    }

    public static synchronized void write(Context ctx, Throwable t, String where) {
        try {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            Runtime rt = Runtime.getRuntime();
            String head = "\n==== "
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + " | " + where
                    + " | thread=" + Thread.currentThread().getName()
                    + " | mem used/max=" + ((rt.totalMemory() - rt.freeMemory()) >> 20)
                    + "/" + (rt.maxMemory() >> 20) + "MB\n";
            try (FileOutputStream out = new FileOutputStream(file(ctx), true)) {
                out.write((head + sw + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            // 留证失败不能再抛
        }
        Log.e("spore-crash", where, t);
    }

    /** 进程入口装一次（SporeApp）。 */
    public static void install(Context ctx) {
        Context app = ctx.getApplicationContext();
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, e) -> {
            write(app, e, "uncaught@" + th.getName());
            if (prev != null) {
                prev.uncaughtException(th, e);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
    }

    public static String readCrash(Context ctx) {
        return readAll(file(ctx), 64_000);
    }

    public static void clearCrash(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        file(ctx).delete();
    }

    public static void hb(Context ctx, String line) {
        try {
            File f = hbFile(ctx);
            if (f.length() > 32_768) {
                File old = new File(f.getParentFile(), "service.hb.old");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(old);
            }
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write((new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                        + " " + line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    public static String readHeartbeat(Context ctx) {
        return readAll(hbFile(ctx), 16_000);
    }

    /** 上次服务有 create 没 destroy = 进程非正常终止（被杀，非 Java 崩溃）。 */
    public static boolean diedAbnormally(Context ctx) {
        String hb = readHeartbeat(ctx);
        if (hb.isEmpty()) {
            return false;
        }
        String lastCreate = null;
        String lastDestroy = null;
        for (String line : hb.split("\n")) {
            if (line.contains(" create")) {
                lastCreate = line;
            }
            if (line.contains(" destroy")) {
                lastDestroy = line;
            }
        }
        return lastCreate != null && (lastDestroy == null || lastCreate.compareTo(lastDestroy) > 0);
    }

    private static String readAll(File f, int cap) {
        try {
            if (!f.exists() || f.length() == 0) {
                return "";
            }
            byte[] b = new byte[(int) Math.min(f.length(), cap)];
            try (FileInputStream in = new FileInputStream(f)) {
                //noinspection ResultOfMethodCallIgnored
                in.read(b);
            }
            return new String(b, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
