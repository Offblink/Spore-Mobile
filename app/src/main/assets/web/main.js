/* 主页逻辑：状态胶囊 / 主 CTA / 分组导航。桥协议见 common.js 头注。 */
(function () {
  "use strict";

  var $ = function (id) { return document.getElementById(id); };

  /* 文案抄 strings.xml 的 status_* / start_ball / stop_ball（web 皮文案落 HTML/JS 是既定口径） */
  var TXT = {
    noOverlay: "请先授予「显示在其他应用上层」权限（设置 → 应用 → 权限 → 悬浮窗）",
    running: "悬浮球运行中 · 点球即截屏",
    idle: "悬浮球已停止",
    start: "显示悬浮球",
    stop: "隐藏悬浮球"
  };

  var hintTimer = 0;
  /** 授权缺失状态的起点：稳定满 2s 才显示提示（第六轮：授权落库异步，别短闪「失效」） */
  var hintSince = 0;

  function render(st) {
    if (!st) {
      return; // 桥缺失/首帧未到：保留 HTML 里的静态空态
    }
    var running = !!st.running;
    var overlay = !!st.overlay;
    $("statusText").textContent = !overlay ? TXT.noOverlay
      : running ? TXT.running : TXT.idle;
    $("statusDot").classList.toggle("on", running);
    var cta = $("btnBall");
    cta.textContent = running ? TXT.stop : TXT.start;
    cta.classList.toggle("btn-soft", running);
    cta.classList.toggle("btn-primary", !running);
    // 投影授权只在开球开关时过桥；running && 授权没读到 = 可能需重授。
    // 第六轮：授权落库是异步的，false 稳定满 2s 才亮提示（防授权刚过就短闪）；
    // 复查轮询照常跑（1.5s 一拉，projection 到手即停，别把误报挂死）。
    var missing = running && !st.projection;
    var needHint = missing;
    if (missing) {
      if (!hintSince) {
        hintSince = Date.now();
      }
      if (Date.now() - hintSince < 2000) {
        needHint = false;
      }
    } else {
      hintSince = 0;
    }
    $("projHint").hidden = !needHint;
    if (missing && !hintTimer) {
      hintTimer = setInterval(function () {
        var s2 = bridge("ready");
        if (!s2) {
          return;
        }
        render(s2);
      }, 1500);
    } else if (!missing && hintTimer) {
      clearInterval(hintTimer);
      hintTimer = 0;
    }
  }

  /** 开关 + 延迟重拉：起服务/过授权是异步的，~400ms 后再要一帧 */
  function pokeBall() {
    bridge("toggleBall");
    setTimeout(function () { render(bridge("ready")); }, 400);
  }

  // 原生 → 页面：全量快照（onResume、权限/授权回调后原生推）
  Host.onState = function (st) { render(st); };

  document.addEventListener("DOMContentLoaded", function () {
    $("btnBall").addEventListener("click", pokeBall);
    // 重授权提示：专用 reauth（球照跑）；走 pokeBall 会撞 toggleBall 把运行中的球关掉
    $("projHint").addEventListener("click", function () { bridge("reauth"); });
    $("navRecords").addEventListener("click", function () { bridge("openRecords"); });
    $("navSettings").addEventListener("click", function () { bridge("openSettings"); });
    // 握手：调过 ready 原生才开始推；返回值就是首帧
    render(bridge("ready"));
  });
})();
