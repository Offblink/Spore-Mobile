package org.offblink.spore;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * 取帧取证落盘（第九轮·黑屏悬案）——把「进框选的那张帧」变成开发者拿得到的证据。
 *
 * 三个产物：
 * 1. {@code files/diag/frame_<ts>_meanN_maxM_deadPpct_grid...jpg}：**每次取帧都留一张**
 *    （旧代码只在三轮全失败时留，过闸但帧脏的现场反而没样本）；
 * 2. {@code files/diag/pending.txt}：只有**可疑帧**（死黑斑块 ≥25%）才写 → 主页开屏弹给用户，
 *    一键存进相册（Pictures/Spore）。真机不能 adb 取文件，这是现场帧唯一的回传通道；
 * 3. logcat `spore-frame` 一行统计（含重复帧计数 dup：静态屏下 VD 是否不再产新帧的判据）。
 *
 * 红线：取证绝不许影响截屏主链路（全 try/catch 吞），文件不含任何密钥。
 */
public final class FrameDiag {

    private static final String TAG = "spore-frame";
    /** diag 目录里最多留几张帧样本（真机存储也不该被取证吃掉）。 */
    private static final int KEEP = 8;
    private static final String PENDING = "pending.txt";

    private FrameDiag() {
    }

    public static File dir(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), "diag");
    }

    /**
     * 落一张帧样本；可疑帧额外写 pending（主页开屏弹窗 + 一键存相册）。
     *
     * @param dup 与首抓像素完全相同的重抓次数（>0 ⇒ 重建虚拟显示也没拿到新帧 = 合成没动）
     */
    public static void save(Context ctx, Bitmap frame, FrameQuality q, int dup, String tag) {
        String name = tag + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + "_" + q.describe("_") + ".jpg";
        File f = new File(dir(ctx), name);
        try {
            //noinspection ResultOfMethodCallIgnored
            dir(ctx).mkdirs();
            try (FileOutputStream fos = new FileOutputStream(f)) {
                frame.compress(Bitmap.CompressFormat.JPEG, CaptureService.JPEG_QUALITY, fos);
            }
        } catch (Throwable t) {
            return; // 落盘失败只丢样本，主链路照走
        }
        Log.w(TAG, name + " dup" + dup);
        if (q.suspect()) {
            writePending(ctx, f, q, dup);
        }
        prune(ctx);
    }

    /** 待用户回传的现场帧（绝对路径 + 统计 + 重复帧计数）；没有 = 空串。 */
    public static String readPending(Context ctx) {
        try {
            File f = new File(dir(ctx), PENDING);
            if (!f.isFile() || f.length() == 0) {
                return "";
            }
            byte[] b = new byte[(int) Math.min(f.length(), 4096)];
            try (FileInputStream in = new FileInputStream(f)) {
                //noinspection ResultOfMethodCallIgnored
                in.read(b);
            }
            return new String(b, StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    public static void clearPending(Context ctx) {
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(dir(ctx), PENDING).delete();
        } catch (Throwable ignored) {
            // 清标记失败不影响任何链路
        }
    }

    private static void writePending(Context ctx, File frame, FrameQuality q, int dup) {
        try {
            String body = frame.getAbsolutePath() + "\n" + q.describe(" ") + "\n"
                    + "grid " + q.gridText() + "\ndup" + dup + "\n";
            try (FileOutputStream out = new FileOutputStream(new File(dir(ctx), PENDING))) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            // 没有弹窗提示只是少一次取证机会，主链路不受影响
        }
    }

    /** 只留最近 KEEP 张样本（frame_/black_ 都算）。 */
    private static void prune(Context ctx) {
        try {
            File[] all = dir(ctx).listFiles((d, n) -> n.endsWith(".jpg"));
            if (all == null || all.length <= KEEP) {
                return;
            }
            Arrays.sort(all, Comparator.comparingLong(File::lastModified).reversed());
            for (int i = KEEP; i < all.length; i++) {
                //noinspection ResultOfMethodCallIgnored
                all[i].delete();
            }
        } catch (Throwable ignored) {
            // 清理失败只占点空间
        }
    }
}
