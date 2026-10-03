package org.offblink.spore.agent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 会话持久化（记录页数据源）。桌面 store 的文件版：一会话一 JSON，落在
 * {@code filesDir/sessions/<id>.json}，id 即文件名（时间戳 → 名序 = 时序）。
 * 红线：落盘失败只丢一次记录，绝不打断作答回合；thinkOpen 等 UI 瞬态不落。
 */
public final class SessionStore {

    private SessionStore() {
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "sessions");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /**
     * 覆盖写一个会话（用户动作入口：改名/收藏/移科目/消息落盘都走这）。
     *
     * **不动 {@code updated}**（第九轮拍板）：列表按 updated 倒序，任何一次写盘都改时间戳
     * 就等于「收藏/改名/点开都把该条顶到最前」。所以：
     * 内容（消息）变了走 {@link #saveActive} 自己抬时间戳，改名/收藏这类元数据写走这里，
     * 顺序保持原位。
     *
     * 但 {@code touched} 每次都抬——同步上行游标看它（改名/收藏也要同步出去，
     * 见 {@link Session#touched}）。
     */
    public static void save(Context ctx, Session s) {
        if (s != null) {
            s.touched = System.currentTimeMillis();
        }
        saveQuiet(ctx, s);
    }

    /**
     * 原样落盘：不抬 {@code touched}、不抬 {@code updated}。同步下行回写专用——
     * 内容以外部为权威，自抬时间戳会把这次拉取变成下一轮上行的「本地新改动」，回声打转。
     */
    public static void saveQuiet(Context ctx, Session s) {
        if (s == null || s.id.isEmpty()) {
            return;
        }
        try {
            File f = new File(dir(ctx), s.id + ".json");
            File tmp = new File(dir(ctx), s.id + ".json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(toJson(s).toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                if (!tmp.renameTo(f)) {
                    throw new IOException("rename failed");
                }
            }
        } catch (IOException | org.json.JSONException ignored) {
            // 记录是次要品：失败不打断主链路（下次回合结束再试）
        }
    }

    /** 内容落盘（回合结束 / 新会话归档）：抬 {@code updated}，列表按最新活动排前。 */
    public static void saveActive(Context ctx, Session s) {
        if (s != null) {
            s.updated = System.currentTimeMillis();
        }
        save(ctx, s);
    }

    /** 全量加载，按更新时间倒序；坏文件跳过；进程死掉的进行中状态归一为 done */
    public static List<Session> loadAll(Context ctx) {
        List<Session> out = new ArrayList<>();
        File[] files = dir(ctx).listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) {
            return out;
        }
        for (File f : files) {
            Session s = loadFile(f);
            if (s != null) {
                out.add(s);
            }
        }
        Collections.sort(out, Comparator.comparingLong((Session s) -> s.updated).reversed());
        return out;
    }

    /** 按 id 取单个；不存在/损坏 → null */
    public static Session load(Context ctx, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return loadFile(new File(dir(ctx), id + ".json"));
    }

    /**
     * 删除一个会话（记录页/会话列表的删除动作）；文件不存在视为已删。
     * 文件真在 → 先记墓碑（{@link org.offblink.spore.sync.SyncTombstones}，
     * 同步上行 deleted=1 传播到 PC，kit design/03 §二）再删。
     */
    public static void delete(Context ctx, String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        File f = new File(dir(ctx), id + ".json");
        if (f.exists()) {
            org.offblink.spore.sync.SyncTombstones.add(
                    ctx, org.offblink.spore.sync.SyncTombstones.ARTICLE, id);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /**
     * 删科目扫尾：把仍指向该科目的会话置回未分组（kit design/03 §三「任何一端都不允许
     * 出现悬挂引用」）。只改字段不抬 updated——级联清引用是科目删除事件的派生动作，
     * 不算用户活动（删除事件本身经 subjects.json 传播，会话不需要为它重排）。
     *
     * {@code liveIds} = 引擎内存态持有的会话 id（当前视图 + 在途回合）：**必须排除**——
     * 它们的磁盘文件在回合期间是陈旧的（消息只在回合结束 persist），读旧写旧会把
     * 刚 persist 的消息回滚掉；这些会话的引用由 {@code AgentEngine.deleteSubject}
     * 在内存里清并经 live 对象落盘。
     */
    public static void clearSubject(Context ctx, String subjectId, java.util.Set<String> liveIds) {
        if (subjectId == null || subjectId.isEmpty()) {
            return;
        }
        for (Session s : loadAll(ctx)) {
            if (subjectId.equals(s.subjectId) && (liveIds == null || !liveIds.contains(s.id))) {
                s.subjectId = null;
                save(ctx, s);
            }
        }
    }

    private static Session loadFile(File f) {
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
            return fromJson(new JSONObject(new String(buf, 0, off, StandardCharsets.UTF_8)));
        } catch (IOException | org.json.JSONException e) {
            return null;
        }
    }

    /**
     * 会话完整 JSON（持久化与 web bridge 共用）。
     * bridge 会在自己的线程调它，引擎线程同时往 session.messages 里 append →
     * 先拷贝再序列化：会话对象内 messages 只 append 不移除，拷贝即安全快照
     * （直连遍历在 bridge 线程上有 CME 风险，别改回去）。
     */
    public static JSONObject toJson(Session s) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("id", s.id);
        o.put("created", s.created);
        o.put("updated", s.updated);
        o.put("touched", s.touched);
        o.put("fav", s.fav);
        // 显式判空（不依赖 put 对 null 的实现差异）；键缺席 = 未分组，兼容老 JSON
        if (s.subjectId != null) {
            o.put("subjectId", s.subjectId);
        }
        o.put("title", s.title);
        o.put("status", s.status);
        JSONArray msgs = new JSONArray();
        // 快照拷贝：messages 是**只 append** 的，进场先取定长、再逐个 get —— 回合线程正在
        // append 也不会撞到失效下标。别改回 new ArrayList<>(s.messages)：并发扩容时它可能
        // 用「新 size + 旧数组」拷出越界（第九轮并行后，生成中改名/收藏会真的并发走到这里）。
        int n = s.messages.size();
        for (int i = 0; i < n; i++) {
            msgs.put(msgToJson(s.messages.get(i)));
        }
        o.put("messages", msgs);
        return o;
    }

    /**
     * web bridge 索引条目（记录页分页 / 面板 listpop）：不带 messages，
     * 全量消息走 {@link #toJson} 按需单取，避免索引每次读盘把所有消息拼一遍。
     */
    public static JSONArray metaJson(Context ctx) {
        JSONArray out = new JSONArray();
        for (Session s : loadAll(ctx)) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", s.id);
                o.put("title", s.title);
                o.put("created", s.created);
                o.put("updated", s.updated);
                o.put("fav", s.fav);
                // 键缺席 = 未分组（与 toJson 同约定）
                if (s.subjectId != null) {
                    o.put("subjectId", s.subjectId);
                }
                o.put("status", s.status);
                o.put("msgCount", s.messages.size());
            } catch (org.json.JSONException ignored) {
            }
            out.put(o);
        }
        return out;
    }

    /**
     * 截图 data URL（web 端按 path 只取一次并缓存；base64 几百 KB，绝不内嵌进 sessionJson
     * 反复过桥）。文件缺失/读失败回空串。
     */
    public static String imageDataUrl(String path) {
        try {
            if (path == null || path.isEmpty()) {
                return "";
            }
            File f = new File(path);
            if (!f.isFile() || f.length() == 0) {
                return "";
            }
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n < 0) {
                        break;
                    }
                    off += n;
                }
            }
            if (buf.length > 400 * 1024) {
                // 手机 1440p 截图 q82 可超 1MB：桥上再乘 base64/4/3 与字符串拷贝，
                // JavaBridge 线程 OOM = 进程死（第五轮崩溃家族）→ 超 400KB 降采样到 1600px
                android.graphics.BitmapFactory.Options bound =
                        new android.graphics.BitmapFactory.Options();
                bound.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeByteArray(buf, 0, buf.length, bound);
                int longSide = Math.max(bound.outWidth, bound.outHeight);
                android.graphics.BitmapFactory.Options dec =
                        new android.graphics.BitmapFactory.Options();
                dec.inSampleSize = Math.max(1, longSide / 1600);
                android.graphics.Bitmap bmp =
                        android.graphics.BitmapFactory.decodeByteArray(buf, 0, buf.length, dec);
                if (bmp != null) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, bos);
                    bmp.recycle();
                    buf = bos.toByteArray();
                }
            }
            return "data:image/jpeg;base64,"
                    + android.util.Base64.encodeToString(buf, android.util.Base64.NO_WRAP);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /**
     * 「保存到相册」（照片全屏查看器的保存按钮）：Q+ 走 MediaStore Pictures/Spore
     * （免权限、系统相册可见）；pre-Q 无运行时存储权限链 → 落应用外置 Pictures
     * （文件管理器可达）。true = 已落盘；路径非法/写失败回 false。
     */
    public static boolean saveToGallery(Context ctx, String path) {
        try {
            if (path == null || path.isEmpty()) {
                return false;
            }
            File src = new File(path);
            if (!src.isFile() || src.length() == 0) {
                return false;
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                        "Spore_" + src.getName());
                v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_PICTURES + "/Spore");
                v.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
                android.content.ContentResolver cr = ctx.getContentResolver();
                android.net.Uri uri = cr.insert(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) {
                    return false;
                }
                try (java.io.OutputStream os = cr.openOutputStream(uri)) {
                    if (os == null || !copy(src, os)) {
                        cr.delete(uri, null, null);
                        return false;
                    }
                }
                v.clear();
                v.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
                cr.update(uri, v, null, null);
                return true;
            }
            // pre-Q：应用外置 Pictures（免运行时权限）
            File dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
            if (dir == null) {
                dir = new File(ctx.getFilesDir(), "pictures");
            }
            try (FileOutputStream fos = new FileOutputStream(new File(dir, "Spore_" + src.getName()))) {
                return copy(src, fos);
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean copy(File src, java.io.OutputStream os) throws IOException {
        try (FileInputStream in = new FileInputStream(src)) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                os.write(chunk, 0, n);
            }
            return true;
        }
    }

    private static JSONObject msgToJson(Session.Msg m) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("role", m.role);
        o.put("kind", m.kind == null ? "" : m.kind);
        o.put("text", m.text);
        o.put("think", m.think);
        o.put("no", m.no);
        o.put("title", m.title);
        o.put("ans", m.ans);
        o.put("why", m.why);
        o.put("hasImage", m.hasImage);
        o.put("imagePath", m.imagePath);
        o.put("ts", m.ts);
        o.put("verifyRan", m.verifyRan);
        o.put("verifySkipped", m.verifySkipped);
        o.put("verifyPending", m.verifyPending);
        o.put("verifyVerdict", m.verifyVerdict);
        o.put("verifyNote", m.verifyNote);
        o.put("verifyThink", m.verifyThink);
        o.put("tools", new JSONArray(m.tools));
        return o;
    }

    /**
     * 会话反序列化（本包与同步回写共用）。包私有契约由 SessionStoreTest 钉住：
     * subjectId 无键 → null；{@code touched} 无键（老 JSON / 服务端行）→ 回落 {@code updated}。
     */
    public static Session fromJson(JSONObject o) throws org.json.JSONException {
        Session s = new Session();
        s.id = o.optString("id", s.id);
        s.created = o.optLong("created", s.created);
        s.updated = o.optLong("updated", s.updated);
        s.touched = o.optLong("touched", s.updated);
        s.fav = o.optBoolean("fav", false);
        // 必须先 isNull：org.json 的 optString 会把 JSON null 变成字面量 "null"
        s.subjectId = o.isNull("subjectId") ? null : o.optString("subjectId", "");
        if (s.subjectId != null && s.subjectId.isEmpty()) {
            s.subjectId = null;
        }
        s.title = o.optString("title", "新会话");
        s.status = o.optString("status", "");
        // 回合跑一半进程死了（华为杀后台真实发生）→ 展示层按已结束处理
        if ("answering".equals(s.status) || "verifying".equals(s.status)
                || "searching".equals(s.status) || s.status.isEmpty()) {
            s.status = "done";
        }
        JSONArray msgs = o.optJSONArray("messages");
        if (msgs != null) {
            for (int i = 0; i < msgs.length(); i++) {
                s.messages.add(msgFromJson(msgs.getJSONObject(i)));
            }
        }
        return s;
    }

    private static Session.Msg msgFromJson(JSONObject o) throws org.json.JSONException {
        Session.Msg m = new Session.Msg();
        m.role = o.optString("role", "user");
        m.kind = o.optString("kind", "");
        m.text = o.optString("text", "");
        m.think = o.optString("think", "");
        m.no = o.optString("no", "");
        m.title = o.optString("title", "");
        m.ans = o.optString("ans", "");
        m.why = o.optString("why", "");
        m.hasImage = o.optBoolean("hasImage", false);
        m.imagePath = o.optString("imagePath", "");
        m.ts = o.optLong("ts", 0);
        m.verifyRan = o.optBoolean("verifyRan", false);
        m.verifySkipped = o.optBoolean("verifySkipped", false);
        m.verifyPending = o.optBoolean("verifyPending", false);
        m.verifyVerdict = o.optString("verifyVerdict", "");
        m.verifyNote = o.optString("verifyNote", "");
        m.verifyThink = o.optString("verifyThink", "");
        JSONArray tools = o.optJSONArray("tools");
        if (tools != null) {
            for (int i = 0; i < tools.length(); i++) {
                m.tools.add(tools.getString(i));
            }
        }
        return m;
    }
}
