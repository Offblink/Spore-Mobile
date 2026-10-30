package org.offblink.spore.sync;

import android.content.Context;

import org.json.JSONObject;
import org.offblink.spore.SporeLog;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 「账号直连」配对：不扫码，直接对 Spore-Cloud 微服务后端登录。
 *
 * <p>扫码那条路（{@link PairActivity}）载荷来自桌面端，token 是 LAN token；这条路
 * 走 {@code POST {api}/auth/login} 换普通 JWT —— 与扫码得到的凭据同一 Bearer 通道，
 * 同步代码（{@link SyncEngine}）零改动。
 *
 * <p>地址归一：用户填 {@code 192.168.1.5:8080} / {@code http://192.168.1.5:8080}
 * 都可，末尾缺 {@code /api} 自动补上（协议约定 api base 含 /api）。
 *
 * <p>红线：token 只落本机 prefs（{@link SporeSyncState#token}），不进日志、不进 toJson。
 */
public final class ServerConnector {

    private static final MediaType JSON =
            MediaType.parse("application/json; charset=utf-8");

    private ServerConnector() {
    }

    /**
     * 登录并落配对态。阻塞调用（由宿主放在后台线程跑）。
     *
     * @return {@code {"ok":true,"api":..,"nick":..,"uid":..}} 或 {@code {"ok":false,"message":..}}
     */
    public static JSONObject connect(Context c, String rawApi, String username, String password) {
        String api = normalizeApi(rawApi);
        if (api.isEmpty()) {
            return fail("服务器地址为空");
        }
        if (username == null || username.trim().isEmpty()
                || password == null || password.isEmpty()) {
            return fail("账号与口令必填");
        }
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        try {
            JSONObject loginBody = new JSONObject();
            loginBody.put("username", username.trim());
            loginBody.put("password", password);

            JSONObject login = call(http, api + "/auth/login", null,
                    RequestBody.create(loginBody.toString(), JSON));
            String token = login.optString("token", "");
            if (token.isEmpty()) {
                return fail("登录成功但没拿到 token");
            }

            JSONObject me = call(http, api + "/users/me", token, null);
            long uid = me.optLong("id", -1);
            if (uid < 0) {
                return fail("连通校验失败：users/me 没有用户 id");
            }
            String nick = me.optString("nickname", "");
            if (nick.isEmpty()) {
                nick = me.optString("username", "服务器");
            }

            // 换服务器 = 换身份：双游标归零，下轮全量交换（否则旧水位会永久跳过新账号的存量行）
            SporeSyncState state = SporeSyncState.load(c);
            state.api = api;
            state.token = token;
            state.nick = nick;
            state.uid = uid;
            state.pullCursor = 0;
            state.pushCursor = 0;
            state.lastSyncAt = 0;
            state.lastSyncMsg = "账号直连";
            state.lastUp = -1;
            state.lastDown = -1;
            state.save(c);

            SporeLog.i(c, "sync: 账号直连成功 " + api + " uid=" + uid);

            JSONObject ok = new JSONObject();
            ok.put("ok", true);
            ok.put("api", api);
            ok.put("nick", nick);
            ok.put("uid", uid);
            return ok;
        } catch (SyncClient.SyncException e) {
            SporeLog.i(c, "sync: 账号直连失败 code=" + e.code);
            return fail(e.getMessage() == null ? "连接失败" : e.getMessage());
        } catch (IOException e) {
            SporeLog.e(c, "sync: 账号直连网络错误", e);
            return fail("连不上服务器（检查地址与同一 Wi-Fi）");
        } catch (Exception e) {
            SporeLog.e(c, "sync: 账号直连异常", e);
            return fail("连接失败：" + e.getClass().getSimpleName());
        }
    }

    /** 兼容用户手填的几种写法：裸 host:port / 带 http:// / 末尾带不带斜杠与 /api */
    static String normalizeApi(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://" + s;
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (!s.endsWith("/api")) {
            s = s + "/api";
        }
        return s;
    }

    /** 一次 R 包装调用：code=0 返回 data，否则抛 SyncException（带服务端原话） */
    private static JSONObject call(OkHttpClient http, String url, String token, RequestBody body)
            throws IOException, SyncClient.SyncException {
        Request.Builder rb = new Request.Builder().url(url);
        if (token != null) {
            rb.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            rb.post(body);
        }
        try (Response resp = http.newCall(rb.build()).execute()) {
            ResponseBody rb2 = resp.body();
            String text = rb2 == null ? "" : rb2.string();
            JSONObject env;
            try {
                env = new JSONObject(text);
            } catch (org.json.JSONException e) {
                throw new SyncClient.SyncException(resp.code(), "HTTP " + resp.code());
            }
            int code = env.optInt("code", -1);
            if (code != 0) {
                String msg = env.optString("message", "");
                throw new SyncClient.SyncException(code, msg.isEmpty() ? "业务错误 " + code : msg);
            }
            JSONObject data = env.optJSONObject("data");
            return data == null ? new JSONObject() : data;
        }
    }

    private static JSONObject fail(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("message", message);
        } catch (org.json.JSONException ignored) {
            // JSONObject.put(String,String) 不会真抛
        }
        return o;
    }
}
