package org.offblink.spore.sync;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

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
 */
public final class SyncTombstones {

    /** 会话（服务端 article）墓碑 */
    public static final String ARTICLE = "a";
    /** 科目（服务端 category）墓碑 */
    public static final String CATEGORY = "c";

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
        try {
            JSONArray items = loadArray(ctx);
            long ts = System.currentTimeMillis();
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o != null && kind.equals(o.optString("k", ""))
                        && id.equals(o.optString("id", ""))) {
                    o.put("ts", ts);
                    save(ctx, items);
                    return;
                }
            }
            JSONObject row = new JSONObject();
            row.put("k", kind);
            row.put("id", id);
            row.put("ts", ts);
            items.put(row);
            save(ctx, items);
        } catch (org.json.JSONException ignored) {
            // 账本是次要品：记不上就丢这一笔，不打断删除
        }
    }

    /** 全量读（坏文件/缺文件 → 空账） */
    public static synchronized List<Item> load(Context ctx) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray items = loadArray(ctx);
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String kind = o.optString("k", "");
                String id = o.optString("id", "");
                if (ARTICLE.equals(kind) || CATEGORY.equals(kind)) {
                    if (!id.isEmpty()) {
                        out.add(new Item(kind, id, o.optLong("ts", 0)));
                    }
                }
            }
        } catch (org.json.JSONException ignored) {
            // 坏账按空账走（同 subjects.json 坏文件口径）
        }
        return out;
    }

    /** 推送成功后按 (kind, id) 清账 */
    public static synchronized void remove(Context ctx, String kind, Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        try {
            JSONArray items = loadArray(ctx);
            JSONArray next = new JSONArray();
            Set<String> key = new LinkedHashSet<>(ids);
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                if (kind.equals(o.optString("k", "")) && key.contains(o.optString("id", ""))) {
                    continue; // 已推过的，清掉
                }
                next.put(o);
            }
            save(ctx, next);
        } catch (org.json.JSONException ignored) {
            // 同上：清账失败只影响回声，不影响正确性
        }
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
        try {
            JSONArray items = loadArray(ctx);
            boolean changed = false;
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null || !kind.equals(o.optString("k", ""))
                        || !id.equals(o.optString("id", ""))) {
                    continue;
                }
                if (o.optLong("ts", 0) <= target) {
                    o.put("ts", target + 1);
                    changed = true;
                }
            }
            if (changed) {
                save(ctx, items);
            }
        } catch (org.json.JSONException ignored) {
            // 账本次要品口径：抬失败只影响这一笔的收敛速度
        }
    }

    // ---------------------------------------------------------------- 文件

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "sync_tombstones.json");
    }

    private static JSONArray loadArray(Context ctx) throws org.json.JSONException {
        File f = file(ctx);
        if (!f.exists()) {
            return new JSONArray();
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
            JSONObject o = new JSONObject(new String(buf, 0, off, StandardCharsets.UTF_8));
            JSONArray arr = o.optJSONArray("items");
            return arr == null ? new JSONArray() : arr;
        } catch (IOException | org.json.JSONException e) {
            return new JSONArray(); // 坏文件按空账走
        }
    }

    /** tmp+rename 原子落盘（照抄 SubjectsStore.save） */
    private static void save(Context ctx, JSONArray items) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("items", items);
        File f = file(ctx);
        File tmp = new File(ctx.getFilesDir(), "sync_tombstones.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        }
    }
}
