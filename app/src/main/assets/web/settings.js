/* 设置页逻辑：ready 回填 10 字段（含主题档位） / save 落盘 / 返回关页。桥协议见 common.js 头注。 */
(function () {
  "use strict";

  var $ = function (id) { return document.getElementById(id); };

  /** ready() 快照：数字坏输入时回落到已存值 */
  var current = null;
  /** 主题档位：system | light | dark（即时生效，另走 themeSet 桥） */
  var themeMode = "system";

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

  // ---------- 主题（外观卡） ----------
  /** 当前档位的解析结果：桥给最终值，桥缺失时按系统偏好兜底 */
  function resolveTheme(mode) {
    var t = bridge("themeSet", mode);
    if (t === "light" || t === "dark") {
      return t;
    }
    if (mode === "light" || mode === "dark") {
      return mode;
    }
    return matchMedia && matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  }

  /** 指示器动画（水滴：位置/宽度各一条欠阻尼弹簧，移动中纵向压扁） */
  var segAnim = 0;

  function segSpring(el, fromX, fromW, toX, toW) {
    cancelAnimationFrame(segAnim);
    var x = fromX, w = fromW, vx = 0, vw = 0;
    var last = performance.now();
    var kx = 300, rx = 0.58;      // 位置：跟手 + 轻微回弹
    var kw = 240, rw = 0.52;      // 宽度：拉伸更明显
    var cx = 2 * rx * Math.sqrt(kx), cw = 2 * rw * Math.sqrt(kw);

    function frame(now) {
      var dt = Math.min(0.05, (now - last) / 1000) || 0.016;
      last = now;
      var steps = Math.max(1, Math.ceil(dt / (1 / 240))), h = dt / steps;
      for (var i = 0; i < steps; i++) {
        vx += (-kx * (x - toX) - cx * vx) * h; x += vx * h;
        vw += (-kw * (w - toW) - cw * vw) * h; w += vw * h;
      }
      el.style.left = x.toFixed(2) + "px";
      el.style.width = Math.max(8, w).toFixed(2) + "px";
      // 速度越快压得越扁（上限 16%）：一颗水滴被甩出去再弹回来
      var squash = 1 - Math.min(0.16, Math.abs(vx) * 0.00016 + Math.abs(vw) * 0.00006);
      el.style.transform = "scaleY(" + squash.toFixed(3) + ")";
      if (Math.abs(x - toX) < 0.4 && Math.abs(vx) < 8 && Math.abs(w - toW) < 0.4 && Math.abs(vw) < 8) {
        el.style.left = toX + "px";
        el.style.width = toW + "px";
        el.style.transform = "";
        return;
      }
      segAnim = requestAnimationFrame(frame);
    }
    segAnim = requestAnimationFrame(frame);
  }

  /** 指示器跟到当前档位（宽度/位置都量真按钮，别算百分比） */
  function paintSeg(animate) {
    var seg = $("themeSeg");
    if (!seg) {
      return;
    }
    var active = null;
    Array.prototype.forEach.call(seg.querySelectorAll(".seg"), function (b) {
      var on = b.getAttribute("data-theme-mode") === themeMode;
      b.classList.toggle("on", on);
      if (on) {
        active = b;
      }
    });
    var ind = $("segInd");
    if (!active || !ind) {
      return;
    }
    var toX = active.offsetLeft, toW = active.offsetWidth;
    if (animate && ind.style.width) {
      segSpring(ind, parseFloat(ind.style.left) || 4, parseFloat(ind.style.width) || 0, toX, toW);
    } else {
      cancelAnimationFrame(segAnim);
      ind.style.left = toX + "px";
      ind.style.width = toW + "px";
      ind.style.transform = "";
    }
  }

  /** 只按档位算明暗并落 DOM（不碰 prefs；fill 校准用） */
  function applyThemeToDom(mode) {
    var t = mode === "light" || mode === "dark" ? mode : bridge("theme");
    if (t !== "light" && t !== "dark") {
      t = matchMedia && matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
    }
    document.documentElement.setAttribute("data-theme", t);
  }

  function setTheme(mode) {
    themeMode = mode === "light" || mode === "dark" ? mode : "system";
    document.documentElement.setAttribute("data-theme", resolveTheme(themeMode));
    paintSeg(true);
  }

  function fill(s) {
    current = s || {};
    themeMode = current.theme === "light" || current.theme === "dark"
      ? current.theme : "system";
    // 首帧兜底可能按系统深色画错：以 prefs 的档位再校准一次（只改 DOM，不再落盘）
    applyThemeToDom(themeMode);
    paintSeg();
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
      autoVerify: on("swAutoVerify"),
      theme: themeMode
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

  // ---------- 服务器与同步卡 ----------
  /** 原生 SyncEngine.isRunning()（轮询用） */
  var syncRunning = false;

  function fillSync(info) {
    info = info || {};
    syncRunning = !!info.running;
    var paired = !!info.paired;
    var syncBtn = $("btnSync");
    syncBtn.disabled = !paired || syncRunning;
    syncBtn.textContent = syncRunning ? "同步中…" : "立即同步";
    $("btnQr").textContent = paired ? "重新扫码" : "扫码登录";
    $("btnUnpair").hidden = !paired;
    if (!paired) {
      $("syncState").textContent = "未登录";
      $("syncHint").textContent = "扫服务器登录页上的二维码（记录与科目双向同步）";
      return;
    }
    $("syncState").textContent = "已登录 · " + (info.nick || "服务器");
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

    // 外观：点一下立即生效（themeSet 落 prefs + 应用夜间模式，返回解析后的明暗）
    Array.prototype.forEach.call(document.querySelectorAll("#themeSeg .seg"), function (b) {
      b.addEventListener("click", function () {
        setTheme(b.getAttribute("data-theme-mode"));
      });
    });
    window.addEventListener("resize", function () { paintSeg(false); });

    $("btnBack").addEventListener("click", function () { bridge("close"); });
    $("btnSave").addEventListener("click", save);
    $("btnLogs").addEventListener("click", function () { bridge("openLogs"); });
    // 所有说明文字：遇到中文分号就换行（用户口径：一个分号一行）
    Array.prototype.forEach.call(document.querySelectorAll(".hint, .sub"), function (el) {
      var txt = el.textContent || "";
      if (txt.indexOf("；") < 0) {
        return;
      }
      el.innerHTML = esc(txt).split("；").join("；<br>");
    });

    // 服务器与同步：扫码进原生取景页；同步靠轮询拉状态（页内无事件通道）
    refreshSync();
    $("btnQr").addEventListener("click", function () {
      bridge("qrLogin");
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

    // 服务器与同步：扫码进原生取景页；同步靠轮询拉状态（页内无事件通道）
    refreshSync();
    $("btnQr").addEventListener("click", function () {
      bridge("qrLogin");
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

    // 握手：调过 ready 原生才开始推；返回值就是首帧
    fill(bridge("ready"));
  });
})();
