package org.offblink.spore.agent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 科目表持久化（kit design/03 §三）：{@code filesDir/subjects.json}，整表一个 JSON 数组，
 * 写盘 tmp+rename 原子化（照抄 {@link SessionStore#save}）。
 * 字段照 kit：{@code id/name/parentId/sortOrder/status/created/updated}——v1 手机端恒平铺
 * （parentId=null、status=1，kit 明确「手机 v1 平铺顶层，但字段保留」，GUI 多级同步下来不丢形）。
 * id 风格 = {@link Session#newId()} 时间戳，两端同一命名空间（handoff §1 禁自增）。
 * 单写者：只有记录页 JavaBridge 线程改这张表（引擎不碰 subjects.json）。
 */
public final class SubjectsStore {

    private SubjectsStore() {
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "subjects.json");
    }

    // ---------------------------------------------------------------- 读

    /** 全量列表（数组序 = 创建序 = 渲染序）；坏文件/缺文件 → 空表 */
    public static JSONArray metaJson(Context ctx) {
        JSONArray out = new JSONArray();
        JSONArray arr = load(ctx);
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                if (o.has("id") && o.has("name")) {
                    out.put(o);
                }
            } catch (org.json.JSONException ignored) {
                // 单行坏 → 跳过该行，其余照常（同 SessionStore 坏文件跳过口径）
            }
        }
        return out;
    }

    public static boolean exists(Context ctx, String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        JSONArray arr = load(ctx);
        for (int i = 0; i < arr.length(); i++) {
            if (id.equals(arr.optJSONObject(i) == null ? null : arr.optJSONObject(i).optString("id", ""))) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 写

    /**
     * 新建科目：名字空 → null（不建）；同毫秒 id 防撞（人工点击到不了，防程序化双写撞号）。
     * 返回新建的整行，供 bridge 直接回给 web。
     */
    public static JSONObject create(Context ctx, String name) {
        String clean = cleanName(name);
        if (clean == null) {
            return null;
        }
        JSONArray arr = load(ctx);
        String id = Session.newId();
        for (int n = 1; existsIn(arr, id); n++) {
            id = Session.newId() + "-" + n;
        }
        long now = System.currentTimeMillis();
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", clean);
            o.put("parentId", JSONObject.NULL);
            o.put("sortOrder", arr.length());
            o.put("status", 1);
            o.put("created", now);
            o.put("updated", now);
            arr.put(o);
        } catch (org.json.JSONException e) {
            return null;
        }
        return save(ctx, arr) ? o : null;
    }

    /** 重命名（只改名，不动成员，Spore 远端 043815e 同语义）；名字空/科目不存在 → false */
    public static boolean rename(Context ctx, String id, String name) {
        String clean = cleanName(name);
        if (clean == null || id == null || id.isEmpty()) {
            return false;
        }
        JSONArray arr = load(ctx);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id.equals(o.optString("id", ""))) {
                try {
                    o.put("name", clean);
                    o.put("updated", System.currentTimeMillis());
                } catch (org.json.JSONException e) {
                    return false;
                }
                return save(ctx, arr);
            }
        }
        return false;
    }

    /**
     * 只摘科目行；**成员会话的 subjectId 清扫不在这**——那要动引擎内存态，
     * 统一走 {@code CaptureService.deleteSubject}（先摘行 → 引擎清引用 → 磁盘扫尾）。
     * 真摘成功 → 记科目墓碑（同步上行 deleted=1，kit design/03 §二）。
     */
    public static boolean remove(Context ctx, String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        JSONArray arr = load(ctx);
        JSONArray next = new JSONArray();
        boolean hit = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id.equals(o.optString("id", ""))) {
                hit = true;
                continue;
            }
            next.put(o);
        }
        boolean ok = hit && save(ctx, next);
        if (ok) {
            org.offblink.spore.sync.SyncTombstones.add(
                    ctx, org.offblink.spore.sync.SyncTombstones.CATEGORY, id);
        }
        return ok;
    }

    /**
     * 同步下行套用一行科目（服务端权威，SyncEngine.pull 调）。规则：
     * <ul>
     *   <li>本地无行：deleted=1 → 无事；否则追加到尾（数组序 = 创建序，追加即置底）；</li>
     *   <li>本地有行：服务端 updated ≤ 本地 updated → 跳过（本地新，等上行 LWW 裁决）；
     *       deleted=1 → 摘行；否则整行原位替换（保数组位置）。</li>
     * </ul>
     * 字段照单全收（parentId/status 多级形不丢，kit design/03 §三）；**不走 create/rename**——
     * 它们会盖 now 时间戳，而下行行的 updated 就是同步游标，动了会打断增量判定。
     * 成员会话的引用清扫不在这：类别墓碑由 SyncEngine 走 CaptureService.deleteSubject 的级联。
     *
     * @return 本地表是否被改动
     */
    public static boolean applyServerRow(Context ctx, JSONObject row) {
        if (row == null) {
            return false;
        }
        String id = row.optString("id", "");
        if (id.isEmpty()) {
            return false;
        }
        boolean deleted = row.optInt("deleted", 0) == 1;
        JSONArray arr = load(ctx);
        int idx = -1;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id.equals(o.optString("id", ""))) {
                idx = i;
                break;
            }
        }
        try {
            if (idx < 0) {
                if (deleted) {
                    return false; // 本就没有，墓碑无需落地
                }
                arr.put(copyRow(row));
                return save(ctx, arr);
            }
            long localUpdated = arr.getJSONObject(idx).optLong("updated", 0);
            if (row.optLong("updated", 0) <= localUpdated) {
                return false; // 本地新（或同刻回声）：不覆盖，等上行裁决
            }
            if (deleted) {
                JSONArray next = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    if (i != idx) {
                        next.put(arr.optJSONObject(i));
                    }
                }
                return save(ctx, next);
            }
            arr.put(idx, copyRow(row));
            return save(ctx, arr);
        } catch (org.json.JSONException e) {
            return false;
        }
    }

    /** 服务端行 → 本机行形（字段白名单；parentId 缺失/为 null 一律落显式 NULL，同 create 口径） */
    private static JSONObject copyRow(JSONObject row) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("id", row.optString("id", ""));
        o.put("name", row.optString("name", ""));
        Object pid = row.opt("parentId");
        o.put("parentId", (pid == null || pid == JSONObject.NULL || "".equals(pid))
                ? JSONObject.NULL : pid);
        o.put("sortOrder", row.optInt("sortOrder", 0));
        o.put("status", row.optInt("status", 1));
        o.put("created", row.optLong("created", row.optLong("updated", 0)));
        o.put("updated", row.optLong("updated", 0));
        return o;
    }

    /**
     * 科目名清洗（MV3 {@code createSubject} 同口径）：trim（含全角空格）→ 空回 null → 截 40 字。
     * 包私有供单测。
     */
    static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String t = name.replaceAll("^[\\s\\u3000]+", "").replaceAll("[\\s\\u3000]+$", "");
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > 40 ? t.substring(0, 40) : t;
    }

    // ---------------------------------------------------------------- 文件

    private static JSONArray load(Context ctx) {
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
            return new JSONArray(new String(buf, 0, off, StandardCharsets.UTF_8));
        } catch (IOException | org.json.JSONException e) {
            return new JSONArray(); // 坏文件按空表走（与 SessionStore 坏文件跳过同口径）
        }
    }

    /** tmp+rename 原子落盘；false = 写失败（调用方据此回报失败，不谎报成功） */
    private static boolean save(Context ctx, JSONArray arr) {
        File f = file(ctx);
        File tmp = new File(ctx.getFilesDir(), "subjects.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return false;
        }
        if (tmp.renameTo(f)) {
            return true;
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        return tmp.renameTo(f);
    }

    private static boolean existsIn(JSONArray arr, String id) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id.equals(o.optString("id", ""))) {
                return true;
            }
        }
        return false;
    }
}
