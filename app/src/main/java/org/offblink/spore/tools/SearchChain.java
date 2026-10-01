package org.offblink.spore.tools;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 检索链（web_search / web）——语义移植桌面 `src/lib/tools.js`，
 * 同步自 Fungi `fungi/tools/webtools.py`（spec §71）：
 * <ul>
 *   <li>引擎链 duckduckgo → bing → brave；定序由「代理」字段决定
 *       （填了 = ddg 打头；留空 = 只走 bing，ddg 直连只会白等一个超时）</li>
 *   <li>三道闸：空页/节流页每腿两次（间隔 500ms）、结果必须含查询实词（诱饵页拒）、
 *       逐腿报错 `ERROR: Search failed (duckduckgo timed out; bing HTTP 429)`</li>
 *   <li>bing 腿 = RSS 主 + HTML 回落（本仓本地适配，其余照抄 Fungi）</li>
 * </ul>
 * 纯 JVM（不依赖 android.*），后台线程同步调用。
 * ⚠️ `webSearch` 的 ERROR 串在桌面原文就没有右括号（`tests/search.test.mjs` 钉着），
 * 逐字保留——要修先改桌面与测试，别在这里单方面修。
 */
public final class SearchChain {

    private SearchChain() {
    }

    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final int SEARCH_TIMEOUT = 12000;
    private static final int WEB_TIMEOUT = 15000;
    private static final int TRUNCATE = 12000;
    private static final int SEARCH_ATTEMPTS = 2;
    private static final int SEARCH_RETRY_PAUSE = 500;
    private static final int SEARCH_HITS = 8;

    /** 查询里的短词/虚词不构成「结果属于这次查询」的证据（≥4 字符的实词才算） */
    private static final Set<String> NOISE_WORDS = Set.of(
            "the", "and", "for", "with", "how", "what", "does", "that", "from", "into", "about");

    /** 引擎链开关（每次核实开始前由引擎调 setProxy） */
    private static volatile String searchProxy = "";

    /** 诊断日志环（桌面日志环的移动端替身，300 条；日志页在 handoff §9⑤ 落地） */
    private static final int LOG_CAP = 300;
    private static final ArrayDeque<String> logBuf = new ArrayDeque<>();

    private static final OkHttpClient CLIENT_SEARCH = buildClient(SEARCH_TIMEOUT);
    private static final OkHttpClient CLIENT_WEB = buildClient(WEB_TIMEOUT);

    // ---------------------------------------------------------------- 开关与日志

    /** 由引擎在每次核实开始前用当前设置调一次（设置改了下一次检索即生效） */
    public static void setProxy(String v) {
        searchProxy = v == null ? "" : v.trim();
    }

    /** 当前引擎链（纯函数，对齐桌面 searchPlan） */
    public static List<String> searchPlan() {
        return searchPlan(searchProxy);
    }

    public static List<String> searchPlan(String proxy) {
        if (proxy == null || proxy.trim().isEmpty()) {
            return List.of("bing");
        }
        return List.of("duckduckgo", "bing", "brave");
    }

    public static synchronized List<String> readLog() {
        return new ArrayList<>(logBuf);
    }

    private static synchronized void toolLog(String msg) {
        try {
            String at = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
            logBuf.addLast(at + " " + msg);
            while (logBuf.size() > LOG_CAP) {
                logBuf.removeFirst();
            }
        } catch (Exception ignored) {
            // 日志不能影响工具本身
        }
    }

    // ---------------------------------------------------------------- 分发入口

    /**
     * 统一分发：未知工具/缺参/异常都回填 ERROR 字符串（照抄 fungi dispatch），不向引擎抛业务异常。
     */
    public static String dispatch(String name, String argsJson) {
        long t0 = System.currentTimeMillis();
        try {
            JSONObject args = parseArgsLenient(argsJson);
            if ("web_search".equals(name)) {
                String query = args.optString("query", "");
                if (query.isEmpty()) {
                    return "ERROR: Missing required argument: query";
                }
                String out = webSearch(query);
                toolLog("web_search \"" + clip(query, 50) + "\" " + (System.currentTimeMillis() - t0)
                        + "ms → " + wsClip(out, 140));
                return out;
            }
            if ("web".equals(name)) {
                String url = args.optString("url", "");
                if (url.isEmpty()) {
                    return "ERROR: Missing required argument: url";
                }
                String out = web(url);
                toolLog("web " + clip(url, 60) + " " + (System.currentTimeMillis() - t0)
                        + "ms → " + wsClip(out, 120));
                return out;
            }
            return "ERROR: Unknown tool: " + name;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "ERROR: interrupted";
        } catch (Exception e) {
            toolLog(name + " threw " + clip(String.valueOf(e.getMessage()), 120));
            return "ERROR: " + (e.getMessage() != null ? e.getMessage() : e);
        } catch (Throwable t) {
            // Error（NoSuchMethodError/OOM…）也回 ERROR 字符串：工具绝不许杀进程（第六轮栈实证）
            toolLog(name + " threw " + String.valueOf(t));
            return "ERROR: " + t;
        }
    }

    private static JSONObject parseArgsLenient(String s) {
        try {
            return new JSONObject(s == null ? "{}" : s);
        } catch (Exception e) {
            String cleaned = (s == null ? "{}" : s).replaceAll(",\\s*([}\\]])", "$1");
            try {
                return new JSONObject(cleaned);
            } catch (Exception e2) {
                return new JSONObject();
            }
        }
    }

    // ---------------------------------------------------------------- web_search

    /**
     * 引擎链检索。成功 = 格式化结果串；三腿全空 = (no results ...)；
     * 有硬失败/诱饵页 = ERROR 串（逐腿点名）。IO 硬失败在腿内抛出 → dispatch 兜。
     */
    public static String webSearch(String query) throws InterruptedException {
        List<String> plan = searchPlan();
        toolLog("search: " + (searchProxy.isEmpty() ? "未配代理" : "代理模式")
                + " → " + String.join("→", plan) + " ｜ \"" + clip(query, 40) + "\"");

        Set<String> failures = new LinkedHashSet<>();
        int empty = 0;
        for (String name : plan) {
            for (int attempt = 1; attempt <= SEARCH_ATTEMPTS; attempt++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                String results;
                try {
                    if ("duckduckgo".equals(name)) {
                        results = searchDdg(query);
                    } else if ("bing".equals(name)) {
                        results = searchBing(query);
                    } else {
                        results = searchBrave(query);
                    }
                } catch (IOException e) {
                    failures.add(name + " " + legError(e));
                    break; // 硬失败不重试，换腿
                }
                if (!results.isEmpty() && relevant(query, results)) {
                    return truncateMiddle(results, 8000);
                }
                if (!results.isEmpty()) {
                    failures.add(name + " returned unrelated hits");
                    break; // 诱饵页不重试、不返回
                }
                empty++; // 空页/节流页是可重试的失败
                toolLog("search leg " + name + ": empty (attempt " + attempt + "/" + SEARCH_ATTEMPTS + ")");
                if (attempt < SEARCH_ATTEMPTS) {
                    Thread.sleep(SEARCH_RETRY_PAUSE);
                }
            }
        }
        if (!failures.isEmpty()) {
            // ⚠️ 桌面原文即无右括号，逐字对齐（见类注释）
            return "ERROR: Search failed (" + String.join("; ", failures);
        }
        if (empty > 0) {
            return "(no results for '" + query + "')";
        }
        return "ERROR: Search failed (no engine configured)";
    }

    /** 腿级错误 → 报错片段（HTTP 状态原样、我方超时记 timed out，对齐 Fungi `name reason` 口径） */
    private static String legError(Exception e) {
        if (e instanceof SocketTimeoutException || e instanceof InterruptedIOException) {
            return "timed out";
        }
        String m = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
        if (m.matches("^HTTP \\d+$")) {
            return m;
        }
        return m.length() > 60 ? m.substring(0, 60) : m;
    }

    /** 相关性闸：结果必须含查询里某个实词（≥4 字符、非虚词），否则判诱饵页——宁可报错不给静默错答（Fungi §71） */
    private static boolean relevant(String query, String results) {
        String[] raw = query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+");
        List<String> tokens = new ArrayList<>();
        for (String w : raw) {
            if (w.length() >= 4 && !NOISE_WORDS.contains(w)) {
                tokens.add(w);
            }
        }
        if (tokens.isEmpty()) {
            return true;
        }
        String low = results.toLowerCase(Locale.ROOT);
        for (String w : tokens) {
            if (low.contains(w)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 三腿实现

    /** DuckDuckGo 静态页：按标题锚点切分（容器 class 会变，锚点不变），跳赞助行、还原跳转 */
    private static String searchDdg(String query) throws IOException, InterruptedException {
        String url = "https://html.duckduckgo.com/html/?q=" + encode(query);
        String html = getText(url, SEARCH_TIMEOUT);
        List<Item> items = new ArrayList<>();
        String[] chunks = Pattern.compile("class=\"result__a\"", Pattern.CASE_INSENSITIVE)
                .split(html);
        Pattern aPat = Pattern.compile("^[^>]*?href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>",
                Pattern.CASE_INSENSITIVE);
        Pattern snPat = Pattern.compile("class=\"result__snippet\"[^>]*>([\\s\\S]*?)</a>",
                Pattern.CASE_INSENSITIVE);
        for (int i = 1; i < chunks.length; i++) {
            String chunk = chunks[i];
            Matcher a = aPat.matcher(chunk);
            if (!a.find()) {
                continue;
            }
            String href = decodeEntities(a.group(1));
            if (href.contains("y.js") || href.contains("ad_domain")) {
                continue; // 赞助行
            }
            Matcher sn = snPat.matcher(chunk);
            String snippet = sn.find() ? clean(sn.group(1)) : "";
            items.add(new Item(clean(a.group(2)), ddgTarget(href), snippet));
            if (items.size() >= SEARCH_HITS) {
                break;
            }
        }
        return formatResults(items);
    }

    /**
     * Bing：RSS 主 + HTML 回落（本仓实测，与 Fungi 纯 HTML 腿不同——刻意保留的本地适配）。
     * 有响应但 0 条 = ""（引擎链按可重试空页处理），三个入口全网络失败才抛错。
     */
    private static String searchBing(String query) throws IOException, InterruptedException {
        String q = encode(query);
        String[] tries = {
                "https://cn.bing.com/search?q=" + q + "&format=rss&count=20&setlang=zh-CN",
                "https://www.bing.com/search?q=" + q + "&format=rss&count=20&setlang=en-US",
                "https://cn.bing.com/search?q=" + q + "&count=20&setlang=zh-CN",
        };
        IOException lastErr = null;
        String firstBody = "";
        boolean sawBody = false;
        for (String url : tries) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            String text;
            try {
                text = getText(url, SEARCH_TIMEOUT);
            } catch (IOException e) {
                lastErr = e;
                continue;
            }
            sawBody = true;
            List<Item> items = url.contains("format=rss") ? parseRss(text) : parseHtml(text);
            if (!items.isEmpty()) {
                return formatResults(items);
            }
            if (firstBody.isEmpty()) {
                firstBody = text;
            }
        }
        if (sawBody) {
            // 有响应但一条都没解析出来（节流页/改版）：留痕，交给引擎链当空页重试
            toolLog("bing empty → body head: " + wsClip(firstBody, 220));
            return "";
        }
        throw lastErr != null ? lastErr : new IOException("search failed");
    }

    /** brave：整页去标签兜底（≥50 字才算有内容），只在前两腿全挂时出场 */
    private static String searchBrave(String query) throws IOException, InterruptedException {
        String raw = getText("https://search.brave.com/search?q=" + encode(query), SEARCH_TIMEOUT);
        String noScript = raw
                .replaceAll("(?is)<script[\\s\\S]*?</script>", " ")
                .replaceAll("(?is)<style[\\s\\S]*?</style>", " ")
                .replaceAll("(?is)<svg[\\s\\S]*?</svg>", " ")
                .replaceAll("(?s)<!--[\\s\\S]*?-->", " ");
        String text = stripTags(noScript).trim();
        return text.length() >= 50 ? text : "";
    }

    private static List<Item> parseRss(String xml) {
        List<Item> out = new ArrayList<>();
        Matcher m = Pattern.compile("<item>([\\s\\S]*?)</item>").matcher(xml);
        while (m.find()) {
            String blk = m.group(1);
            String title = groupOf(blk, "<title>([\\s\\S]*?)</title>");
            String link = groupOf(blk, "<link>([\\s\\S]*?)</link>");
            String desc = groupOf(blk, "<description>([\\s\\S]*?)</description>");
            if (link.isEmpty()) {
                continue;
            }
            out.add(new Item(clean(title), clean(link), clean(desc)));
            if (out.size() >= SEARCH_HITS) {
                break;
            }
        }
        return out;
    }

    private static List<Item> parseHtml(String html) {
        List<Item> out = new ArrayList<>();
        Matcher m = Pattern.compile("<li class=\"b_algo[\\s\\S]*?</li>").matcher(html);
        while (m.find()) {
            String blk = m.group();
            Matcher a = Pattern.compile("<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>")
                    .matcher(blk);
            if (!a.find()) {
                continue;
            }
            Matcher p = Pattern.compile("<p[^>]*>([\\s\\S]*?)</p>").matcher(blk);
            out.add(new Item(clean(a.group(2)), unbing(clean(a.group(1))),
                    p.find() ? clean(p.group(1)) : ""));
            if (out.size() >= SEARCH_HITS) {
                break;
            }
        }
        return out;
    }

    private static String groupOf(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private static String formatResults(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append(i + 1).append(". ").append(it.title)
                    .append("\n   ").append(it.url);
            if (!it.snippet.isEmpty()) {
                sb.append("\n   ").append(it.snippet);
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- web（正文）

    /** 正文抓取：去 script/style/head，压缩空白，中间截断 */
    public static String web(String url) throws InterruptedException {
        if (!url.matches("(?i)^https?://.*")) {
            return "ERROR: not an http(s) url: " + url;
        }
        String raw;
        try {
            raw = getText(url, WEB_TIMEOUT);
        } catch (IOException e) {
            return "ERROR: fetch failed (" + (e.getMessage() != null ? e.getMessage() : e) + ")";
        }
        String text = raw
                .replaceAll("(?is)<script[\\s\\S]*?</script>", " ")
                .replaceAll("(?is)<style[\\s\\S]*?</style>", " ")
                .replaceAll("(?is)<noscript[\\s\\S]*?</noscript>", " ")
                .replaceAll("(?is)<svg[\\s\\S]*?</svg>", " ")
                .replaceAll("(?s)<!--[\\s\\S]*?-->", " ")
                .replaceAll("(?i)</(p|div|li|h[1-6]|tr|br)>", "\n")
                .replaceAll("(?i)<(br|hr)\\s*/?>", "\n");
        String stripped = postLineFilter(stripTags(text));
        if (stripped.length() < 30) {
            return "ERROR: empty or blocked page: " + url;
        }
        return truncateMiddle(stripped, TRUNCATE);
    }

    /** 逐行 trim → 过滤连续空行 → 拼回 → 三连换行压成两行 → trim（桌面同序） */
    private static String postLineFilter(String stripped) {
        String[] lines = stripped.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            boolean prevNonEmpty = i > 0 && !lines[i - 1].trim().isEmpty();
            if (l.isEmpty() && !prevNonEmpty) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(l);
        }
        String joined = sb.toString().replaceAll("\\n{3,}", "\n\n");
        return joined.trim();
    }

    // ---------------------------------------------------------------- HTTP

    private static OkHttpClient buildClient(int timeoutMs) {
        return new OkHttpClient.Builder()
                .connectTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build();
    }

    private static String getText(String url, int timeoutMs) throws IOException {
        OkHttpClient client = timeoutMs <= SEARCH_TIMEOUT ? CLIENT_SEARCH : CLIENT_WEB;
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .get()
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code());
            }
            ResponseBody body = resp.body();
            return body == null ? "" : body.string();
        }
    }

    // ---------------------------------------------------------------- 字符串工具

    private static String encode(String s) {
        // 对齐 JS encodeURIComponent 的查询串语义（空格 %20 即可，引擎不挑）
        // 必须用 String 重载：URLEncoder.encode(String, Charset) 是 API 33+ 才有，
        // 旧机（鸿蒙底座 <33）会 NoSuchMethodError = 一进检索就崩（第六轮真机栈实证）
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return s; // UTF-8 必在
        }
    }

    /** ddg 跳转 → 真链（只解码一次；Fungi 二次 unquote 会把 URL 里合法的 %20 解坏） */
    private static String ddgTarget(String href) {
        String h = href;
        if (h.startsWith("//")) {
            h = "https:" + h;
        }
        try {
            URL u = new URL(h);
            String query = u.getQuery();
            if (query != null) {
                for (String part : query.split("&")) {
                    int eq = part.indexOf('=');
                    if (eq > 0 && "uddg".equals(part.substring(0, eq))) {
                        String target = URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
                        if (!target.isEmpty()) {
                            return target;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 非法 URL 原样返回
        }
        return h;
    }

    /** bing 的 /ck/a?...&u=a1<base64url> 跳转 → 真链（Fungi _unbing）；解不出原样返回 */
    private static String unbing(String url) {
        Matcher m = Pattern.compile("[?&]u=a1([A-Za-z0-9_-]+)").matcher(url);
        if (!m.find()) {
            return url;
        }
        try {
            String b64 = m.group(1).replace('-', '+').replace('_', '/');
            int pad = (4 - (b64.length() % 4)) % 4;
            StringBuilder sb = new StringBuilder(b64);
            for (int i = 0; i < pad; i++) {
                sb.append('=');
            }
            byte[] bytes = Base64.getDecoder().decode(sb.toString());
            // 严格 UTF-8（对齐桌面 TextDecoder fatal:true：解码失败当非法链接丢弃）
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (Exception e) {
            return url;
        }
    }

    private static final Pattern ENTITY_PAT =
            Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);");

    private static String decodeEntities(String text) {
        Map<String, String> named = new HashMap<>();
        named.put("amp", "&");
        named.put("lt", "<");
        named.put("gt", ">");
        named.put("quot", "\"");
        named.put("apos", "'");
        named.put("nbsp", " ");
        named.put("hellip", "…");
        named.put("mdash", "—");
        named.put("ndash", "–");
        named.put("ldquo", "“");
        named.put("rdquo", "”");
        named.put("lsquo", "‘");
        named.put("rsquo", "’");
        named.put("times", "×");
        named.put("divide", "÷");
        named.put("middot", "·");
        named.put("copy", "©");

        Matcher m = ENTITY_PAT.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String body = m.group(1);
            String repl;
            if (body.startsWith("#")) {
                boolean hex = body.length() > 1 && (body.charAt(1) == 'x' || body.charAt(1) == 'X');
                try {
                    int code = Integer.parseInt(body.substring(hex ? 2 : 1), hex ? 16 : 10);
                    repl = new String(Character.toChars(code));
                } catch (Exception e) {
                    repl = m.group();
                }
            } else {
                String v = named.get(body);
                repl = v != null ? v : m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String stripTags(String html) {
        String noTags = html.replaceAll("<[^>]*>", " ");
        return decodeEntities(noTags)
                .replaceAll("[ \\t\\u00A0]+", " ")
                .replaceAll("\\n{3,}", "\n\n");
    }

    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        return decodeEntities(text)
                .replaceAll("<[^>]*>", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static String truncateMiddle(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        int half = limit / 2;
        return text.substring(0, half)
                + "\n\n... [" + "truncated " + (text.length() - limit) + " chars] ...\n\n"
                + text.substring(text.length() - half);
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 截断 + 压空白（日志行用，顺序对齐桌面 `.slice().replace()` 调用点） */
    private static String wsClip(String s, int max) {
        String head = s.length() > max ? s.substring(0, max) : s;
        return head.replaceAll("\\s+", " ");
    }

    private static final class Item {
        final String title;
        final String url;
        final String snippet;

        Item(String title, String url, String snippet) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
        }
    }
}
