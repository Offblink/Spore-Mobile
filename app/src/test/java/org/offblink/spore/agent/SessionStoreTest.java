package org.offblink.spore.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

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
}
