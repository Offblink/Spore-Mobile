package org.offblink.spore;

import android.content.Context;
import android.content.SharedPreferences;

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

    private SporeSettings() {
    }

    public static SporeSettings load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SporeSettings s = new SporeSettings();
        s.endpoint = p.getString("endpoint", s.endpoint);
        s.model = p.getString("model", s.model);
        s.apiKey = p.getString("apiKey", s.apiKey);
        s.proxy = p.getString("proxy", s.proxy);
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
                .putInt("maxToolRounds", maxToolRounds)
                .putInt("historyLimit", historyLimit)
                .putBoolean("fastNoThink", fastNoThink)
                .putBoolean("autoVerify", autoVerify)
                .apply();
    }

    /** 端点与 key 都填了才可发起作答；缺配置时面板给引导而不是抛异常 */
    public boolean isConfigured() {
        return !apiKey.trim().isEmpty() && !endpoint.trim().isEmpty();
    }
}
