/* 设置页逻辑：ready 回填 8 字段 / save 落盘 / 返回关页。桥协议见 common.js 头注。 */
(function () {
  "use strict";

  var $ = function (id) { return document.getElementById(id); };

  /** ready() 快照：数字坏输入时回落到已存值 */
  var current = null;

  function text(id) {
    var el = $(id);
    return el ? String(el.value || "").trim() : "";
  }

  function num(id, def) {
    var n = parseInt($(id).value, 10);
    return isNaN(n) ? def : n;
  }

  function on(id) {
    return $(id).classList.contains("on");
  }

  function fill(s) {
    current = s || {};
    $("endpoint").value = current.endpoint || "";
    $("model").value = current.model || "";
    $("apiKey").value = current.apiKey || "";
    $("proxy").value = current.proxy || "";
    $("maxToolRounds").value = current.maxToolRounds == null ? 5 : current.maxToolRounds;
    $("historyLimit").value = current.historyLimit == null ? 10 : current.historyLimit;
    $("swFastNoThink").classList.toggle("on", !!current.fastNoThink);
    $("swAutoVerify").classList.toggle("on", !!current.autoVerify);
  }

  function save() {
    var form = {
      endpoint: text("endpoint"),
      model: text("model"),
      apiKey: text("apiKey"),
      proxy: text("proxy"),
      maxToolRounds: num("maxToolRounds",
        current && current.maxToolRounds != null ? current.maxToolRounds : 5),
      historyLimit: num("historyLimit",
        current && current.historyLimit != null ? current.historyLimit : 10),
      fastNoThink: on("swFastNoThink"),
      autoVerify: on("swAutoVerify")
    };
    bridge("save", JSON.stringify(form));
    // 空配置仍允许保存（引擎 isConfigured 会拦作答），只警示
    if (!form.apiKey) {
      toast("Key 为空时无法作答");
    } else if (!form.endpoint) {
      toast("接口地址为空时无法作答");
    } else {
      toast("保存成功");
    }
  }

  document.addEventListener("DOMContentLoaded", function () {
    // Key 显隐切换（password ↔ text）
    $("keyEye").addEventListener("click", function () {
      var key = $("apiKey");
      var show = key.type === "password";
      key.type = show ? "text" : "password";
      this.textContent = show ? "🙈" : "👁";
    });

    // 开关行：点整行翻转轨上开关
    Array.prototype.forEach.call(document.querySelectorAll(".switch-row"), function (row) {
      row.addEventListener("click", function () {
        row.querySelector(".switch").classList.toggle("on");
      });
    });

    $("btnBack").addEventListener("click", function () { bridge("close"); });
    $("btnSave").addEventListener("click", save);

    // 握手：调过 ready 原生才开始推；返回值就是首帧
    fill(bridge("ready"));
  });
})();
