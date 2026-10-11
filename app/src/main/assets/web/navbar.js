/* 底部导航条点击路由（navbar.css 的伴生脚本，三页共用）。
 *
 * 切页走原生桥，不在此处做任何页内平移——Activity 转场（SporeAnim）就是切页动画：
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

  document.addEventListener("DOMContentLoaded", function () {
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
