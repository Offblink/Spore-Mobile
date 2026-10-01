package org.offblink.spore.llm;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 线上帧字段读取契约：非字符串一律当空。
 * Android 的 {@code optString} 会把 JSON null 变成字面量 "null"（桌面 JS 判真值天然跳过），
 * 直接用它会让核实区刷满 null、垃圾回灌模型（2026-10-01 第四轮反馈根因）。
 * 谁把 {@link LlmClient#wireStr} 改回 optString，这个测试变红。
 */
public class LlmClientTest {

    @Test
    public void json_null当缺失不当字面量null() throws Exception {
        JSONObject o = new JSONObject();
        o.put("content", JSONObject.NULL);
        assertEquals("", LlmClient.wireStr(o, "content"));
    }

    @Test
    public void 真字符串原样透传() throws Exception {
        JSONObject o = new JSONObject();
        o.put("content", "选 A。");
        assertEquals("选 A。", LlmClient.wireStr(o, "content"));
    }

    @Test
    public void 缺字段与非字符串一律空串() throws Exception {
        JSONObject o = new JSONObject();
        o.put("index", 42);
        assertEquals("", LlmClient.wireStr(o, "missing"));
        assertEquals("", LlmClient.wireStr(o, "index"));
    }
}
