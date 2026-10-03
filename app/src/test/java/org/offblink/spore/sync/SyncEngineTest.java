package org.offblink.spore.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.offblink.spore.agent.Session;
import org.offblink.spore.agent.SessionStore;

/**
 * 同步纯逻辑契约钉（协议 = Spore-GUI SyncController/SyncServiceImpl）：
 * 下行套用（LWW 判据、JSON null 剥离、fav 数字、时间戳对齐）、
 * 上行条目（touched 当 LWW 键、未分组的 "" 编码、三态、墓碑形状）。
 */
public class SyncEngineTest {

    /** 服务端 articlePayload 形状的一行（含 Jackson 风格的 null 键） */
    private static JSONObject serverRow() throws Exception {
        JSONObject m0 = new JSONObject();
        m0.put("role", "user");
        m0.put("kind", JSONObject.NULL);
        m0.put("text", "这道题选什么");
        m0.put("think", JSONObject.NULL);
        m0.put("hasImage", true);
        m0.put("imagePath", "/storage/emulated/0/pc/cap_1.jpg");
        m0.put("ts", 1727769000000L);
        m0.put("tools", new JSONArray());

        JSONObject m1 = new JSONObject();
        m1.put("role", "assistant");
        m1.put("kind", "answer");
        m1.put("ans", "B");
        m1.put("think", "");
        m1.put("verifyVerdict", JSONObject.NULL);
        m1.put("tools", new JSONArray("[\"bing\"]"));

        JSONArray msgs = new JSONArray();
        msgs.put(m0);
        msgs.put(m1);

        JSONObject row = new JSONObject();
        row.put("id", "20261001-120000123");
        row.put("categoryId", "20261002-090000456");
        row.put("title", "数学专题");
        row.put("messages", msgs);
        row.put("fav", 1); // 服务端发数字 0/1
        row.put("status", "done");
        row.put("auditStatus", 2);
        row.put("attachmentPath", JSONObject.NULL);
        row.put("created", 1727768000000L);
        row.put("updated", 1727770000123L);
        row.put("deleted", 0);
        return row;
    }

    // ------------------------------------------------------------- 下行

    @Test
    public void 服务端行套用_字段与时间戳对齐服务端() throws Exception {
        Session s = SyncEngine.mergeFromServer(serverRow());
        assertNotNull(s);
        assertEquals("20261001-120000123", s.id);
        assertEquals("数学专题", s.title);
        assertTrue(s.fav); // 数字 1 → true
        assertEquals("20261002-090000456", s.subjectId);
        assertEquals("done", s.status);
        assertEquals(1727768000000L, s.created);
        // updated/touched 都对齐服务端：否则本地落回旧值，下一轮又被当新改动重推
        assertEquals(1727770000123L, s.updated);
        assertEquals(1727770000123L, s.touched);
        assertEquals(2, s.messages.size());
        assertEquals("这道题选什么", s.messages.get(0).text);
        assertEquals("B", s.messages.get(1).ans);
        assertTrue(s.messages.get(0).hasImage);
    }

    @Test
    public void 服务端null键不产字面量null() throws Exception {
        Session s = SyncEngine.mergeFromServer(serverRow());
        assertNotNull(s);
        // Android optString(JSON null) = "null" 字面量的坑：stripNulls 必须先剥
        assertEquals("", s.messages.get(0).kind);
        assertEquals("", s.messages.get(0).think);
        assertEquals("", s.messages.get(1).verifyVerdict);
    }

    @Test
    public void categoryId为null时回未分组() throws Exception {
        JSONObject row = serverRow();
        row.put("categoryId", JSONObject.NULL);
        Session s = SyncEngine.mergeFromServer(row);
        assertNotNull(s);
        assertNull(s.subjectId);
    }

    @Test
    public void 未分组行_上行categoryId编码为空串() throws Exception {
        Session s = new Session();
        s.subjectId = null;
        JSONObject item = SyncEngine.articleItem(s);
        // null 会被服务端 update 路径跳过（清不掉引用）——"" 才是「清引用」的唯一编码
        assertEquals("", item.getString("categoryId"));
        s.subjectId = "20261002-090000456";
        assertEquals("20261002-090000456",
                SyncEngine.articleItem(s).getString("categoryId"));
    }

    @Test
    public void LWW判据_回声不覆盖_本地新不覆盖_服务端新才套用() {
        assertTrue(SyncEngine.serverWins(200L, 100L));
        assertFalse(SyncEngine.serverWins(100L, 100L)); // 相等 = 自己刚推的回声
        assertFalse(SyncEngine.serverWins(50L, 100L));  // 本地新（含只抬 touched 的改名收藏）
    }

    @Test
    public void 老会话无touched键回落地updated() throws Exception {
        JSONObject old = new JSONObject(
                "{\"id\":\"20260930-120000000\",\"updated\":1727700000000}");
        Session s = SessionStore.fromJson(old);
        assertEquals(1727700000000L, s.touched);
    }

    @Test
    public void stripNulls_递归剥键_数组内同步剥() throws Exception {
        JSONObject nested = new JSONObject();
        nested.put("a", JSONObject.NULL);
        nested.put("b", "x");
        JSONObject arrItem = new JSONObject();
        arrItem.put("k", JSONObject.NULL);
        arrItem.put("v", 3);
        JSONArray arr = new JSONArray();
        arr.put(arrItem);
        arr.put(JSONObject.NULL); // null 元素丢弃
        arr.put("s");

        JSONObject in = new JSONObject();
        in.put("top", JSONObject.NULL);
        in.put("keep", 1);
        in.put("obj", nested);
        in.put("arr", arr);

        JSONObject out = SyncEngine.stripNulls(in);
        assertFalse(out.has("top"));
        assertEquals(1, out.getInt("keep"));
        assertFalse(out.getJSONObject("obj").has("a"));
        assertEquals("x", out.getJSONObject("obj").getString("b"));
        JSONArray oarr = out.getJSONArray("arr");
        assertEquals(2, oarr.length());
        assertFalse(oarr.getJSONObject(0).has("k"));
        assertEquals(3, oarr.getJSONObject(0).getInt("v"));
        assertEquals("s", oarr.getString(1));
    }

    // ------------------------------------------------------------- 上行

    @Test
    public void 上行条目_元数据改动靠touched当LWW键() throws Exception {
        Session s = new Session();
        s.id = "20261001-100000000";
        s.updated = 1000L;      // 第九轮：改名/收藏不抬 updated（列表不跳位）
        s.touched = 2500L;      // 但 touched 抬了 —— 同步必须看得见
        s.fav = true;
        s.status = "done";
        JSONObject item = SyncEngine.articleItem(s);
        assertEquals(2500L, item.getLong("updated"));
        assertEquals(1, item.getInt("fav"));
        assertEquals(0, item.getInt("deleted"));
        assertNotNull(item.getJSONArray("messages"));
        assertTrue(item.has("created"));
    }

    @Test
    public void 上行状态只推三态_在途与空态归done() {
        assertEquals("done", SyncEngine.normalizeStatus("answering"));
        assertEquals("done", SyncEngine.normalizeStatus("verifying"));
        assertEquals("done", SyncEngine.normalizeStatus("searching"));
        assertEquals("done", SyncEngine.normalizeStatus(""));
        assertEquals("done", SyncEngine.normalizeStatus("done"));
        assertEquals("error", SyncEngine.normalizeStatus("error"));
        assertEquals("aborted", SyncEngine.normalizeStatus("aborted"));
    }

    @Test
    public void 墓碑条目只带三键_不动存量字段() throws Exception {
        JSONObject item = SyncEngine.tombstoneItem("20261001-100000000", 987654L);
        assertEquals("20261001-100000000", item.getString("id"));
        assertEquals(987654L, item.getLong("updated"));
        assertEquals(1, item.getInt("deleted"));
        assertFalse(item.has("title"));     // null 字段服务端逐个跳过：只翻墓碑位
        assertFalse(item.has("messages"));
        assertFalse(item.has("categoryId"));
    }

    @Test
    public void 科目条目保留多级形() throws Exception {
        JSONObject row = new JSONObject();
        row.put("id", "cat-1");
        row.put("name", "数学");
        row.put("parentId", "cat-0");
        row.put("sortOrder", 2);
        row.put("status", 0);
        row.put("created", 111L);
        row.put("updated", 222L);
        JSONObject item = SyncEngine.categoryItem(row);
        assertEquals("cat-0", item.getString("parentId"));
        assertEquals(0, item.getInt("status"));
        assertEquals(222L, item.getLong("updated"));
        assertEquals(0, item.getInt("deleted"));

        row.put("parentId", JSONObject.NULL);
        assertTrue(SyncEngine.categoryItem(row).isNull("parentId"));
    }
}
