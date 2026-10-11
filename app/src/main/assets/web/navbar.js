/* 底部导航条：点击路由 + 入场重播（navbar.css 的伴生脚本，三页共用）。
 *
 * 切页动作全在 web 层（CSS 动画挂在 html[data-from] 上，见 navbar.css）：整页平移与
 * 胶囊行程同一条 --nav-move、同一帧起跑。原生 Activity 转场由 Java 侧关掉。
 * 点击路由：
 *   主页    → openRecords / openSettings（MainActivity 原有）
 *   记录页  → openSettings（RecordActivity，关自己再开，栈不叠页）
 *   设置页  → openRecords （SettingsActivity，同上）
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
  var root = document.documentElement;
  var clearT = 0;

  function scheduleClear() {
    clearTimeout(clearT);
    // 动画 300ms；跑完把 data-from 摘掉，胶囊交回 body[data-tab] 的静止位置（= 动画终点，无跳变）
    clearT = setTimeout(function () { root.removeAttribute("data-from"); }, 420);
  }

  /**
   * 重播一次入场（整页平移 + 胶囊行程）。
   * 回主页时主页是「恢复」而不是重载（WebView 没重新加载），原生喊这一声才有动画。
   * 摘属性 → 强制重排 → 再挂上 = 干干净净的第二遍（不断开的话浏览器认为动画没变，不重播）。
   */
  function replay(from) {
    root.removeAttribute("data-from");
    void root.offsetWidth;
    root.setAttribute("data-from", from);
    scheduleClear();
  }
  window.__navEnter = replay;

  document.addEventListener("DOMContentLoaded", function () {
    // 首次加载：动画在首帧就起跑了（早于这里），只接管收尾清场，别重播——
    // 重播会把已经跑了一半的动画拽回起点，看着就是「闪一下」。
    if (root.getAttribute("data-from")) {
      scheduleClear();
    }
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
