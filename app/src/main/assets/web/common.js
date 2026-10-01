/* Spore web 共享胶水 —— 面板/记录/主页/设置四页共用。页面专属逻辑别放这里。
 *
 * 与 Java 侧的桥契约（改一边必须同步另一边）：
 *   JS → 原生：window.Spore.<method>(...)，即各页宿主对象
 *     （AnswerPanel / RecordActivity / MainActivity / SettingsActivity）
 *     上带 @JavascriptInterface 的方法。**只对方法加注解，JS 才看得见**。
 *   原生 → JS：
 *     Host.onEvent(ev)   引擎流式增量事件（type: answer-delta / think-delta /
 *                        verify-delta / chat-delta / tool / status / title /
 *                        session-new / answer-start / chat-start / turn-end / error）
 *     Host.onState(st)   全量快照 {session, sessions, busy}——结构性变化后原生推，
 *                        页面自己也可用 bridge("state") 主动拉。
 *     Host.back()        返回键询问：true = 页面自己消费（如详情回列表），false = 原生继续
 *                        （Activity finish / 面板收起）。页面禁止用 history.pushState。
 */
(function () {
  "use strict";

  /**
   * 同步调原生：返回值是 JSON 就解析成对象，否则原样返回（data URL / 纯文本）。
   * 桥缺失/方法缺失/异常一律回 null——页面按空态降级，绝不抛到顶层。
   */
  window.bridge = function (method) {
    try {
      var f = window.Spore && window.Spore[method];
      if (typeof f !== "function") {
        return null;
      }
      var args = Array.prototype.slice.call(arguments, 1);
      // 必须以注入对象为 this：JavaBridge 方法靠 receiver 找回宿主对象，
      // 脱引用 f.apply(null,…) 会报 "non-injected object"（WebView 149 AVD 实测钉死）
      var raw = f.apply(window.Spore, args);
      if (typeof raw !== "string") {
        return raw == null ? null : raw; // boolean / number 直接透传
      }
      try {
        return JSON.parse(raw);
      } catch (ignore) {
        return raw;
      }
    } catch (e) {
      console.error("bridge." + method, e);
      return null;
    }
  };

  /** 事件落点：页面按需覆写钩子，没覆写的默认空实现 */
  window.Host = {
    onEvent: function () {},
    onState: function () {},
    back: function () { return false; }
  };

  // 桥健康自报（web 皮排错入口：adb logcat -s spore-web 直接看得到）
  console.log(
    "spore:boot Spore=" + typeof window.Spore +
    " methods=" + (window.Spore ? Object.keys(window.Spore).join(",") : "-")
  );

  /** 页内 toast（MV3 深色 pill）。页内可见即可，原生 Toast 不进这里。 */
  var toastEl = null;
  var toastTimer = 0;
  window.toast = function (msg, ms) {
    if (!toastEl) {
      toastEl = document.createElement("div");
      toastEl.id = "toast";
      document.body.appendChild(toastEl);
    }
    toastEl.textContent = String(msg == null ? "" : msg);
    toastEl.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      toastEl.classList.remove("show");
    }, ms || 1800);
  };

  window.esc = function (s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  };

  /** "yyyy-MM-dd HH:mm"（记录页用） */
  window.fmtTime = function (ts) {
    var d = new Date(Number(ts) || 0);
    function p(n) { return (n < 10 ? "0" : "") + n; }
    return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate()) +
      " " + p(d.getHours()) + ":" + p(d.getMinutes());
  };

  /** "MM-dd HH:mm"（面板 listpop 行用，桌面同款粒度） */
  window.fmtShort = function (ts) {
    var d = new Date(Number(ts) || 0);
    function p(n) { return (n < 10 ? "0" : "") + n; }
    return p(d.getMonth() + 1) + "-" + p(d.getDate()) + " " +
      p(d.getHours()) + ":" + p(d.getMinutes());
  };

  /** 截图 data URL，按 path 只过桥一次并缓存（base64 几百 KB，绝不重复取） */
  var imgCache = {};
  window.imageFor = function (path) {
    if (!path) {
      return "";
    }
    if (imgCache[path] !== undefined) {
      return imgCache[path];
    }
    var url = window.bridge("image", path);
    imgCache[path] = typeof url === "string" ? url : "";
    return imgCache[path];
  };
})();
