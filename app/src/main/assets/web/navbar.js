/* 底部导航条点击路由 + 入场行程（navbar.css 的伴生脚本，三页共用）。
 *
 * 切页走原生桥，不做页内平移——Activity 转场（SporeAnim）就是切页动画：
 *   主页    → openRecords / openSettings（MainActivity 原有）
 *   记录页  → openSettings（RecordActivity 新增，关自己再开，栈不叠页）
 *   设置页  → openRecords （SettingsActivity 新增，同上）
 *   三页共通 → close（finish 回主页）
 * 已在本页的 tab 直接 return：不重复 startActivity、也不回压栈。
 * 桥缺失（无头浏览器冒烟）时 bridge() 返回 null，这里静默不动作，页面照常渲染。 */
(function () {
  "use strict";

  var CUR = document.body.getAttribute("data-tab") || "home";
  var GO = {
    home: "close",
    records: "openRecords",
    settings: "openSettings"
  };
  var POS = { home: 0, records: 1, settings: 2 };

  /** 入场行程：从 data-from 那一格弹射到本页那一格（中途沿轴轻微拉长）。
   *  速度曲线分两段半程显式给 easing = 先加速后减速（"--spring" 那种过冲曲线在
   *  跨页行程里会显得甩，这里要的是「起-落」。 */
  function enter() {
    var pill = document.querySelector(".tab-pill");
    var from = document.documentElement.getAttribute("data-from");
    var settled = function () {
      document.documentElement.removeAttribute("data-from");
    };
    if (!pill || !from || POS[from] == null || from === CUR) {
      settled();
      return;
    }
    var a = POS[from] * 100;
    var b = POS[CUR] * 100;
    var reduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduced || typeof pill.animate !== "function") {
      settled(); // 降级：直接落位（CSS 已就位）
      return;
    }
    var anim = pill.animate([
      { transform: "translateX(" + a + "%) scaleX(.94)", easing: "cubic-bezier(.55,0,.75,.5)" },
      { transform: "translateX(" + (a + b) / 2 + "%) scaleX(1.14)", offset: 0.5, easing: "cubic-bezier(.3,.5,.2,1)" },
      { transform: "translateX(" + b + "%) scaleX(1)" }
    ], { duration: 560, fill: "forwards" });
    anim.onfinish = settled;
    setTimeout(settled, 900); // 动画被切页打断时的兜底，别让 data-from 卡住胶囊
  }

  document.addEventListener("DOMContentLoaded", function () {
    enter();
    var bar = document.querySelector(".tabbar");
    if (!bar) {
      return;
    }
    bar.addEventListener("click", function (e) {
      var it = e.target.closest ? e.target.closest(".tabitem") : null;
      if (!it) {
        return;
      }
      var tab = it.getAttribute("data-tab");
      if (!tab || tab === CUR) {
        return;
      }
      var method = GO[tab];
      if (method) {
        bridge(method);
      }
    });
  });
})();
