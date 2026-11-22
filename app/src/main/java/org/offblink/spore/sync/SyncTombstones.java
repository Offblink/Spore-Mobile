package org.offblink.spore.sync;

import android.content.Context;

import androidx.annotation.Keep;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 删除墓碑账本：{@code filesDir/sync_tombstones.json}，一行 {k, id, ts}。
 * 会话/科目在本机被删掉后文件就没了，光靠「遍历本地行上行」永远传播不出删除——
 * 所以 {@code SessionStore.delete} / {@code SubjectsStore.remove} 在真删成功前先记一笔，
 * 同步上行时补 {@code deleted=1}（server 侧 LWW 判定，见 SyncEngine.push）。
 *
 * <p>推过（HTTP 层成功即算，服务端 LWW 裁决为最终）就整笔清掉；未配对期间的删除
 * 照记不误，配对后第一轮一并传播。ts = 本地删除时刻（上行就是用它当 updated）。
 * 写失败只丢这一笔记账（与 SessionStore「记录是次要品」同口径），绝不打断删除动作。
 *
 * <p>序列化走 Gson DTO（{@link Ledger}/{@link Row}）：这是本应用私有格式、字段只有三个、
 * 没有跨端透传需求 —— 与 sessions 存档同一套做法。subjects.json 不走 Gson：那份要
 * 「字段照单全收」（GUI 多级同步下来的额外列不丢），DTO 绑定会静默丢键。
 */
public final class SyncTombstones {

    /** 会话（服务端 article）墓碑 */
    public static final String ARTICLE = "a";
    /** 科目（服务端 category）墓碑 */
    public static final String CATEGORY = "c";

    /**
     * 宽容解析（与 org.json 同口径：坏文件/手工编辑不因严格性差异被丢弃）；
     * 账本是纯 ASCII 结构，HTML 转义无所谓，但保持与 SessionJson 同一套配置减少意外。
     */
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setStrictness(Strictness.LENIENT)
            .create();

    private SyncTombstones() {
    }

    /** 一笔墓碑 */
    public static final class Item {
        public final String kind;
        public final String id;
        public final long ts;

        Item(String kind, String id, long ts) {
            this.kind = kind;
            this.id = id;
            this.ts = ts;
        }
    }

    /** 记一笔（同 k+id 重复删 → 覆盖成最新时刻：重建后又删，LWW 要用最后那次） */
    public static synchronized void add(Context ctx, String kind, String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        Ledger ledger = loadLedger(ctx);
        long ts = System.currentTimeMillis();
        for (Row r : ledger.items) {
            if (r != null && kind.equals(r.k) && id.equals(r.id)) {
                r.ts = ts;
                save(ctx, ledger);
                return;
            }
        }
        Row row = new Row();
        row.k = kind;
        row.id = id;
        row.ts = ts;
        ledger.items.add(row);
        save(ctx, ledger);
    }

    /** 全量读（坏文件/缺文件 → 空账） */
    public static synchronized List<Item> load(Context ctx) {
        List<Item> out = new ArrayList<>();
        for (Row r : loadLedger(ctx).items) {
            if (r == null || r.k == null || r.id == null || r.id.isEmpty()) {
                continue;
            }
            if (ARTICLE.equals(r.k) || CATEGORY.equals(r.k)) {
                out.add(new Item(r.k, r.id, r.ts));
            }
        }
        return out;
    }

    /** 推送成功后按 (kind, id) 清账 */
    public static synchronized void remove(Context ctx, String kind, Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Ledger ledger = loadLedger(ctx);
        Set<String> key = new LinkedHashSet<>(ids);
        List<Row> next = new ArrayList<>();
        for (Row r : ledger.items) {
            if (r == null) {
                continue;
            }
            if (kind.equals(r.k) && key.contains(r.id)) {
                continue; // 已推过的，清掉
            }
            next.add(r);
        }
        ledger.items = next;
        save(ctx, ledger);
    }

    /** 账上有没有这一笔（下行套用前查：在账墓碑必须压住活行，不许复活） */
    public static synchronized boolean has(Context ctx, String kind, String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        for (Item i : load(ctx)) {
            if (kind.equals(i.kind) && id.equals(i.id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把账上这笔 ts 抬到 target 之上：下行发现「本地已删但服务端行更新」时，
     * 抬账让下一轮 push 的 updated 压过服务端 → LWW 才裁得出删除赢（两轮收敛）。
     */
    public static synchronized void raise(Context ctx, String kind, String id, long target) {
        if (id == null || id.isEmpty()) {
            return;
        }
        Ledger ledger = loadLedger(ctx);
        boolean changed = false;
        for (Row r : ledger.items) {
            if (r == null || !kind.equals(r.k) || !id.equals(r.id)) {
                continue;
            }
            if (r.ts <= target) {
                r.ts = target + 1;
                changed = true;
            }
        }
        if (changed) {
            save(ctx, ledger);
        }
    }

    // ---------------------------------------------------------------- 文件

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "sync_tombstones.json");
    }

    /** 读账本；坏文件/缺文件一律按空账走（次要品口径），永不返回 null */
    private static Ledger loadLedger(Context ctx) {
        File f = file(ctx);
        if (!f.exists()) {
            return new Ledger();
        }
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            Ledger l = GSON.fromJson(new String(buf, 0, off, StandardCharsets.UTF_8), Ledger.class);
            if (l == null || l.items == null) {
                return new Ledger();
            }
            return l;
        } catch (IOException | RuntimeException e) {
            return new Ledger(); // 坏文件按空账走
        }
    }

    /** tmp+rename 原子落盘（照抄 SubjectsStore.save）；写失败只丢这一笔，不抛 */
    private static void save(Context ctx, Ledger ledger) {
        File f = file(ctx);
        File tmp = new File(ctx.getFilesDir(), "sync_tombstones.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(GSON.toJson(ledger).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return;
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        }
    }

    // ---------------------------------------------------------------- DTO（落盘形状）

    /**
     * 账本整体：{@code {"items":[{k,id,ts}…]}}（与旧 org.json 输出同形）。
     * {@code items} 恒非 null，调用方不必判空。
     */
    @Keep
    static final class Ledger {
        List<Row> items = new ArrayList<>();
    }

    /** 一笔墓碑；字段名 = JSON 键名，单字母是 kit 既有格式（别改名） */
    @Keep
    static final class Row {
        String k;
        String id;
        long ts;
    }
}
