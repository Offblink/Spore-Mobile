package org.offblink.spore;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 应用设置（桌面 spore.settings 的移动版落点之一）。
 * 扁平键值存 SharedPreferences；默认值逐字对齐桌面 store.js 的 DEFAULT_SETTINGS。
 * 红线：apiKey 只活在本机 prefs，绝不进日志/仓库（handoff §7）。
 */
public final class SporeSettings {

    private static final String PREFS = "spore_settings";

    public String endpoint = "https://api.deepseek.com/chat/completions";
    public String model = "deepseek-v4-flash-vision-exp";
    public String apiKey = "";
    public int maxToolRounds = 5;
    public int historyLimit = 10;
    public boolean fastNoThink = true;
    public boolean autoVerify = true;
    /** 检索代理（可选）：填了 = 引擎链 ddg→bing→brave；留空 = 只走 bing（Fungi §71 替身闸） */
    public String proxy = "";
    /** 主题档位：system（默认，跟系统）| light | dark；解析后的明暗由 resolvedTheme() 给 */
    public String theme = "system";

    private SporeSettings() {
    }

    public static SporeSettings load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.contains("apiKey")) {
            // 首启播种：构建期从 secrets.properties（gitignore）注入；之后用户改空不回填
            p.edit().putString("apiKey", BuildConfig.SPORE_API_KEY).apply();
        }
        SporeSettings s = new SporeSettings();
        s.endpoint = p.getString("endpoint", s.endpoint);
        s.model = p.getString("model", s.model);
        s.apiKey = p.getString("apiKey", s.apiKey);
        s.proxy = p.getString("proxy", s.proxy);
        s.theme = normalizeTheme(p.getString("theme", s.theme));
        s.maxToolRounds = p.getInt("maxToolRounds", s.maxToolRounds);
        s.historyLimit = p.getInt("historyLimit", s.historyLimit);
        s.fastNoThink = p.getBoolean("fastNoThink", s.fastNoThink);
        s.autoVerify = p.getBoolean("autoVerify", s.autoVerify);
        return s;
    }

    public void save(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("endpoint", endpoint)
                .putString("model", model)
                .putString("apiKey", apiKey)
                .putString("proxy", proxy)
                .putString("theme", theme)
                .putInt("maxToolRounds", maxToolRounds)
                .putInt("historyLimit", historyLimit)
                .putBoolean("fastNoThink", fastNoThink)
                .putBoolean("autoVerify", autoVerify)
                .apply();
    }

    /** web 设置页读取（本机 WebView；apiKey 对页面可见——红线同原生页：不进日志/仓库） */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("endpoint", endpoint);
            o.put("model", model);
            o.put("apiKey", apiKey);
            o.put("proxy", proxy);
            o.put("theme", theme);
            o.put("maxToolRounds", maxToolRounds);
            o.put("historyLimit", historyLimit);
            o.put("fastNoThink", fastNoThink);
            o.put("autoVerify", autoVerify);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /**
     * web 设置页保存：只覆盖 json 里出现的键，缺字段保持现值。
     * 字符串走 str() 而非 optString——Android optString 把 JSON null 变字面量 "null"
     * （LlmClient.wireStr 同款坑，第四轮反馈根因家族）。
     */
    public static void applyJson(Context c, String json) {
        SporeSettings s = load(c);
        try {
            JSONObject o = new JSONObject(json);
            s.endpoint = str(o, "endpoint", s.endpoint);
            s.model = str(o, "model", s.model);
            s.apiKey = str(o, "apiKey", s.apiKey);
            s.proxy = str(o, "proxy", s.proxy);
            s.theme = normalizeTheme(str(o, "theme", s.theme));
            s.maxToolRounds = o.optInt("maxToolRounds", s.maxToolRounds);
            s.historyLimit = o.optInt("historyLimit", s.historyLimit);
            s.fastNoThink = o.optBoolean("fastNoThink", s.fastNoThink);
            s.autoVerify = o.optBoolean("autoVerify", s.autoVerify);
            s.save(c);
        } catch (JSONException ignored) {
            // 脏入参不落盘
        }
    }

    // ---------------------------------------------------------------- 主题

    /** 只认三档；脏值一律回 system（prefs / JS 入参都过这道闸） */
    public static String normalizeTheme(String t) {
        return "light".equals(t) || "dark".equals(t) ? t : "system";
    }

    /** 系统当前明暗（AppCompatDelegate 的档位会改写应用 Configuration，跟随档时它就是答案） */
    public static String systemTheme(Context c) {
        int mode = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES ? "dark" : "light";
    }

    /**
     * 非 Activity 的 Context（Service / 悬浮窗 View）专用：AppCompatDelegate 只改 Activity 的
     * Resources，Service 的 getColor 可能仍按系统 uiMode 取色 → 手动档位在悬浮球/框选上失效。
     * 这里把 prefs 里的解析值强制塞回 Configuration，再 createConfigurationContext。
     */
    public static Context themedContext(Context c) {
        String t = load(c).resolvedTheme(c);
        Configuration cfg = new Configuration(c.getResources().getConfiguration());
        int night = "dark".equals(t)
                ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return c.createConfigurationContext(cfg);
    }

    /** 页面/原生面板要的最终明暗（桥 Spore.theme() 的返回值） */
    public String resolvedTheme(Context c) {
        return "light".equals(theme) || "dark".equals(theme) ? theme : systemTheme(c);
    }

    private static String str(JSONObject o, String key, String def) {
        Object v = o.opt(key);
        return v instanceof String ? (String) v : def;
    }

    /** 端点与 key 都填了才可发起作答；缺配置时面板给引导而不是抛异常 */
    public boolean isConfigured() {
        return !apiKey.trim().isEmpty() && !endpoint.trim().isEmpty();
    }
}
