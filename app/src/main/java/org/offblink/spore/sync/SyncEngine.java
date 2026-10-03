package org.offblink.spore.sync;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.offblink.spore.CaptureService;
import org.offblink.spore.agent.Session;
import org.offblink.spore.agent.SessionStore;
import org.offblink.spore.agent.SubjectsStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 同步编排（协议 = Spore-GUI SyncController/SyncServiceImpl，design/02 §四）：
 * <ol>
 *   <li><b>先上行</b>：本地 touched &gt; pushCursor 的行 + 全部墓碑 → push（LWW）→
 *       推成功才进 pushCursor（先推后拉保证「拉之前本地改动已裁决」）；</li>
 *   <li><b>再下行</b>：pull 游标增量（含墓碑）→ categories 先于 articles（引用完整性）→
 *       LWW 套用 → pullCursor = nextCursor。</li>
 * </ol>
 * 三条铁律（错了会静默丢数据，改前先读 {@link SporeSyncState} 头注）：
 * <ul>
 *   <li>pushCursor 只按「本轮真实上行过的 touched」前进，tombstone 的 ts 不参与
 *       （墓碑走账本不经游标，混进来会把没推过的本地行跳过）；</li>
 *   <li>pull 永远不碰 pushCursor；</li>
 *   <li>下行 LWW 键是 {@code server.updated &gt; local.touched}（touched 见
 *       {@link Session#touched}：改名/收藏不抬 updated 但抬 touched——
 *       用 updated 比会把本地刚改的元数据盖掉）。</li>
 * </ul>
 *
 * <p>线程：只在 SyncEngine 自己的后台线程被调（阻塞 IO + 读写会话/科目文件）；
 * 单轮互斥靠 {@link #RUNNING}，并发触发直接拒绝。
 */
public final class SyncEngine {

    /** 一轮同步的结果（message = 给人看的一句话，同步进 lastSyncMsg） */
    public static final class Result {
        public final boolean ok;
        public final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private static final int PULL_LIMIT = 500;   // 服务端上限 500
    private static final int PULL_BATCH_MAX = 50; // 10k 行还拉不完就当有毛病，防打转
    private static final int PUSH_CHUNK = 100;    // 每请求最多 100 篇文章（首配全量时别一发 10MB+）

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private SyncEngine() {
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    /** 完整一轮（设置页「立即同步」的唯一入口） */
    public static Result run(Context ctx) {
        if (!RUNNING.compareAndSet(false, true)) {
            return new Result(false, "同步进行中");
        }
        try {
            SporeSyncState st = SporeSyncState.load(ctx);
            if (!st.paired()) {
                return new Result(false, "未配对");
            }
            SyncClient client = new SyncClient(st.api, st.token);
            int up = pushPhase(ctx, client, st);
            int down = pullPhase(ctx, client, st);
            st.lastSyncAt = System.currentTimeMillis();
            st.lastSyncMsg = "上行 " + up + " · 下行 " + down;
            st.save(ctx);
            return new Result(true, st.lastSyncMsg);
        } catch (SyncClient.SyncException | IOException | JSONException e) {
            String msg = e.getMessage();
            if (msg == null || msg.isEmpty()) {
                msg = e.getClass().getSimpleName();
            }
            // 网络/业务失败也要落一笔（web 页靠它显示上次结果），游标保持原值 → 下轮重试
            SporeSyncState st = SporeSyncState.load(ctx);
            st.lastSyncAt = System.currentTimeMillis();
            st.lastSyncMsg = "失败：" + msg;
            st.save(ctx);
            return new Result(false, "同步失败：" + msg);
        } finally {
            RUNNING.set(false);
        }
    }

    // ================================================================ 上行

    /** @return 上行条数（含墓碑） */
    private static int pushPhase(Context ctx, SyncClient client, SporeSyncState st)
            throws IOException, SyncClient.SyncException, JSONException {
        long cursor = st.pushCursor;

        // ---- 科目（categories：一次请求整批发；引用完整性上它们必须先进服务端）----
        JSONArray catItems = new JSONArray();
        JSONArray rows = SubjectsStore.metaJson(ctx);
        long maxUpd = cursor;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                continue;
            }
            long upd = row.optLong("updated", 0);
            if (upd > cursor) {
                catItems.put(categoryItem(row));
                maxUpd = Math.max(maxUpd, upd);
            }
        }

        // ---- 会话（分块上行；先建全部 item 才能带着 attachmentPath 一起推）----
        List<Session> sessions = SessionStore.loadAll(ctx);
        List<JSONObject> artItems = new ArrayList<>();
        List<File> artImages = new ArrayList<>();
        for (Session s : sessions) {
            if (s.touched <= cursor) {
                continue;
            }
            try {
                artItems.add(articleItem(s));
            } catch (JSONException e) {
                continue; // 单条序列化失败不拖垮整轮（该条下轮再试）
            }
            artImages.add(imageFileOf(s));
            maxUpd = Math.max(maxUpd, s.touched);
        }

        // ---- 墓碑（账本全量：它们不走游标，推过就销账）----
        List<SyncTombstones.Item> tombs = SyncTombstones.load(ctx);
        Set<String> tombedCat = new HashSet<>();
        Set<String> tombedArt = new HashSet<>();
        for (SyncTombstones.Item t : tombs) {
            JSONObject item = tombstoneItem(t.id, t.ts);
            if (SyncTombstones.CATEGORY.equals(t.kind)) {
                catItems.put(item);
                tombedCat.add(t.id);
            } else {
                artItems.add(item);
                artImages.add(null); // 墓碑不传图
                tombedArt.add(t.id);
            }
        }

        if (catItems.length() == 0 && artItems.isEmpty()) {
            return 0; // 没东西可推：游标、账本原样
        }

        // ---- 题图先行（push-attachment 只存文件不回写行——Spore-GUI records.py 实测在案；
        //      路径必须先拿到、随本次 push 写进 attachmentPath，GUI 才显示得出截图）----
        for (int i = 0; i < artItems.size(); i++) {
            File img = artImages.get(i);
            if (img == null) {
                continue;
            }
            String path = client.pushAttachment(artItems.get(i).optString("id", ""), img);
            if (!path.isEmpty()) {
                artItems.get(i).put("attachmentPath", path);
            }
        }

        // ---- 发送：科目一次；文章分块，块成功才推进内存游标（崩溃回退整轮重推，幂等）----
        int sent = 0;
        if (catItems.length() > 0) {
            JSONObject body = new JSONObject();
            body.put("categories", catItems);
            body.put("articles", new JSONArray());
            client.push(body);
            sent += catItems.length();
        }
        for (int from = 0; from < artItems.size(); from += PUSH_CHUNK) {
            int to = Math.min(artItems.size(), from + PUSH_CHUNK);
            JSONArray chunk = new JSONArray();
            for (int i = from; i < to; i++) {
                chunk.put(artItems.get(i));
            }
            JSONObject body = new JSONObject();
            body.put("categories", new JSONArray());
            body.put("articles", chunk);
            client.push(body);
            sent += chunk.length();
        }

        // ---- 游标只吃非墓碑 touched；账本按已推的销 ----
        st.pushCursor = maxUpd;
        if (!tombedCat.isEmpty()) {
            SyncTombstones.remove(ctx, SyncTombstones.CATEGORY, tombedCat);
        }
        if (!tombedArt.isEmpty()) {
            SyncTombstones.remove(ctx, SyncTombstones.ARTICLE, tombedArt);
        }
        return sent;
    }

    /** 会话 → 上行 article 条目。{@code updated = touched}（LWW 键）；{@code categoryId} */
    static JSONObject articleItem(Session s) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("id", s.id);
        // 未分组表达为 ""（服务端 categoryExists("") → null → 置空）：null 会被 update 路径跳过，
        // 移出科目就永远同步不出去——空串是唯一能「清引用」的编码
        item.put("categoryId", s.subjectId == null ? "" : s.subjectId);
        item.put("title", (s.title == null || s.title.trim().isEmpty()) ? "新会话" : s.title);
        item.put("messages", SessionStore.toJson(s).optJSONArray("messages"));
        item.put("fav", s.fav ? 1 : 0);
        item.put("status", normalizeStatus(s.status));
        item.put("created", s.created);
        item.put("updated", s.touched);
        item.put("deleted", 0);
        return item;
    }

    /** design/03 §1：同步只推 done/error/aborted；在途/空态（进程死掉残留）一律归 done */
    static String normalizeStatus(String status) {
        if ("error".equals(status) || "aborted".equals(status)) {
            return status;
        }
        return "done";
    }

    /** subjects.json 行 → 上行 category 条目（字段照单：多级 parentId/status 不丢形） */
    static JSONObject categoryItem(JSONObject row) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("id", row.optString("id", ""));
        Object pid = row.opt("parentId");
        item.put("parentId", (pid == null || pid == JSONObject.NULL || "".equals(pid))
                ? JSONObject.NULL : pid);
        item.put("name", row.optString("name", ""));
        item.put("sortOrder", row.optInt("sortOrder", 0));
        item.put("status", row.optInt("status", 1));
        item.put("created", row.optLong("created", row.optLong("updated", 0)));
        item.put("updated", row.optLong("updated", 0));
        item.put("deleted", 0);
        return item;
    }

    /** 墓碑条目：只带 id/updated/deleted——服务端 update 路径对 null 字段逐个跳过，
     *  存量行原样只翻墓碑位；insert 路径造出来的死行不可见、无害 */
    static JSONObject tombstoneItem(String id, long ts) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("id", id);
        item.put("updated", ts);
        item.put("deleted", 1);
        return item;
    }

    /** 会话里第一张「本地真在」的题图；没有 → null（不传图） */
    private static File imageFileOf(Session s) {
        for (Session.Msg m : s.messages) {
            if (m.hasImage && m.imagePath != null && !m.imagePath.isEmpty()) {
                File f = new File(m.imagePath);
                if (f.isFile() && f.length() > 0) {
                    return f;
                }
            }
        }
        return null;
    }

    // ================================================================ 下行

    /** @return 实际套用（新建/覆盖/删除）的条数 */
    private static int pullPhase(Context ctx, SyncClient client, SporeSyncState st)
            throws IOException, SyncClient.SyncException {
        int applied = 0;
        for (int batch = 0; batch < PULL_BATCH_MAX; batch++) {
            JSONObject data = client.pull(st.pullCursor, PULL_LIMIT);
            if (data == null) {
                break;
            }
            JSONArray cats = data.optJSONArray("categories");
            JSONArray arts = data.optJSONArray("articles");
            long next = data.optLong("nextCursor", st.pullCursor);
            int catN = cats == null ? 0 : cats.length();
            int artN = arts == null ? 0 : arts.length();
            if (catN == 0 && artN == 0) {
                break; // 拉干了（nextCursor 此时等于原游标）
            }

            // 1) 科目先行：文章引用的科目必须已在本地（引用完整性，服务端同序）
            if (cats != null) {
                for (int i = 0; i < catN; i++) {
                    JSONObject row = cats.optJSONObject(i);
                    if (row == null || row.optString("id", "").isEmpty()) {
                        continue;
                    }
                    if (row.optInt("deleted", 0) == 1) {
                        // 墓碑比本地行新才摘（本地新 = 等上行裁决）；行已不在 → 无事
                        if (localSubjectNewer(ctx, row) == 0) {
                            // 级联清引用 + 摘行；remove 内会再记一笔本地墓碑（回声一次后销账，
                            // 两轮收敛——行已摘时 hit=false 不再记账）
                            CaptureService.deleteSubject(ctx, row.optString("id", ""));
                            applied++;
                        }
                    } else if (SubjectsStore.applyServerRow(ctx, row)) {
                        applied++;
                    }
                }
            }

            // 2) 会话
            long hold = Long.MAX_VALUE; // 活会话挡住的行：游标停在它前面，下轮再解
            if (arts != null) {
                for (int i = 0; i < artN; i++) {
                    JSONObject row = arts.optJSONObject(i);
                    if (row == null || row.optString("id", "").isEmpty()) {
                        continue;
                    }
                    int r = applyArticleRow(ctx, client, row);
                    if (r > 0) {
                        applied++;
                    } else if (r < 0) {
                        hold = Math.min(hold, row.optLong("updated", 0) - 1);
                    }
                }
            }

            long prev = st.pullCursor;
            st.pullCursor = Math.max(prev, Math.min(next, hold));
            if (st.pullCursor <= prev || st.pullCursor < next) {
                // 游标没走到 next：要么服务端没推进（防打转），要么被在途回合挡住
                // （收束本轮，下轮从这重拉——已套用的行回声跳过，代价一次空转）
                break;
            }
        }
        return applied;
    }

    /**
     * 单条会话下行套用。LWW 判据 = {@code server.updated > local.touched}：
     * 本地新（含改名/收藏这种不抬 updated 的）→ 跳过等上行；相等 = 自己刚推的回声 → 跳过。
     *
     * @return 1 = 套用了；0 = 无事（本地新/回声/坏行，游标照走）；
     *         -1 = 该会话在途回合里，本轮跳过（调用方要把游标停在它前面）
     */
    private static int applyArticleRow(Context ctx, SyncClient client, JSONObject row)
            throws IOException, SyncClient.SyncException {
        String id = row.optString("id", "");
        long serverUpd = row.optLong("updated", 0);
        boolean deleted = row.optInt("deleted", 0) == 1;
        Session local = SessionStore.load(ctx, id);

        if (local == null) {
            if (deleted) {
                return 0; // 本就没这条（可能已推过销过账）：无事
            }
            Session s = mergeFromServer(row);
            if (s == null) {
                return 0;
            }
            fetchAttachment(ctx, client, id, row, s);
            fixDanglingSubject(ctx, s);
            return CaptureService.applyFromSync(ctx, s) ? 1 : -1;
        }

        if (!serverWins(serverUpd, local.touched)) {
            return 0; // 本地新/回声：本地保持，等本轮上行裁决
        }
        if (deleted) {
            // CaptureService.deleteSession = 引擎在场走内存态（掐在途回合）否则删文件；
            // 会记本地墓碑（回声一次后销，两轮收敛）
            return CaptureService.deleteSession(ctx, id) ? 1 : -1;
        }
        Session s = mergeFromServer(row);
        if (s == null) {
            return 0;
        }
        fetchAttachment(ctx, client, id, row, s);
        fixDanglingSubject(ctx, s);
        return CaptureService.applyFromSync(ctx, s) ? 1 : -1;
    }

    /**
     * 下行 LWW 判据（会话）：服务端 {@code updated} 严格大于本地 {@code touched} 才套用。
     * 相等 = 刚推上去的回声（必须跳过，否则每次同步都把本地行重写一遍并回声打转）；
     * 小于 = 本地新（含改名/收藏这类只抬 touched 的），保留本地等上行裁决。
     */
    static boolean serverWins(long serverUpdated, long localTouched) {
        return serverUpdated > localTouched;
    }

    /** 服务端行 → 本地 Session（纯转换；题图下载/悬挂引用清扫由调用方做） */
    static Session mergeFromServer(JSONObject row) {
        try {
            // 服务端 Jackson 把 null 字段照发：Android optString 会把 JSON null 变字面量
            // "null"（本仓第四轮血泪坑）——先剥 null 键再走统一反序列化
            JSONObject clean = stripNulls(row);
            Session s = SessionStore.fromJson(clean);
            // 服务端 fav 是数字 0/1，optBoolean 认不了（Android JSON.toBoolean 只认 Boolean/"true"）
            s.fav = row.optInt("fav", 0) == 1;
            s.subjectId = clean.isNull("categoryId") ? null
                    : clean.optString("categoryId", "");
            if (s.subjectId != null && s.subjectId.isEmpty()) {
                s.subjectId = null;
            }
            // 下行权威：两个时间戳都对齐服务端（否则本地 touched 落回旧值，又会被当新改动重推）
            s.updated = row.optLong("updated", 0);
            s.touched = row.optLong("updated", 0);
            return s;
        } catch (JSONException e) {
            return null;
        }
    }

    /** 递归剥 JSON null 键（数组里的 null 元素丢弃）。包内供测试钉形状。 */
    static JSONObject stripNulls(JSONObject o) throws JSONException {
        JSONObject out = new JSONObject();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = o.get(k);
            if (v == null || v == JSONObject.NULL) {
                continue;
            }
            if (v instanceof JSONObject) {
                out.put(k, stripNulls((JSONObject) v));
            } else if (v instanceof JSONArray) {
                out.put(k, stripNulls((JSONArray) v));
            } else {
                out.put(k, v);
            }
        }
        return out;
    }

    static JSONArray stripNulls(JSONArray arr) throws JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            Object v = arr.get(i);
            if (v == null || v == JSONObject.NULL) {
                continue; // null 元素直接丢（消息数组不该有，防御）
            }
            if (v instanceof JSONObject) {
                out.put(stripNulls((JSONObject) v));
            } else if (v instanceof JSONArray) {
                out.put(stripNulls((JSONArray) v));
            } else {
                out.put(v);
            }
        }
        return out;
    }

    /** 悬空科目 → 回未分组（镜像服务端 categoryExists 规则，kit design/03 §三 两端不许悬挂） */
    private static void fixDanglingSubject(Context ctx, Session s) {
        if (s.subjectId != null && !SubjectsStore.exists(ctx, s.subjectId)) {
            s.subjectId = null;
        }
    }

    /**
     * 题图下行：会话里有带图消息且本地文件不在、服务端 attachmentPath 非空 → 拉到
     * {@code captures/att_<id>.jpg} 并把该消息的 imagePath 改指本地。
     * 拉不到/没图都不算错（web 端显示空位而已）；下载改路径 <b>不抬 touched</b>（saveQuiet 语义）。
     */
    private static void fetchAttachment(Context ctx, SyncClient client, String id,
                                        JSONObject row, Session s)
            throws IOException, SyncClient.SyncException {
        String rel = row.isNull("attachmentPath") ? "" : row.optString("attachmentPath", "");
        if (rel.isEmpty() || "null".equals(rel)) {
            return; // 键缺席/null（还没上过题图）；"null" 字面量兜 Jackson null 键的老坑
        }
        Session.Msg target = null;
        for (Session.Msg m : s.messages) {
            if (!m.hasImage) {
                continue;
            }
            if (m.imagePath == null || m.imagePath.isEmpty()
                    || !new File(m.imagePath).isFile()) {
                target = m;
                break;
            }
        }
        if (target == null) {
            return; // 图都在（回声/重拉）或这会话本来没图
        }
        byte[] bytes = client.pullAttachment(rel);
        if (bytes == null || bytes.length == 0) {
            return;
        }
        File dir = new File(ctx.getFilesDir(), "captures");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, "att_" + id + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(bytes);
        }
        target.imagePath = out.getAbsolutePath();
    }

    /**
     * 科目墓碑判定：本地行比墓碑新 → 不摘（返回 1）；行不存在 → -1；墓碑新 → 0。
     * 只为「本地新保留」这一支服务，调用方已另查 exists。
     */
    private static int localSubjectNewer(Context ctx, JSONObject row) {
        String id = row.optString("id", "");
        JSONArray all = SubjectsStore.metaJson(ctx);
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && id.equals(o.optString("id", ""))) {
                return row.optLong("updated", 0) > o.optLong("updated", 0) ? 0 : 1;
            }
        }
        return -1;
    }
}
