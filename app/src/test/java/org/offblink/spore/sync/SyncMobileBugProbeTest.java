package org.offblink.spore.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import org.junit.Test;
import org.offblink.spore.agent.AgentEngine;
import org.offblink.spore.agent.Session;
import org.offblink.spore.agent.SessionStore;
import org.offblink.spore.agent.SubjectsStore;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 同步链路三个症状的构造性探针（删除回流 / 同步不全 / 题图不显示）。
 * 每个用例钉一条真实断点：改前必须红（断言失败）、改后必须绿。
 * 不走网络：SyncClient 换假客户端、Context 换临时目录。
 */
public class SyncMobileBugProbeTest {

    // ------------------------------------------------------------- 基建

    /** 只落在临时目录的 Context；filesDir 全部读写由 SessionStore/SubjectsStore/墓碑账本共用 */
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
            throw new UnsupportedOperationException("探针不碰 prefs");
        }
    }

    /** 同步客户端替身：pull 按游标回预置页；push 回 accepted 可控的 results */
    private static final class FakeClient extends SyncClient {
        final Map<Long, JSONObject> pages = new HashMap<>();
        final List<Long> pulls = new ArrayList<>();
        final Set<String> storageFiles = new HashSet<>();
        final List<String> attachmentRequests = new ArrayList<>();
        boolean acceptAll = true;

        FakeClient() {
            super("http://127.0.0.1:1/api", "probe");
        }

        @Override
        public JSONObject pull(long cursor, int limit) {
            pulls.add(cursor);
            JSONObject page = pages.get(cursor);
            if (page != null) {
                return page;
            }
            try {
                JSONObject empty = new JSONObject();
                empty.put("categories", new JSONArray());
                empty.put("articles", new JSONArray());
                empty.put("nextCursor", cursor);
                return empty;
            } catch (JSONException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public JSONArray push(JSONObject body) {
            try {
                JSONArray results = new JSONArray();
                JSONArray cats = body.optJSONArray("categories");
                JSONArray arts = body.optJSONArray("articles");
                for (int i = 0; cats != null && i < cats.length(); i++) {
                    results.put(result(cats.getJSONObject(i).optString("id", "")));
                }
                for (int i = 0; arts != null && i < arts.length(); i++) {
                    results.put(result(arts.getJSONObject(i).optString("id", "")));
                }
                return results;
            } catch (JSONException e) {
                throw new AssertionError(e);
            }
        }

        private JSONObject result(String id) {
            try {
            JSONObject r = new JSONObject();
            r.put("id", id);
            r.put("accepted", acceptAll);
            return r;
            } catch (JSONException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public String pushAttachment(String articleId, File file) {
            return articleId + ".jpg";
        }

        @Override
        public byte[] pullAttachment(String relPath) {
            attachmentRequests.add(relPath);
            if (storageFiles.contains(relPath)) {
                return new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};
            }
            return null; // 404 → null（没图不是错误）
        }
    }

    private static FakeContext freshCtx(String name) {
        File dir = new File("C:/tmp/scratch/sync-mobile-audit/ut", name);
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

    private static SporeSyncState newState() throws Exception {
        Constructor<SporeSyncState> c = SporeSyncState.class.getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
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

    private static int applyArticleRow(Context ctx, SyncClient client, JSONObject row) throws Exception {
        Method m = SyncEngine.class.getDeclaredMethod(
                "applyArticleRow", Context.class, SyncClient.class, JSONObject.class);
        m.setAccessible(true);
        return (Integer) m.invoke(null, ctx, client, row);
    }

    private static JSONObject page(JSONArray cats, JSONArray arts, long next) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("categories", cats);
        o.put("articles", arts);
        o.put("nextCursor", next);
        return o;
    }

    // ============================================================ 片1：删除回流

    /**
     * 断点一：本地删过（账上有墓碑）→ 下行带回「活行」时按 LWW 建档复活。
     * 期望：在账墓碑压住活行（不复活），并把墓碑 ts 抬到服务端时间之上让删除最终能赢。
     */
    @Test
    public void 删除回流_在账墓碑压住下行活行_不复活() throws Exception {
        FakeContext ctx = freshCtx("resurrect");
        String id = "20260101-100000000";
        long deletedAt = System.currentTimeMillis();
        SyncTombstones.add(ctx, SyncTombstones.ARTICLE, id);
        List<SyncTombstones.Item> before = SyncTombstones.load(ctx);
        assertEquals(1, before.size());

        // 电脑端还持有/又更新过：服务端 updated 比删除时刻新
        JSONObject row = new JSONObject();
        row.put("id", id);
        row.put("title", "PC端还活着的会话");
        row.put("updated", deletedAt + 60_000);
        row.put("deleted", 0);
        row.put("messages", new JSONArray());

        int r = applyArticleRow(ctx, new FakeClient(), row);

        assertEquals("在账墓碑的活行必须跳过（0=无事），不许建档复活", 0, r);
        assertNull("本地仍须是删除态", SessionStore.load(ctx, id));
        List<SyncTombstones.Item> after = SyncTombstones.load(ctx);
        assertEquals("墓碑必须还在账上", 1, after.size());
        assertTrue("墓碑 ts 必须抬到服务端时间之上（下轮 push 才盖得过 LWW）",
                after.get(0).ts > deletedAt + 60_000);
    }

    /**
     * 断点二：push 对服务端 results[].accepted 照单全收——被 LWW 拒绝的墓碑照样销账 →
     * 删除永不重试 → 服务端行保持活 → 同一轮 pull 把活行拉回来（配合断点一即完整回流链）。
     */
    @Test
    public void 删除回流_服务端拒绝的墓碑不许销账() throws Exception {
        FakeContext ctx = freshCtx("accepted");
        String id = "20260102-100000000";
        SyncTombstones.add(ctx, SyncTombstones.ARTICLE, id);

        FakeClient client = new FakeClient();
        client.acceptAll = false; // 服务端 LWW：这行比墓碑新，拒绝
        SporeSyncState st = newState();

        int sent = pushPhase(ctx, client, st);
        assertEquals("墓碑至少要发出去", 1, sent);
        assertFalse("被拒绝的墓碑必须留账下轮重推（销账 = 删除永久丢失）",
                SyncTombstones.load(ctx).isEmpty());
    }

    /** 对照组：服务端接受的墓碑照旧销账（不许把删除永久挂在账上） */
    @Test
    public void 删除回流_服务端接受的墓碑当轮销账() throws Exception {
        FakeContext ctx = freshCtx("accepted-ok");
        SyncTombstones.add(ctx, SyncTombstones.ARTICLE, "20260103-100000000");

        FakeClient client = new FakeClient();
        client.acceptAll = true;
        pushPhase(ctx, client, newState());

        assertTrue("接受即销账（回声一次后销的既有口径）",
                SyncTombstones.load(ctx).isEmpty());
    }

    /**
     * 断点三（科目侧对称）：本地删掉的科目，下行活行会原样 append 复活。
     */
    @Test
    public void 删除回流_在账科目墓碑压住下行活科目() throws Exception {
        FakeContext ctx = freshCtx("cat-resurrect");
        String cid = "20260104-100000000";
        SyncTombstones.add(ctx, SyncTombstones.CATEGORY, cid);

        JSONObject cat = new JSONObject();
        cat.put("id", cid);
        cat.put("name", "数学");
        cat.put("parentId", JSONObject.NULL);
        cat.put("sortOrder", 0);
        cat.put("status", 1);
        cat.put("created", 1L);
        cat.put("updated", System.currentTimeMillis() + 60_000);
        cat.put("deleted", 0);

        FakeClient client = new FakeClient();
        client.pages.put(0L, page(new JSONArray().put(cat), new JSONArray(),
                System.currentTimeMillis() + 60_000));
        SporeSyncState st = newState();

        pullPhase(ctx, client, st);

        assertFalse("在账墓碑的科目活行不许复活", SubjectsStore.exists(ctx, cid));
        assertFalse("墓碑必须留账", SyncTombstones.load(ctx).isEmpty());
    }

    // ============================================================ 片1：同步不全

    /**
     * 控制组（应一直绿）：pull 必须循环拉到 nextCursor 无新行为止，游标推进到末页 nextCursor。
     */
    @Test
    public void 同步不全_pull循环拉干_游标走到末页() throws Exception {
        FakeContext ctx = freshCtx("paginate");
        FakeClient client = new FakeClient();

        JSONObject a = new JSONObject();
        a.put("id", "20260110-000000001");
        a.put("title", "A");
        a.put("updated", 100);
        a.put("deleted", 0);
        JSONObject b = new JSONObject();
        b.put("id", "20260110-000000002");
        b.put("title", "B");
        b.put("updated", 110);
        b.put("deleted", 0);
        JSONObject c = new JSONObject();
        c.put("id", "20260110-000000003");
        c.put("title", "C");
        c.put("updated", 120);
        c.put("deleted", 0);

        client.pages.put(0L, page(new JSONArray(), new JSONArray().put(a).put(b), 110));
        client.pages.put(110L, page(new JSONArray(), new JSONArray().put(c), 120));
        // cursor=120 → 无预置页 → 空页收束

        SporeSyncState st = newState();
        int applied = pullPhase(ctx, client, st);

        assertEquals("三页都要套用", 3, applied);
        assertEquals("游标走到末页 nextCursor", 120L, st.pullCursor);
        assertEquals("每轮同步要把游标拉干（0/110/120 三次）",
                List.of(0L, 110L, 120L), client.pulls);
        assertNotNull(SessionStore.load(ctx, "20260110-000000001"));
        assertNotNull(SessionStore.load(ctx, "20260110-000000002"));
        assertNotNull(SessionStore.load(ctx, "20260110-000000003"));
    }

    /**
     * 断点四：下行行里混一条畸形消息行（GUI 侧对同类畸形行是跳过的，
     * 见 Spore-GUI tests/test_session.py「畸形行」）→ 现状整会话反序列化失败、
     * 返回 0 且游标照走 → 该会话永久同步不全。期望：跳过坏行、会话照常套用。
     */
    @Test
    public void 同步不全_畸形消息行不许拖垮整个会话() throws Exception {
        FakeContext ctx = freshCtx("malformed");
        JSONObject row = new JSONObject();
        row.put("id", "20260105-100000000");
        row.put("title", "带畸形行的会话");
        row.put("updated", System.currentTimeMillis());
        row.put("deleted", 0);
        JSONArray msgs = new JSONArray();
        msgs.put("畸形行"); // 非对象行
        JSONObject good = new JSONObject();
        good.put("role", "user");
        good.put("kind", "answer");
        good.put("text", "正常消息");
        good.put("ts", 1L);
        msgs.put(good);
        row.put("messages", msgs);

        int r = applyArticleRow(ctx, new FakeClient(), row);

        assertEquals("会话必须套用（坏消息行跳过）", 1, r);
        Session s = SessionStore.load(ctx, "20260105-100000000");
        assertNotNull("会话不许被一条坏行整条丢掉（游标已过 = 永久丢）", s);
        assertEquals(1, s.messages.size());
        assertEquals("正常消息", s.messages.get(0).text);
    }

    /**
     * 断点五：下行套用到「当前正在查看的会话」时只换内存不落盘 ——
     * 游标已推进，进程一杀磁盘永远停在旧版 → 永久同步不全。
     */
    @Test
    public void 同步不全_当前查看会话下行套用必须落盘() throws Exception {
        FakeContext ctx = freshCtx("applypulled");
        AgentEngine eng = new AgentEngine(ctx);

        Session cur = new Session();
        cur.title = "旧标题";
        Field f = AgentEngine.class.getDeclaredField("session");
        f.setAccessible(true);
        f.set(eng, cur);

        Session incoming = new Session();
        incoming.id = cur.id;
        incoming.title = "PC改过的新标题";
        incoming.touched = System.currentTimeMillis();

        assertTrue("应套用到当前视图", eng.applyPulled(incoming));

        Session onDisk = SessionStore.load(ctx, cur.id);
        assertNotNull("下行套用后磁盘必须有该会话（否则游标已过、数据永久停旧版）", onDisk);
        assertEquals("PC改过的新标题", onDisk.title);
    }

    // ============================================================ 片2：题图链路

    /**
     * 断点六：GUI→手机题图。服务端 push-attachment 只存文件不回写行
     * （Spore-GUI SyncController.java 注释声称写回、代码没写；records.py 实测在案），
     * 所以 GUI 会话行里 attachmentPath 恒空，但文件已按 &lt;articleId&gt;.&lt;ext&gt; 落题库目录。
     * 手机现状：rel 空 → 直接 return → 永远不拉图 → 渲染拿 PC 绝对路径读不到文件。
     * 期望：rel 空时按 articleId 命名约定探拉，拉到写进 captures/att_<id>.jpg 并改指消息。
     */
    @Test
    public void 题图附件路径为空时按会话id兜底拉取() throws Exception {
        FakeContext ctx = freshCtx("attach");
        String id = "20260106-100000000";
        FakeClient client = new FakeClient();
        client.storageFiles.add(id + ".png"); // GUI 上传的真实落盘名（<articleId>.<ext>）

        JSONObject imgMsg = new JSONObject();
        imgMsg.put("role", "user");
        imgMsg.put("kind", "answer");
        imgMsg.put("text", "");
        imgMsg.put("hasImage", true);
        imgMsg.put("imagePath", "C:/Users/pc/Desktop/captures/shot.png"); // PC 绝对路径，手机读不到
        imgMsg.put("ts", 1L);

        JSONObject row = new JSONObject();
        row.put("id", id);
        row.put("title", "GUI截图会话");
        row.put("updated", System.currentTimeMillis());
        row.put("deleted", 0);
        row.put("messages", new JSONArray().put(imgMsg));
        // attachmentPath 故意缺席（服务端回 null 键 / 缺键）

        int r = applyArticleRow(ctx, client, row);
        assertEquals(1, r);

        File local = new File(new File(ctx.getFilesDir(), "captures"), "att_" + id + ".jpg");
        assertTrue("题图必须落到本地 captures/att_<id>.jpg", local.isFile() && local.length() > 0);
        assertTrue("必须按 <articleId>.<ext> 兜底探拉过（服务端命名约定）",
                client.attachmentRequests.contains(id + ".png"));

        Session s = SessionStore.load(ctx, id);
        assertNotNull(s);
        assertEquals("消息 imagePath 必须改指本地文件", local.getAbsolutePath(),
                s.messages.get(0).imagePath);
        assertTrue("渲染入口（record.js m.hasImage && m.imagePath）此刻拿得到真实文件",
                new File(s.messages.get(0).imagePath).isFile());
    }

    // ============================================================ 片1：换账号身份（主会话补）

    /**
     * 后端 lan_token 改绑后手机不重扫：游标还停在旧账号的水位上，
     * 新账号的存量行被 `> cursor` 永久跳过（同步不全）。身份变了必须双游标清零；
     * 升级首跑未记录身份（-1）也按变更处理；同账号反复同步不许清游标。
     */
    @Test
    public void 换账号_双游标清零_同账号不动() throws Exception {
        SporeSyncState st = newState();
        st.uid = 1;
        st.pullCursor = 111;
        st.pushCursor = 222;
        SyncEngine.adoptUid(st, 4); // 改绑 admin → 布林诺
        assertEquals(0L, st.pullCursor);
        assertEquals(0L, st.pushCursor);
        assertEquals(4L, st.uid);

        st.pullCursor = 77;
        st.pushCursor = 88;
        SyncEngine.adoptUid(st, 4); // 同账号：不许清
        assertEquals(77L, st.pullCursor);
        assertEquals(88L, st.pushCursor);

        st.uid = -1; // 升级首跑（未记录身份）→ 按变更重置，全量收敛
        st.pullCursor = 99;
        st.pushCursor = 100;
        SyncEngine.adoptUid(st, 4);
        assertEquals(0L, st.pullCursor);
        assertEquals(0L, st.pushCursor);
        assertEquals(4L, st.uid);
    }
}
