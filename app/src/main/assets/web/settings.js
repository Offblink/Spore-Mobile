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
    var pr = document.querySelector(
      'input[name="panelRender"][value="' + (current.panelRender || "web") + '"]');
    if (pr) {
      pr.checked = true;
    } else {
      var def = document.querySelector('input[name="panelRender"][value="web"]');
      if (def) {
        def.checked = true;
      }
    }
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
      autoVerify: on("swAutoVerify"),
      panelRender: (function () {
        var c = document.querySelector('input[name="panelRender"]:checked');
        return c ? c.value : "web";
      })()
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

  // ---------- 日志卡 ----------
  function refreshLog() {
    var s = "";
    try {
      s = bridge("logRead") || "";
    } catch (e) {
      s = "读取失败：" + e;
    }
    $("logBody").textContent = s || "（空）";
    $("logBody").scrollTop = $("logBody").scrollHeight;
  }

  // ---------- 配对与同步卡 ----------
  /** 原生 SyncEngine.isRunning()（轮询用） */
  var syncRunning = false;

  function fillSync(info) {
    info = info || {};
    syncRunning = !!info.running;
    var paired = !!info.paired;
    var syncBtn = $("btnSync");
    syncBtn.disabled = !paired || syncRunning;
    syncBtn.textContent = syncRunning ? "同步中…" : "立即同步";
    $("btnScan").textContent = paired ? "重新扫码" : "扫码配对";
    $("btnUnpair").hidden = !paired;
    if (!paired) {
      $("syncState").textContent = "未配对";
      $("syncHint").textContent = "扫码接入 Spore 桌面端（记录与科目双向同步）";
      return;
    }
    $("syncState").textContent = "已配对 · " + (info.nick || "电脑");
    var parts = [];
    if (info.api) parts.push(info.api);
    if (info.lastSyncAt > 0) parts.push("上次同步 " + fmtTime(info.lastSyncAt));
    if (info.lastSyncMsg) parts.push(info.lastSyncMsg);
    $("syncHint").textContent = parts.join(" · ");
  }

  function refreshSync() {
    fillSync(bridge("pairInfo"));
  }
  // 原生回叫钩子（onResume 从扫码页回来、取消配对确认后都会 evaluateJavascript 到这）
  window.__pairRefresh = refreshSync;

  /** 同步进行中就继续轮（事件通道没有——页内 600ms 拉一次状态） */
  function pollSync() {
    refreshSync();
    if (syncRunning) {
      setTimeout(pollSync, 600);
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

    // 配对与同步：扫码进原生取景页；同步靠轮询拉状态（页内无事件通道）
    refreshSync();
    $("btnScan").addEventListener("click", function () {
      bridge("scan");
    });
    $("btnSync").addEventListener("click", function () {
      bridge("syncStart");
      $("btnSync").disabled = true;
      $("btnSync").textContent = "同步中…";
      setTimeout(pollSync, 400);
    });
    $("btnUnpair").addEventListener("click", function () {
      bridge("unpair"); // 确认框在原生（页面无模态）
    });

    // 日志卡：开页即取尾部；刷新/清空走原生桥
    $("btnLogRefresh").addEventListener("click", refreshLog);
    $("btnLogClear").addEventListener("click", function () {
      bridge("logClear");
      refreshLog();
    });
    refreshLog();

    // 握手：调过 ready 原生才开始推；返回值就是首帧
    fill(bridge("ready"));
  });
})();
