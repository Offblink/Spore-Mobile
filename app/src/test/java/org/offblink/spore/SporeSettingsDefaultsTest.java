package org.offblink.spore;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Constructor;

/**
 * AI 建议框开关的默认值契约（Round 23，2026-10-09）。
 *
 * 三端（MV3 / GUI / Mobile）拍板「都默认关闭」——默认关是硬契约：
 * 关着时 Mobile 不建 ML Kit 识别器、不跑 OCR，直接进框选（CaptureService 的分流）。
 * 构造器是 private（本项目无 Robolectric、拿不到 Context 走 load()），
 * 这里用反射取一份「首启未播种」的实例钉默认值；toJson 同时钉住 web 设置页回填的键。
 */
public class SporeSettingsDefaultsTest {

    private static SporeSettings fresh() throws Exception {
        Constructor<SporeSettings> c = SporeSettings.class.getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }

    @Test
    public void mlSuggest_defaults_to_off() throws Exception {
        assertFalse("AI 建议框必须默认关（三端一致）", fresh().mlSuggest);
    }

    @Test
    public void mlSuggest_exported_to_json_as_false() throws Exception {
        JSONObject o = fresh().toJson();
        assertTrue("toJson 必须带上 mlSuggest（web 设置页靠它回填）", o.has("mlSuggest"));
        assertFalse("导出的默认值必须是 false", o.getBoolean("mlSuggest"));
    }
}
