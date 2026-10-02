package org.offblink.spore.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;

import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

/**
 * 科目数据层桩烟测：假 Context（覆写 getFilesDir → 临时目录）真跑 subjects.json 的
 * create/rename/remove/exists 全链 + SessionStore.clearSubject 级联清引用。
 * 覆盖 kit design/03 §三 的两条硬契约：删科目后会话回未分组、名字清洗 1–40 字。
 */
public class SubjectsStoreTest {

    private File dir;
    private Context ctx;

    @Before
    public void setUp() {
        dir = new File(System.getProperty("java.io.tmpdir"),
                "subj-test-" + System.nanoTime());
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        ctx = new ContextWrapper(null) {
            @Override
            public File getFilesDir() {
                return dir;
            }
        };
    }

    @Test
    public void 新建改名删科目全链() throws Exception {
        JSONArray before = SubjectsStore.metaJson(ctx);
        assertEquals(0, before.length());

        org.json.JSONObject math = SubjectsStore.create(ctx, " 数学 ");
        assertNotNull(math);
        assertEquals("数学", math.optString("name"));
        assertEquals(0, math.optInt("sortOrder"));
        assertEquals(1, math.optInt("status"));
        assertTrue(SubjectsStore.exists(ctx, math.optString("id")));

        org.json.JSONObject eng = SubjectsStore.create(ctx, "英语");
        assertNotNull(eng);
        assertEquals(1, eng.optInt("sortOrder"));
        assertFalse(math.optString("id").equals(eng.optString("id")));
        assertEquals(2, SubjectsStore.metaJson(ctx).length());

        assertTrue(SubjectsStore.rename(ctx, math.optString("id"), "高等数学"));
        assertEquals("高等数学", SubjectsStore.metaJson(ctx)
                .getJSONObject(0).optString("name"));

        assertFalse(SubjectsStore.remove(ctx, "不存在的id"));
        assertTrue(SubjectsStore.remove(ctx, math.optString("id")));
        assertFalse(SubjectsStore.exists(ctx, math.optString("id")));
        assertEquals(1, SubjectsStore.metaJson(ctx).length());
    }

    @Test
    public void 空名不建科目() {
        assertNull(SubjectsStore.create(ctx, "   "));
        assertNull(SubjectsStore.create(ctx, null));
        assertEquals(0, SubjectsStore.metaJson(ctx).length());
    }

    @Test
    public void 删科目后成员会话回未分组() {
        org.json.JSONObject sub = SubjectsStore.create(ctx, "数学");
        assertNotNull(sub);
        String sid = sub.optString("id");

        Session inGroup = new Session();
        inGroup.subjectId = sid;
        SessionStore.save(ctx, inGroup);
        Session alone = new Session();
        // 同毫秒 new Session() 会撞 newId()（同文件覆盖）——夹具显式给 id，别赌时间精度
        alone.id = inGroup.id + "-b";
        alone.subjectId = "别的科目";
        SessionStore.save(ctx, alone);

        // 引用清扫（CaptureService.deleteSubject 的第一段；引擎路径同此语义）
        SessionStore.clearSubject(ctx, sid, null);

        assertNull(SessionStore.load(ctx, inGroup.id).subjectId);
        assertEquals("别的科目", SessionStore.load(ctx, alone.id).subjectId);
        assertTrue(SubjectsStore.remove(ctx, sid));
    }

    @Test
    public void 活会话被排除在磁盘扫尾之外() {
        org.json.JSONObject sub = SubjectsStore.create(ctx, "数学");
        assertNotNull(sub);
        String sid = sub.optString("id");

        Session live = new Session();
        live.subjectId = sid;
        SessionStore.save(ctx, live);

        SessionStore.clearSubject(ctx, sid, java.util.Collections.singleton(live.id));
        // 被排除 → 磁盘文件不许动（引擎内存态会自己清并落盘）
        assertEquals(sid, SessionStore.load(ctx, live.id).subjectId);

        SessionStore.clearSubject(ctx, sid, java.util.Collections.emptySet());
        assertNull(SessionStore.load(ctx, live.id).subjectId);
    }
}
