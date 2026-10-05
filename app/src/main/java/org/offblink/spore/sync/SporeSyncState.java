package org.offblink.spore.sync;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.offblink.spore.SporeLog;
import org.json.JSONObject;

/**
 * 配对与同步状态（独立 prefs {@code spore_sync}，不混进 SporeSettings——
 * 那份是 LLM 引擎配置，且 toJson 会整份喂给 web 设置页）。
 *
 * <p>红线：{@code pairToken}（LAN token）只活在本机 prefs，**绝不进 toJson/日志/仓库**
 * （handoff §7 与 SporeSettings.apiKey 同口径）——web 页只看得到配对与否、主机与昵称。
 * 游标：pullCursor = 服务端增量水位（只从 pull 的 nextCursor 前进）；
 * pushCursor = 本地上行水位（只在 push 成功后按已推过的 touched 前进）——
 * 两者分开是为了一条铁律：**pull 不许碰 pushCursor**（本地未推改动会被跳过 → 静默丢数据）。
 */
public final class SporeSyncState {

    private static final String PREFS = "spore_sync";

    /** 配对主机（扫码载荷 api，形如 http://192.168.x.x:8080/api）；空 = 未配对 */
    public String api = "";
    /** LAN token（Bearer）；只落 prefs 不出 toJson */
    public String token = "";
    /** 连通校验拿到的昵称（配对页 /users/me），仅展示用 */
    public String nick = "";
    /** 服务端身份（users/me 的 id）：换账号/改绑必须双游标清零，否则旧水位永久跳过新账号的存量行 */
    public long uid = -1;
    public long pullCursor = 0;
    public long pushCursor = 0;
    /** 上次同步完成时刻；0 = 还没同步过 */
    public long lastSyncAt = 0;
    /** 上次同步结果一句话（成功摘要或错误），web 设置页直显 */
    public String lastSyncMsg = "";

    private SporeSyncState() {
    }

    public static SporeSyncState load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SporeSyncState s = new SporeSyncState();
        s.api = p.getString("api", "");
        s.token = p.getString("token", "");
        s.nick = p.getString("nick", "");
        s.uid = p.getLong("uid", -1);
        s.pullCursor = p.getLong("pullCursor", 0);
        s.pushCursor = p.getLong("pushCursor", 0);
        s.lastSyncAt = p.getLong("lastSyncAt", 0);
        s.lastSyncMsg = p.getString("lastSyncMsg", "");
        return s;
    }

    public void save(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("api", api)
                .putString("token", token)
                .putString("nick", nick)
                .putLong("uid", uid)
                .putLong("pullCursor", pullCursor)
                .putLong("pushCursor", pushCursor)
                .putLong("lastSyncAt", lastSyncAt)
                .putString("lastSyncMsg", lastSyncMsg)
                .apply();
    }

    /** 已配对 = 主机与 token 都在 */
    public boolean paired() {
        return !api.isEmpty() && !token.isEmpty();
    }

    /**
     * 配对成功落态：主机/昵称/token 覆盖，**游标清零**（换账号/重扫 = 全量交换，
     * 免得上一个配对的水位把新数据跳过）。
     */
    public void pair(Context c, String apiBase, String lanToken, String nickname) {
        this.api = apiBase;
        this.token = lanToken;
        this.nick = nickname == null ? "" : nickname;
        this.uid = -1;   // 身份待下轮同步 users/me 认领
        this.pullCursor = 0;
        this.pushCursor = 0;
        this.lastSyncAt = 0;
        this.lastSyncMsg = "";
        save(c);
        SporeLog.i(c, "配对成功 api=" + apiBase + " nick=" + this.nick);
    }

    /** 取消配对：清凭据与游标，账本（墓碑）留着——那是本地已删数据的传播义务 */
    public void unpair(Context c) {
        this.api = "";
        this.token = "";
        this.nick = "";
        this.uid = -1;   // 身份待下轮同步 users/me 认领
        this.pullCursor = 0;
        this.pushCursor = 0;
        this.lastSyncAt = 0;
        this.lastSyncMsg = "";
        save(c);
        SporeLog.i(c, "取消配对（游标已清，墓碑账本保留）");
    }

    /** web 设置页状态帧（**无 token**） */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("paired", paired());
            o.put("api", api);
            o.put("nick", nick);
            o.put("pullCursor", pullCursor);
            o.put("pushCursor", pushCursor);
            o.put("lastSyncAt", lastSyncAt);
            o.put("lastSyncMsg", lastSyncMsg);
        } catch (JSONException ignored) {
        }
        return o;
    }
}
