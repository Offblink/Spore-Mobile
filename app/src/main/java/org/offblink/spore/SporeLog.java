package org.offblink.spore;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 操作日志——三端对齐的最后一端（MV3 主页有「日志」卡、GUI 有日志页，这里是设置页日志卡）。
 * files/spore.log 环形：追加写，超 64KB 从行界截前半保尾部。用来回答
 * 「上次同步到底卡在哪一步」（教训：服务端只回一句「系统异常」，客户端两眼一抹黑）。
 * 红线：调用方绝不许写入 token/apiKey（同 CrashLog 口径）；本类自身绝不许抛。
 */
public final class SporeLog {

    private static final long CAP = 64_000;

    private SporeLog() {
    }

    private static File file(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), "spore.log");
    }

    public static void i(Context ctx, String msg) {
        append(ctx, "INFO ", msg, null);
    }

    public static void e(Context ctx, String msg, Throwable t) {
        append(ctx, "ERROR", msg, t);
    }

    private static void append(Context ctx, String level, String msg, Throwable t) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date()));
            sb.append(' ').append(level).append(' ').append(msg == null ? "" : msg);
            if (t != null) {
                sb.append(" | ").append(t.getClass().getSimpleName())
                        .append(": ").append(String.valueOf(t.getMessage()));
                StackTraceElement[] st = t.getStackTrace();
                if (st != null && st.length > 0) {
                    sb.append(" @ ").append(st[0]);
                }
            }
            sb.append('\n');
            File f = file(ctx);
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (f.length() > CAP) {
                trim(f);
            }
        } catch (Throwable ignored) {
            // 日志是旁路：写失败绝不打断业务（同 CrashLog）
        }
    }

    /** 超容时从行界截掉前半，保住最近的尾部 */
    private static void trim(File f) throws IOException {
        byte[] all = readBytes(f);
        if (all.length <= CAP) {
            return;
        }
        int cut = all.length / 2;
        while (cut < all.length && all[cut] != '\n') {
            cut++;
        }
        if (cut + 1 >= all.length) {
            return; // 单行就超容：不动，读取端自己截
        }
        byte[] keep = new byte[all.length - cut - 1];
        System.arraycopy(all, cut + 1, keep, 0, keep.length);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(keep);
        }
    }

    /** 尾部（≤64KB，从行界起读，不切半个 UTF-8）；无文件/失败 → 空串 */
    public static String read(Context ctx) {
        try {
            File f = file(ctx);
            if (!f.exists()) {
                return "";
            }
            byte[] all = readBytes(f);
            int start = 0;
            if (all.length > CAP) {
                start = (int) (all.length - CAP);
                while (start < all.length && all[start] != '\n') {
                    start++;
                }
                start = start < all.length ? start + 1 : all.length;
            }
            byte[] tail = new byte[all.length - start];
            System.arraycopy(all, start, tail, 0, tail.length);
            return new String(tail, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static void clear(Context ctx) {
        try {
            //noinspection ResultOfMethodCallIgnored
            file(ctx).delete();
        } catch (Throwable ignored) {
        }
    }

    private static byte[] readBytes(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            return b;
        }
    }
}
