/* 运行日志页：取尾部、刷新、复制、清空。桥协议见 common.js 头注（Spore.logRead/logCopy/logClear）。 */
(function () {
  "use strict";

  var $ = function (id) { return document.getElementById(id); };

  function refresh() {
    var s = "";
    try {
      s = bridge("logRead") || "";
    } catch (e) {
      s = "读取失败：" + e;
    }
    var el = $("logBody");
    el.textContent = s || "（空）";
    el.scrollTop = el.scrollHeight;
  }

  document.addEventListener("DOMContentLoaded", function () {
    $("btnBack").addEventListener("click", function () { bridge("close"); });
    $("btnRefresh").addEventListener("click", refresh);
    $("btnCopy").addEventListener("click", function () {
      if (bridge("logCopy")) {
        toast("日志已复制");
      } else {
        toast("日志是空的");
      }
    });
    $("btnClear").addEventListener("click", function () {
      bridge("logClear");
      refresh();
      toast("日志已清空");
    });
    refresh();
  });
})();
