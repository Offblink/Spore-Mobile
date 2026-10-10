package org.offblink.spore.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.offblink.spore.CaptureService;
import org.offblink.spore.agent.Session;
import org.offblink.spore.agent.SessionStore;
import org.offblink.spore.agent.SubjectsStore;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 同步幂等 / 科目归属回归探针（用户 2026-10-10 第三条：「已放进科目的会话，多次点击
 * 同步后又回到未分组」「第二次同步上行/下行都应为 0，现在不是」）。
 *
 * <p>服务端替身按 Spore-GUI SyncServiceImpl 真语义写死（只读核实，本仓不改协议）：
 * <ul>
 *   <li>LWW：{@code clientUpdated > server.update_time} **严格大于**，同毫秒服务端胜；</li>
 *   <li>{@code mergeArticle} 赢了才 {@code a_setCategory}，而 {@code categoryExists} 要求
 *       该科目行存在且 user_id 等于推送者，否则 **静默置 NULL（accepted:true 不报错）**；</li>
 *   <li><b>push-attachment 会抬 update_time</b>：SyncController.pushAttachment →
 *       SyncServiceImpl.bindAttachment → {@code articleMapper.update(只带 attachmentPath 的实体)} →
 *       MyBatis-Plus {@code MetaObjectHandler.updateFill} 的 strictFillStrategy
 *       （「值为 null 才填」，Article.updateTime 是 {@code fill=INSERT_UPDATE}）→
 *       {@code update_time = LocalDateTime.now()}（服务端时钟）。</li>
 * </ul>
 * 所以题图先于行推送时，服务端 update_time 被抬到「推图时刻」，紧跟其后的行 push（键 =
 * 编辑时刻 touched）必被 LWW 拒掉 → 科目永远落不了地 → 同一轮 pull 又把服务端的
 * categoryId=NULL 回灌成本地未分组。
 */
public class SyncIdempotencyProbeTest {

    // ------------------------------------------------------------- 基建

    /** 只落在临时目录的 Context + 内存 SharedPreferences（SporeSyncState 要读写 prefs） */
    private static final class FakeContext extends ContextWrapper {
        final File files;
        final MemPrefs prefs = new MemPrefs();

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
            return prefs;
        }
    }

    /** 最小内存 prefs：只实现 SporeSyncState 读写的 getString/getLong/edit 那几个方法 */
    private static final class MemPrefs implements SharedPreferences {
        final Map<String, Object> m = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(m);
        }

        @Override
        public String getString(String k, String d) {
            Object v = m.get(k);
            return v instanceof String ? (String) v : d;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Set<String> getStringSet(String k, Set<String> d) {
            Object v = m.get(k);
            return v instanceof Set ? (Set<String>) v : d;
        }

        @Override
        public int getInt(String k, int d) {
            Object v = m.get(k);
            return v instanceof Integer ? (Integer) v : d;
        }

        @Override
        public long getLong(String k, long d) {
            Object v = m.get(k);
            return v instanceof Long ? (Long) v : d;
        }

        @Override
        public float getFloat(String k, float d) {
            Object v = m.get(k);
            return v instanceof Float ? (Float) v : d;
        }

        @Override
        public boolean getBoolean(String k, boolean d) {
            Object v = m.get(k);
            return v instanceof Boolean ? (Boolean) v : d;
        }

        @Override
        public boolean contains(String k) {
            return m.containsKey(k);
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener l) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener l) {
        }

        @Override
        public Editor edit() {
            return new Editor() {
                @Override
                public Editor putString(String k, String v) {
                    m.put(k, v);
                    return this;
                }

                @Override
                public Editor putStringSet(String k, Set<String> v) {
                    m.put(k, v);
                    return this;
                }

                @Override
                public Editor putInt(String k, int v) {
                    m.put(k, v);
                    return this;
                }

                @Override
                public Editor putLong(String k, long v) {
                    m.put(k, v);
                    return this;
                }

                @Override
                public Editor putFloat(String k, float v) {
                    m.put(k, v);
                    return this;
                }

                @Override
                public Editor putBoolean(String k, boolean v) {
                    m.put(k, v);
                    return this;
                }

                @Override
                public Editor remove(String k) {
                    m.remove(k);
                    return this;
                }

                @Override
                public Editor clear() {
                    m.clear();
                    return this;
                }

                @Override
                public boolean commit() {
                    return true;
                }

                @Override
                public void apply() {
                }
            };
        }
    }

    /** 服务端真语义替身（SyncServiceImpl 只读移植） */
    private static final class FakeServer {
        static final class Cat {
            String id;
            long userId;
            String name;
            long updated;
            boolean deleted;
        }

        static final class Art {
            String id;
            long userId;
            String categoryId; // null = 未分组
            String title;
            JSONArray messages;
            long updated;
            boolean deleted;
        }

        long uid = 4;
        long lastNow;
        final Map<String, Cat> cats = new LinkedHashMap<>();
        final Map<String, Art> arts = new LinkedHashMap<>();
        int pushCalls;
        int pullCalls;
        int attachCalls;

        /** 服务端时钟：单调递增，保证「推图时刻 > 本地编辑时刻」这个现实序不被同毫秒抹平 */
        long now() {
            lastNow = Math.max(System.currentTimeMillis(), lastNow + 1);
            return lastNow;
        }

        private static boolean lww(long clientUpdated, long serverUpdated) {
            return clientUpdated > serverUpdated; // 严格大于（同毫秒服务端胜）
        }

        private String categoryExists(long userId, String categoryId) {
            if (categoryId == null || categoryId.trim().isEmpty()) {
                return null;
            }
            Cat c = cats.get(categoryId.trim());
            return (c != null && !c.deleted && c.userId == userId) ? c.id : null;
        }

        JSONArray push(long userId, JSONObject body) throws JSONException {
            pushCalls++;
            JSONArray res = new JSONArray();
            JSONArray ci = body.optJSONArray("categories");
            for (int i = 0; ci != null && i < ci.length(); i++) {
                JSONObject it = ci.getJSONObject(i);
                res.put(resOf(it.optString("id", ""), mergeCat(userId, it)));
            }
            JSONArray ai = body.optJSONArray("articles");
            for (int i = 0; ai != null && i < ai.length(); i++) {
                JSONObject it = ai.getJSONObject(i);
                res.put(resOf(it.optString("id", ""), mergeArt(userId, it)));
            }
            return res;
        }

        private boolean mergeCat(long userId, JSONObject it) throws JSONException {
            String id = it.optString("id", "");
            if (id.isEmpty()) {
                return false;
            }
            Cat c = cats.get(id);
            if (c == null) {
                Cat n = new Cat();
                n.id = id;
                n.userId = userId;
                n.name = it.optString("name", "");
                n.updated = it.optLong("updated", 0);
                n.deleted = it.optInt("deleted", 0) == 1;
                cats.put(id, n);
                return true;
            }
            if (c.userId != userId) {
                c.userId = userId; // 归属跟推送者走
            }
            if (!lww(it.optLong("updated", 0), c.updated)) {
                return false;
            }
            c.name = it.optString("name", c.name);
            c.updated = it.optLong("updated", 0);
            c.deleted = it.optInt("deleted", 0) == 1;
            return true;
        }

        private boolean mergeArt(long userId, JSONObject it) throws JSONException {
            String id = it.optString("id", "");
            if (id.isEmpty()) {
                return false;
            }
            Art a = arts.get(id);
            if (a == null) {
                Art n = new Art();
                n.id = id;
                n.userId = userId;
                n.categoryId = categoryExists(userId, it.optString("categoryId", ""));
                n.title = it.optString("title", "新会话");
                n.messages = it.optJSONArray("messages");
                n.updated = it.optLong("updated", 0);
                n.deleted = it.optInt("deleted", 0) == 1;
                arts.put(id, n);
                return true;
            }
            if (a.userId != userId) {
                a.userId = userId;
            }
            if (!lww(it.optLong("updated", 0), a.updated)) {
                return false; // 服务端新：内容/科目一个字都不落
            }
            if (it.has("categoryId")) {
                // 悬空/跨账号 → 静默置 NULL（accepted:true，不报错）
                a.categoryId = categoryExists(userId, it.optString("categoryId", ""));
            }
            if (!it.optString("title").isEmpty()) {
                a.title = it.optString("title");
            }
            if (it.optJSONArray("messages") != null) {
                a.messages = it.optJSONArray("messages");
            }
            a.updated = it.optLong("updated", 0);
            a.deleted = it.optInt("deleted", 0) == 1;
            return true;
        }

        /** push-attachment：写文件 + **回写行**（bindAttachment → MP updateFill 抬 update_time） */
        String bindAttachment(long userId, String articleId, String path) {
            attachCalls++;
            Art a = arts.get(articleId);
            if (a != null && a.userId == userId) {
                a.categoryId = a.categoryId; // 不动业务字段
                a.updated = now();           // ← 就是这一抬：服务端时钟盖过本地 touched
            }
            return articleId + ".jpg";
        }

        JSONObject pull(long userId, long cursor, int limit) throws JSONException {
            pullCalls++;
            List<String> cIds = new ArrayList<>();
            List<String> aIds = new ArrayList<>();
            for (Cat c : cats.values()) {
                if (c.userId == userId && c.updated > cursor) {
                    cIds.add(c.id);
                }
            }
            for (Art a : arts.values()) {
                if (a.userId == userId && a.updated > cursor) {
                    aIds.add(a.id);
                }
            }
            sortByUpdated(cIds, true);
            sortByUpdated(aIds, false);
            boolean cHit = cIds.size() >= limit;
            boolean aHit = aIds.size() >= limit;
            if (cHit) {
                cIds = cIds.subList(0, limit);
            }
            if (aHit) {
                aIds = aIds.subList(0, limit);
            }
            JSONArray outC = new JSONArray();
            long maxC = 0;
            for (String id : cIds) {
                Cat c = cats.get(id);
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("name", c.name);
                o.put("parentId", JSONObject.NULL);
                o.put("sortOrder", 0);
                o.put("status", 1);
                o.put("created", c.updated);
                o.put("updated", c.updated);
                o.put("deleted", c.deleted ? 1 : 0);
                outC.put(o);
                maxC = Math.max(maxC, c.updated);
            }
            JSONArray outA = new JSONArray();
            long maxA = 0;
            for (String id : aIds) {
                Art a = arts.get(id);
                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("categoryId", a.categoryId == null ? JSONObject.NULL : a.categoryId);
                o.put("title", a.title);
                o.put("messages", a.messages == null ? new JSONArray() : a.messages);
                o.put("fav", 0);
                o.put("status", "done");
                o.put("attachmentPath", JSONObject.NULL);
                o.put("created", a.updated);
                o.put("updated", a.updated);
                o.put("deleted", a.deleted ? 1 : 0);
                outA.put(o);
                maxA = Math.max(maxA, a.updated);
            }
            long next;
            if (cHit || aHit) {
                long cap = Long.MAX_VALUE;
                if (cHit) {
                    cap = Math.min(cap, maxC);
                }
                if (aHit) {
                    cap = Math.min(cap, maxA);
                }
                next = Math.max(cursor, cap);
            } else {
                next = Math.max(cursor, Math.max(maxC, maxA));
            }
            JSONObject page = new JSONObject();
            page.put("categories", outC);
            page.put("articles", outA);
            page.put("nextCursor", next);
            return page;
        }

        private void sortByUpdated(List<String> ids, boolean cat) {
            Collections.sort(ids, Comparator.comparingLong(id -> cat
                    ? cats.get(id).updated : arts.get(id).updated));
        }

        private static JSONObject resOf(String id, boolean ok) throws JSONException {
            JSONObject r = new JSONObject();
            r.put("id", id);
            r.put("accepted", ok);
            return r;
        }
    }

    /** 同步客户端替身：所有请求落 FakeServer */
    private static final class FakeClient extends SyncClient {
        final FakeServer server;
        volatile boolean blockInPull;

        FakeClient(FakeServer server) {
            super("http://127.0.0.1:1/api", "probe");
            this.server = server;
        }

        @Override
        public JSONObject pull(long cursor, int limit) {
            if (blockInPull) {
                try {
                    Thread.sleep(400); // 拉长一轮，给并发用例留交错窗口
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                return server.pull(server.uid, cursor, limit);
            } catch (JSONException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public JSONArray push(JSONObject body) {
            try {
                return server.push(server.uid, body);
            } catch (JSONException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public String pushAttachment(String articleId, File file) {
            return server.bindAttachment(server.uid, articleId, articleId + ".jpg");
        }

        @Override
        public JSONObject me() {
            try {
                JSONObject o = new JSONObject();
                o.put("id", server.uid);
                o.put("nickname", "probe");
                return o;
            } catch (JSONException e) {
                throw new AssertionError(e);
            }
        }
    }

    private static FakeContext freshCtx(String name) {
        File dir = new File("C:/tmp/scratch/mobile-sync-idem/ut", name);
        deleteRecursively(dir);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new FakeContext(dir);
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursively(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static int pushPhase(Context ctx, SyncClient client, SporeSyncState st) throws Exception {
        Method m = SyncEngine.class.getDeclaredMethod(
                "pushPhase", Context.class, SyncClient.class, SporeSyncState.class);
        m.setAccessible(true);
        return (Integer) m.invoke(null, ctx, client, st);
    }

    private static int pullPhase(Context ctx, SyncClient client, SporeSyncState st) throws Exception {
        Method m = SyncEngine.class.getDeclaredMethod(
                "pullPhase", Context.class, SyncClient.class, SporeSyncState.class);
        m.setAccessible(true);
        return (Integer) m.invoke(null, ctx, client, st);
    }

    /** 一轮 = push → pull（SyncEngine.run 的主体；run() 另有身份校验与状态落盘） */
    private static int[] round(Context ctx, SyncClient client) throws Exception {
        SporeSyncState st = SporeSyncState.load(ctx);
        int up = pushPhase(ctx, client, st);
        int down = pullPhase(ctx, client, st);
        st.save(ctx);
        return new int[] {up, down};
    }

    /** 服务端已有的行（未入科） */
    private static FakeServer.Art art(FakeServer server, String id, long updated, String title) {
        FakeServer.Art a = new FakeServer.Art();
        a.id = id;
        a.userId = server.uid;
        a.updated = updated;
        a.title = title;
        return a;
    }

    private static File writeImage(FakeContext ctx) throws Exception {
        File dir = new File(ctx.getFilesDir(), "captures");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File img = new File(dir, "cap_20261010_150820.jpg");
        try (FileOutputStream out = new FileOutputStream(img)) {
            out.write(new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3});
        }
        return img;
    }

    private static Session imageSession(String id, String title, long ts, File img) {
        Session s = new Session();
        s.id = id;
        s.title = title;
        s.created = ts;
        s.updated = ts;
        s.touched = ts;
        s.status = "done";
        Session.Msg m = new Session.Msg();
        m.role = "user";
        m.kind = "";
        if (img != null) {
            m.hasImage = true;
            m.imagePath = img.getAbsolutePath();
        }
        m.ts = ts;
        s.messages.add(m);
        return s;
    }

    // ============================================================ 片1：科目回退 + 第二轮 0/0

    /**
     * 主断言（用户 2026-10-10 第三条）：会话已入科 → 同步一轮 → 科目不许回退；
     * 紧接着第二轮同步必须「上行 0 · 下行 0」。
     *
     * <p>复现序（当前 HEAD 必红）：本地移入科目（touched = 编辑时刻）→ 同一轮里
     * 「先推题图后推行」→ 服务端 bindAttachment 把 update_time 抬到推图时刻 →
     * 行 push 的 LWW 键（= touched）严格小于它 → <b>被拒</b> → 服务端 category_id 仍是 NULL →
     * 同一轮 pull 套用该行 → 本地 subjectId 被清回 null；且被抬高的 update_time >
     * pushCursor → 第二轮又上行又下行（回声打转）。
     */
    @Test
    public void 双击两轮_科目不回退_第二轮0上行0下行() throws Exception {
        FakeContext ctx = freshCtx("doubleclick");
        FakeServer server = new FakeServer();
        FakeClient client = new FakeClient(server);
        File img = writeImage(ctx);

        String sid = "20261010-150820211";
        long t0 = System.currentTimeMillis() - 60_000;

        // 服务端早前一轮已有的行（当时未入科）
        FakeServer.Art old = new FakeServer.Art();
        old.id = sid;
        old.userId = server.uid;
        old.updated = t0;
        old.title = "3跳卫星链路时延计算";
        server.arts.put(sid, old);

        // 本地：同一行，带题图（手机自己截的图，本地文件真在）
        SessionStore.saveQuiet(ctx, imageSession(sid, "3跳卫星链路时延计算", t0, img));

        // 用户移入科目（saveActive 抬 touched/updated），再把时间拨回 5 秒前
        // —— 模拟真实操作「编辑完过几秒才点同步」（否则同毫秒会掩盖 LWW 拒绝）
        JSONObject cat = SubjectsStore.create(ctx, "cn阶测一");
        assertNotNull(cat);
        String cid = cat.getString("id");
        assertTrue(CaptureService.assignSubject(ctx, sid, cid));
        Session cur = SessionStore.load(ctx, sid);
        cur.touched -= 5000;
        cur.updated -= 5000;
        SessionStore.saveQuiet(ctx, cur);

        SporeSyncState st = SporeSyncState.load(ctx);
        st.pair(ctx, "http://127.0.0.1:1/api", "probe", "probe");
        st.uid = server.uid;
        st.pullCursor = t0;
        st.pushCursor = t0;
        st.save(ctx);

        // ---- 第一轮 ----
        round(ctx, client);

        Session after = SessionStore.load(ctx, sid);
        assertNotNull(after);
        assertEquals("科目不许回退（本地）", cid, after.subjectId);
        assertEquals("科目必须落到服务端（否则下轮 pull 回灌 NULL）",
                cid, server.arts.get(sid).categoryId);

        // ---- 第二轮（双击的第二次点击）----
        int[] r2 = round(ctx, client);
        assertEquals("第二轮上行必须为 0", 0, r2[0]);
        assertEquals("第二轮下行必须为 0", 0, r2[1]);
        assertEquals("科目不许回退（第二轮后）", cid,
                SessionStore.load(ctx, sid).subjectId);
    }

    /**
     * 引用完整性（服务端 contract：{@code a_setCategory → categoryExists 找不到就静默置 NULL，
     * accepted:true 不报错）：本轮上行的会话引用的科目，哪怕它的 updated 落在 pushCursor
     * 水位之下也必须同批带上，否则服务端把该会话打回未分组 → 下一轮 pull 回灌本地。
     * 水位超前科目的 updated，模拟「水位已被别的时间戳抬高」的现实（换账号全量交换 /
     * 服务端时钟超前都做得到）。
     */
    @Test
    public void 会话引用的科目必须同批上行_否则服务端静默回未分组() throws Exception {
        FakeContext ctx = freshCtx("refcat");
        FakeServer server = new FakeServer();
        FakeClient client = new FakeClient(server);

        long now = System.currentTimeMillis();
        String sid = "20261010-000000005";
        JSONObject cat = SubjectsStore.create(ctx, "cn阶测一");
        assertNotNull(cat);
        String cid = cat.getString("id");

        Session s = new Session();
        s.id = sid;
        s.title = "入科会话";
        s.created = now;
        s.updated = now;
        s.touched = now + 120_000; // 比水位新 → 必须上行
        s.subjectId = cid;
        s.status = "done";
        SessionStore.saveQuiet(ctx, s);

        SporeSyncState st = SporeSyncState.load(ctx);
        st.pair(ctx, "http://127.0.0.1:1/api", "probe", "probe");
        st.uid = server.uid;
        st.pullCursor = 0;
        st.pushCursor = now + 60_000; // 水位已越过科目的 updated(now)
        st.save(ctx);

        pushPhase(ctx, client, st);

        FakeServer.Art onServer = server.arts.get(sid);
        assertNotNull("会话必须上行", onServer);
        assertTrue("科目必须同批落到服务端", server.cats.containsKey(cid));
        assertEquals("服务端必须记住科目归属（否则静默置 NULL → 下轮回灌未分组）",
                cid, onServer.categoryId);
    }

    // ============================================================ 片2：单轮幂等基线

    /**
     * 下行幂等：pull 套用服务端新行后，再 pull 必须 0 变更（游标已过）。
     * 对照组（应一直绿）。
     */
    @Test
    public void 单轮幂等_pull套用后再pull_0变更() throws Exception {
        FakeContext ctx = freshCtx("pull-idem");
        FakeServer server = new FakeServer();
        FakeClient client = new FakeClient(server);

        long t0 = System.currentTimeMillis() - 60_000;
        String sid = "20261010-000000001";
        SessionStore.saveQuiet(ctx, imageSession(sid, "旧标题", t0, null));

        FakeServer.Art newer = new FakeServer.Art();
        newer.id = sid;
        newer.userId = server.uid;
        newer.updated = t0 + 1000;
        newer.title = "PC 改过的新标题";
        server.arts.put(sid, newer);

        SporeSyncState st = SporeSyncState.load(ctx);
        st.pair(ctx, "http://127.0.0.1:1/api", "probe", "probe");
        st.uid = server.uid;
        st.pullCursor = t0;
        st.pushCursor = t0;
        st.save(ctx);

        int down1 = pullPhase(ctx, client, st);
        assertEquals("服务端新行必须套用", 1, down1);
        st.save(ctx);

        int down2 = pullPhase(ctx, client, st);
        assertEquals("套用后再 pull 必须 0 变更", 0, down2);
    }

    /**
     * 上行幂等：下行刚套用过的行**不是本地改动**，不许再推上去
     * （现状：pushCursor 只按「本轮真实上行过的 touched」前进，套用行的 touched =
     * 服务端 updated > pushCursor → 第二轮照样上行 → 服务端又抬 update_time → 回声不息）。
     */
    @Test
    public void 单轮幂等_下行套用过的行不再上行() throws Exception {
        FakeContext ctx = freshCtx("push-idem");
        FakeServer server = new FakeServer();
        FakeClient client = new FakeClient(server);

        long t0 = System.currentTimeMillis() - 60_000;
        String sid = "20261010-000000002";
        SessionStore.saveQuiet(ctx, imageSession(sid, "旧标题", t0, null));

        FakeServer.Art newer = new FakeServer.Art();
        newer.id = sid;
        newer.userId = server.uid;
        newer.updated = t0 + 1000;
        newer.title = "PC 改过的新标题";
        server.arts.put(sid, newer);

        SporeSyncState st = SporeSyncState.load(ctx);
        st.pair(ctx, "http://127.0.0.1:1/api", "probe", "probe");
        st.uid = server.uid;
        st.pullCursor = t0;
        st.pushCursor = t0;
        st.save(ctx);

        assertEquals(1, pullPhase(ctx, client, st)); // 套用：本地 = 服务端
        st.save(ctx);

        int up = pushPhase(ctx, client, st);
        assertEquals("套用过的行不许当本地改动重推", 0, up);
    }

    // ============================================================ 片3：并发/连点去重

    /**
     * 双击去重：上一轮刚跑完且两端 0 变化 → 紧接着的第二次点击不许再发请求（只跑一轮）。
     * 现状：第二次 run() 照样走完整一轮（pull 请求照发）。
     */
    @Test
    public void 双击连点_第二轮不重复发请求() throws Exception {
        FakeContext ctx = freshCtx("dedup");
        FakeServer server = new FakeServer();
        FakeClient client = new FakeClient(server);

        long t0 = System.currentTimeMillis() - 60_000;
        String sid = "20261010-000000003";
        SessionStore.saveQuiet(ctx, imageSession(sid, "已同步的会话", t0, null));
        server.arts.put(sid, art(server, sid, t0, "已同步的会话"));

        SporeSyncState st = SporeSyncState.load(ctx);
        st.pair(ctx, "http://127.0.0.1:1/api", "probe", "probe");
        st.uid = server.uid;
        st.pullCursor = t0;
        st.pushCursor = t0;
        st.save(ctx);

        SyncEngine.CLIENT_FACTORY = (api, tok) -> client;
        try {
            SyncEngine.Result r1 = SyncEngine.run(ctx);
            assertTrue("第一轮应成功: " + r1.message, r1.ok);
            int pushes = server.pushCalls;
            int pulls = server.pullCalls;

            SyncEngine.Result r2 = SyncEngine.run(ctx); // 双击的第二次
            assertTrue("第二轮应直接短路: " + r2.message, r2.ok);
            assertEquals("双击不许再发 push", pushes, server.pushCalls);
            assertEquals("双击不许再发 pull", pulls, server.pullCalls);
        } finally {
            SyncEngine.CLIENT_FACTORY = SyncClient::new;
        }
    }

    /**
     * 在飞单飞：第一轮还在跑（卡在 pull 里）时并发触发第二轮 → 只跑一轮，
     * 第二轮必须被在飞标记挡下（对照组，HEAD 上即应绿）。
     */
    @Test
    public void 并发触发_在飞标记挡住第二轮() throws Exception {
        FakeContext ctx = freshCtx("singleflight");
        FakeServer server = new FakeServer();
        FakeClient client = new FakeClient(server);
        client.blockInPull = true;

        long t0 = System.currentTimeMillis() - 60_000;
        String sid = "20261010-000000004";
        SessionStore.saveQuiet(ctx, imageSession(sid, "并发会话", t0, null));
        server.arts.put(sid, art(server, sid, t0, "并发会话"));

        SporeSyncState st = SporeSyncState.load(ctx);
        st.pair(ctx, "http://127.0.0.1:1/api", "probe", "probe");
        st.uid = server.uid;
        st.pullCursor = t0;
        st.pushCursor = t0;
        st.save(ctx);

        SyncEngine.CLIENT_FACTORY = (api, tok) -> client;
        try {
            final SyncEngine.Result[] second = new SyncEngine.Result[1];
            Thread t1 = new Thread(() -> SyncEngine.run(ctx), "round-1");
            t1.start();
            Thread.sleep(120); // 确保 t1 已进飞
            Thread t2 = new Thread(() -> second[0] = SyncEngine.run(ctx), "round-2");
            t2.start();
            t1.join(5000);
            t2.join(5000);
            assertNotNull(second[0]);
            assertEquals("第二轮必须被在飞标记挡下", "同步进行中", second[0].message);
        } finally {
            SyncEngine.CLIENT_FACTORY = SyncClient::new;
        }
    }
}
