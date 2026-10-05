package org.offblink.spore.sync;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Spore-GUI 同步 REST 客户端（{@code base} = 扫码载荷里的 api，形如
 * {@code http://192.168.1.5:8080/api}）。协议对齐 Spore-GUI：
 * <ul>
 *   <li>统一返回 {@code {code,message,data}}，code=0 成功，非 0 抛 {@link SyncException}；</li>
 *   <li>鉴权 {@code Authorization: Bearer <LAN token>}（服务端 AuthInterceptor 唯一闸门）；</li>
 *   <li>{@code GET sync/pull} 游标增量（含墓碑）→ {@code POST sync/push} LWW →
 *       {@code POST sync/push-attachment}（multipart 字段 file）/ {@code GET sync/pull-attachment}；</li>
 *   <li>{@code GET users/me} = 配对页连通校验。</li>
 * </ul>
 * 同步只在后台线程调它（阻塞 IO）；所有调用都不做重试——重试是 SyncEngine 一轮的事，
 * 这层只负责把服务端原话（code+message）如实抛上去。
 */
public class SyncClient {

    /** 服务端业务错误（HTTP 401/400/500 都带 R 包装时一并解析出来） */
    public static class SyncException extends Exception {
        public final int code;

        SyncException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private final OkHttpClient http;
    private final String api;
    private final String token;

    public SyncClient(String api, String token) {
        this.api = api.endsWith("/") ? api.substring(0, api.length() - 1) : api;
        this.token = token == null ? "" : token;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /** GET sync/pull → data（{categories, articles, nextCursor}） */
    public JSONObject pull(long cursor, int limit) throws IOException, SyncException {
        return getOk("/sync/pull?cursor=" + cursor + "&limit=" + limit);
    }

    /** POST sync/push → data.results（[{id, accepted}]；accepted 只作参考，游标照进不误） */
    public JSONArray push(JSONObject body) throws IOException, SyncException {
        Request req = post("/sync/push", jsonBody(body.toString()));
        return call(req).optJSONArray("results");
    }

    /** 题图上推 → 服务端返回的相对题库目录路径（写进 article.attachment_path 用） */
    public String pushAttachment(String articleId, File file) throws IOException, SyncException {
        RequestBody form = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(),
                        RequestBody.create(file, MediaType.parse("image/jpeg")))
                .build();
        Request req = new Request.Builder()
                .url(api + "/sync/push-attachment?articleId=" + articleId)
                .addHeader("Authorization", "Bearer " + token)
                .post(form)
                .build();
        JSONObject data = call(req);
        return data == null ? "" : data.optString("path", "");
    }

    /** 题图下推：按相对路径读字节；404 → null（没图不是错误） */
    public byte[] pullAttachment(String relPath) throws IOException, SyncException {
        Request req = new Request.Builder()
                .url(api + "/sync/pull-attachment?path=" + relPath)
                .addHeader("Authorization", "Bearer " + token)
                .get()
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (resp.code() == 404) {
                return null;
            }
            ResponseBody body = resp.body();
            byte[] bytes = body == null ? new byte[0] : body.bytes();
            if (!resp.isSuccessful()) {
                throw toSyncException(resp.code(), bytes);
            }
            return bytes;
        }
    }

    /** GET users/me → data（UserVO：nickname 等）；配对页连通校验 */
    public JSONObject me() throws IOException, SyncException {
        return getOk("/users/me");
    }

    /** 未入状态前的连通校验（配对页手改完 api 直接试） */
    public static JSONObject me(String api, String token) throws IOException, SyncException {
        return new SyncClient(api, token).me();
    }

    // ---------------------------------------------------------------- 内部

    private JSONObject getOk(String path) throws IOException, SyncException {
        Request req = new Request.Builder()
                .url(api + path)
                .addHeader("Authorization", "Bearer " + token)
                .get()
                .build();
        return call(req);
    }

    private Request post(String path, RequestBody body) {
        return new Request.Builder()
                .url(api + path)
                .addHeader("Authorization", "Bearer " + token)
                .post(body)
                .build();
    }

    private static RequestBody jsonBody(String json) {
        return RequestBody.create(json, MediaType.parse("application/json; charset=utf-8"));
    }

    /** 执行并拆 R 包装：code=0 → data（可为 null）；非 0 → SyncException(message) */
    private JSONObject call(Request req) throws IOException, SyncException {
        try (Response resp = http.newCall(req).execute()) {
            ResponseBody body = resp.body();
            byte[] bytes = body == null ? new byte[0] : body.bytes();
            if (bytes.length == 0) {
                if (resp.isSuccessful()) {
                    return null;
                }
                throw new SyncException(resp.code(), "HTTP " + resp.code());
            }
            JSONObject env;
            try {
                env = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            } catch (org.json.JSONException e) {
                // 非 R 包装（反代页/网关错误）：原文截断给人看
                if (resp.isSuccessful()) {
                    throw new SyncException(-1, "响应不是 R 包装");
                }
                String txt = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).replaceAll("\\s+", " ");
                throw new SyncException(resp.code(),
                        "HTTP " + resp.code() + (txt.length() > 80 ? txt.substring(0, 80) : txt));
            }
            int code = env.optInt("code", -1);
            if (code == 0) {
                JSONObject data = env.optJSONObject("data");
                return data;
            }
            String msg = env.optString("message", "");
            if (msg.isEmpty()) {
                msg = "业务错误 " + code;
            }
            throw new SyncException(code, msg);
        }
    }

    private static SyncException toSyncException(int httpCode, byte[] bytes) {
        try {
            JSONObject env = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            int code = env.optInt("code", httpCode);
            String msg = env.optString("message", "");
            return new SyncException(code, msg.isEmpty() ? "HTTP " + httpCode : msg);
        } catch (org.json.JSONException e) {
            return new SyncException(httpCode, "HTTP " + httpCode);
        }
    }
}
