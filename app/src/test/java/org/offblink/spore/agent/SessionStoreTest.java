package org.offblink.spore.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 科目功能的数据契约回归钉（kit design/03 §一/§三）：
 * 老会话 JSON 向后兼容（无 subjectId 键 → null，不是字面量 "null"）+ 科目名清洗口径。
 */
public class SessionStoreTest {

    @Test
    public void 老会话无subjectId键时读为null() throws Exception {
        JSONObject old = new JSONObject(
                "{\"id\":\"20260930-120000000\",\"title\":\"新会话\",\"fav\":true}");
        Session s = SessionStore.fromJson(old);
        assertNull(s.subjectId);
    }

    @Test
    public void subjectId写读往返() throws Exception {
        Session s = new Session();
        s.subjectId = "20261002-153012";
        JSONObject out = SessionStore.toJson(s);
        assertEquals("20261002-153012", SessionStore.fromJson(out).subjectId);
    }

    @Test
    public void subjectId为null时键缺席而非写null字面量() throws Exception {
        Session s = new Session();
        s.subjectId = null;
        JSONObject out = SessionStore.toJson(s);
        assertFalse(out.has("subjectId"));
        // 即便外部塞进 JSON null，读端也不许吐出 "null" 字符串（org.json optString 陷阱）
        out.put("subjectId", JSONObject.NULL);
        assertNull(SessionStore.fromJson(out).subjectId);
    }

    @Test
    public void 科目名清洗trim与截断() {
        assertEquals("数学", SubjectsStore.cleanName("  数学  "));
        assertEquals("数学", SubjectsStore.cleanName("\u3000数学\u3000")); // 全角空格
        assertNull(SubjectsStore.cleanName("   "));
        assertNull(SubjectsStore.cleanName(""));
        assertNull(SubjectsStore.cleanName(null));
        String long40 = SubjectsStore.cleanName("一".repeat(45));
        assertNotNull(long40);
        assertEquals(40, long40.length());
    }

    // ---------------------------------------------------------------- metaOf（元数据行契约）

    /** metaOf 字段映射：id/title/created/updated/fav/subjectId 逐字段对上，msgCount = 消息条数 */
    @Test
    public void metaOfMapsHeaderFieldsAndMsgCount() throws Exception {
        Session s = new Session();
        s.id = "20261025-120000000";
        s.title = "数学";
        s.created = 100;
        s.updated = 200;
        s.fav = true;
        s.subjectId = "20261002-153012";
        s.status = "done";
        s.messages.add(new Session.Msg());
        s.messages.add(new Session.Msg());
        SessionStore.Meta m = SessionStore.metaOf(s);
        assertEquals("20261025-120000000", m.id);
        assertEquals("数学", m.title);
        assertEquals(100, m.created);
        assertEquals(200, m.updated);
        assertTrue(m.fav);
        assertEquals("20261002-153012", m.subjectId);
        assertEquals("done", m.status);
        assertEquals(2, m.msgCount);
    }

    /** metaOf 状态归一：进行中/空状态一律按 done（与 fromJson 同一口径，缓存路径不吐旧值） */
    @Test
    public void metaOfNormalizesInFlightStatusToDone() {
        for (String raw : new String[] {"answering", "verifying", "searching", ""}) {
            Session s = new Session();
            s.status = raw;
            assertEquals("done", SessionStore.metaOf(s).status);
        }
        Session s = new Session();
        s.status = "aborted";
        assertEquals("aborted", SessionStore.metaOf(s).status); // 非进行中原样保留
    }

    /** metaOf 未分组：subjectId null → Meta.subjectId 也是 null（不吐空串/"null"） */
    @Test
    public void metaOfKeepsNullSubjectId() {
        Session s = new Session();
        s.subjectId = null;
        assertNull(SessionStore.metaOf(s).subjectId);
    }

    // ---------------------------------------------------------------- Gson 层（SessionJson）

    /** 默认 Msg 的 wire 形状：18 键里只有 null 的 role/imagePath 缺席，其余全写（含空串与 false） */
    @Test
    public void 消息wire形状_默认Msg写全键且null键缺席() throws Exception {
        Session s = new Session();
        s.messages.add(new Session.Msg());
        JSONArray msgs = SessionStore.toJson(s).getJSONArray("messages");
        JSONObject row = msgs.getJSONObject(0);
        assertFalse(row.has("role"));       // null → 键缺席（旧 put(key,null) 同形）
        assertFalse(row.has("imagePath"));
        assertEquals("", row.getString("kind"));
        assertEquals("", row.getString("text"));
        assertEquals("", row.getString("think"));
        assertEquals("", row.getString("no"));
        assertEquals("", row.getString("title"));
        assertEquals("", row.getString("ans"));
        assertEquals("", row.getString("why"));
        assertFalse(row.getBoolean("hasImage"));
        assertEquals(0L, row.getLong("ts"));
        assertFalse(row.getBoolean("verifyRan"));
        assertFalse(row.getBoolean("verifySkipped"));
        assertFalse(row.getBoolean("verifyPending"));
        assertEquals("", row.getString("verifyVerdict"));
        assertEquals("", row.getString("verifyNote"));
        assertEquals("", row.getString("verifyThink"));
        assertEquals(0, row.getJSONArray("tools").length()); // 恒写数组
    }

    /** 完整会话往返：头字段 + 消息 18 字段逐项一致；UI 瞬态不落盘 */
    @Test
    public void 完整会话写读往返_逐字段一致() throws Exception {
        Session s = new Session();
        s.id = "20261123-101500123";
        s.created = 1000;
        s.updated = 2000;
        s.touched = 3000;
        s.fav = true;
        s.subjectId = "20261002-153012";
        s.title = "二次函数";
        s.status = "done";
        Session.Msg m = new Session.Msg();
        m.role = "assistant";
        m.kind = "answer";
        m.text = "答案 <div>&";
        m.think = "思考";
        m.no = "7";
        m.title = "题";
        m.ans = "A";
        m.why = "因为";
        m.hasImage = true;
        m.imagePath = "/p.jpg";
        m.ts = 12345;
        m.verifyRan = true;
        m.verifyPending = true;
        m.verifyVerdict = "v";
        m.verifyNote = "n";
        m.verifyThink = "vt";
        m.verifyStreamNote = "流式瞬态";   // 不许落盘
        m.thinkOpen = true;                // 不许落盘
        m.tools.add("检索 x");
        m.tools.add("检索 y");
        s.messages.add(m);

        String raw = SessionJson.encode(s);
        // HTML 安全模式必须关：markdown 里的 < > & 不许被写成 \u003c（否则文件体积与旧版不同形）
        assertTrue(raw.contains("<div>&"));
        assertFalse(raw.contains("verifyStreamNote"));
        assertFalse(raw.contains("thinkOpen"));

        Session b = SessionStore.fromJson(SessionStore.toJson(s));
        assertEquals(s.id, b.id);
        assertEquals(1000L, b.created);
        assertEquals(2000L, b.updated);
        assertEquals(3000L, b.touched);
        assertTrue(b.fav);
        assertEquals("20261002-153012", b.subjectId);
        assertEquals("二次函数", b.title);
        assertEquals("done", b.status);
        assertEquals(1, b.messages.size());
        Session.Msg bm = b.messages.get(0);
        assertEquals("assistant", bm.role);
        assertEquals("answer", bm.kind);
        assertEquals("答案 <div>&", bm.text);
        assertEquals("思考", bm.think);
        assertEquals("7", bm.no);
        assertEquals("题", bm.title);
        assertEquals("A", bm.ans);
        assertEquals("因为", bm.why);
        assertTrue(bm.hasImage);
        assertEquals("/p.jpg", bm.imagePath);
        assertEquals(12345L, bm.ts);
        assertTrue(bm.verifyRan);
        assertTrue(bm.verifyPending);
        assertEquals("v", bm.verifyVerdict);
        assertEquals("n", bm.verifyNote);
        assertEquals("vt", bm.verifyThink);
        assertEquals(2, bm.tools.size());
        assertEquals("检索 y", bm.tools.get(1));
    }

    /** 缺键回落默认：role→user、其余空串/false/0（旧 optString(key, 默认) 逐字段口径） */
    @Test
    public void 缺键回落到Msg构造默认() {
        Session s = SessionJson.decode("{\"messages\":[{}]}");
        Session.Msg m = s.messages.get(0);
        assertEquals("user", m.role);
        assertEquals("", m.kind);
        assertEquals("", m.text);
        assertFalse(m.hasImage);
        assertEquals(0L, m.ts);
        assertTrue(m.tools.isEmpty());
    }

    /** messages 里的非对象行单条跳过，不拖垮整会话（原 org.json 口径，Gson 反射会整档失败） */
    @Test
    public void 消息数组里的非对象行跳过() {
        Session s = SessionJson.decode(
                "{\"id\":\"x\",\"messages\":[1,\"bad\",null,{\"role\":\"user\",\"text\":\"ok\"}]}");
        assertEquals(1, s.messages.size());
        assertEquals("ok", s.messages.get(0).text);
    }

    /** 宽容解析：org.json 能读的老数据（单引号等），Gson 侧不许变得更严格 */
    @Test
    public void 宽容解析_单引号老数据仍可读() {
        Session s = SessionJson.decode("{'id':'20260930-120000000','title':'老数据'}");
        assertEquals("20260930-120000000", s.id);
        assertEquals("老数据", s.title);
    }

    /** 空串 subjectId 与 JSON null 同归 null（三态收敛，别把 "" 漏进同步层） */
    @Test
    public void subjectId为空串归一为null() throws Exception {
        assertNull(SessionStore.fromJson(new JSONObject("{\"subjectId\":\"\"}")).subjectId);
    }

    /** 进行中状态读回即归一为 done（与 metaOf 同一口径） */
    @Test
    public void 进行中状态读回归一为done() throws Exception {
        assertEquals("done",
                SessionStore.fromJson(new JSONObject("{\"status\":\"answering\"}")).status);
    }
}
