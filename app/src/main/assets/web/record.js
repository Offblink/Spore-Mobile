/* 记录页逻辑：列表（15/页 分页 + 收藏筛选 + 科目筛选 + 页内模态）→ 独立详情视图（转写 + 追问轮询）。
 * 数据：ready/sessions = 索引 meta + 科目表；详情按需 session(id) 单取；改名/删除/收藏/追问/科目四 op 全走原生桥。 */
(() => {
  const { md } = globalThis.SporeMD;
  const $ = (s) => document.querySelector(s);
  const PAGE = 15;
  const BUSY = ["answering", "verifying", "searching"];

  let sessions = [];
  let subjects = [];   // 科目表（subjects.json 全量，chips 与移入弹层共用）
  let subFilter = "";  // 科目筛选："" = 全部
  let pickTarget = null; // 移入弹层目标会话 id（非 null = 弹层开着；开/关都要维护）
  let favOnly = false;
  let page = 0;
  let detail = null;
  let pollTimer = 0;
  let sentAt = 0; // 最近一次追问被接受的时间：15s 宽限（容忍服务冷启动）
  let modalTarget = null;

  // ---------------------------------------------------------------- 列表

  function filtered() {
    // 两维筛选正交（收藏 ∩ 科目）；chips 常驻，不因收藏视图隐藏（比 MV3 的「收藏平铺」更可用）
    return sessions.filter((s) =>
      (!favOnly || s.fav) && (!subFilter || s.subjectId === subFilter));
  }

  function subName(id) {
    const hit = subjects.find((x) => x.id === id);
    return hit ? hit.name : "";
  }

  function refreshSessions() {
    const st = bridge("sessions");
    if (st && st.sessions) {
      sessions = st.sessions;
      subjects = st.subjects || [];
      // 科目被删/筛选失效 → 回落「全部」，别把列表筛成空
      if (subFilter && !subjects.some((x) => x.id === subFilter)) {
        subFilter = "";
        page = 0;
      }
      renderChips();
      renderList(false);
    }
  }

  function renderList(animate) {
    const list = filtered();
    const pages = Math.max(1, Math.ceil(list.length / PAGE));
    if (page > pages - 1) {
      page = pages - 1;
    }
    if (page < 0) {
      page = 0;
    }
    const slice = list.slice(page * PAGE, page * PAGE + PAGE);

    $("#empty").hidden = list.length > 0;
    $("#empty").textContent = subFilter ? "这个科目下还没有会话"
      : favOnly ? "还没有收藏的会话" : "暂无搜题记录";
    $("#rows").innerHTML = slice.map((s) =>
      '<div class="rrow' + (s.fav ? " is-fav" : "") + '" data-id="' + esc(s.id) + '">' +
      '<div class="col"><div class="t">' + esc(s.title || s.id) + "</div>" +
      '<div class="ts">' + fmtTime(s.updated) +
      (s.subjectId ? '<span class="stag">' + esc(subName(s.subjectId) || "已删科目") + "</span>" : "") +
      "</div></div>" +
      '<div class="acts">' +
      '<button class="act f" data-op="fav" type="button">★</button>' +
      '<button class="act m" data-op="move" type="button" title="移入科目">' +
      '<svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" ' +
      'stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<path d="M3 12h10"/><path d="M10 7l5 5-5 5"/><path d="M18 5v14"/></svg></button>' +
      '<button class="act r" data-op="rename" type="button">✎</button>' +
      '<button class="act x" data-op="del" type="button">✕</button>' +
      "</div></div>"
    ).join("");

    $("#pgNum").textContent = (page + 1) + "/" + pages;
    $("#pgPrev").disabled = page === 0;
    $("#pgNext").disabled = page >= pages - 1;

    if (animate) {
      const rows = $("#rows");
      rows.classList.remove("anim-fade-up");
      void rows.offsetWidth; // 重启入场动画
      rows.classList.add("anim-fade-up");
    }
  }

  $("#rows").addEventListener("click", (e) => {
    const row = e.target.closest(".rrow");
    if (!row) {
      return;
    }
    const id = row.dataset.id;
    const op = e.target.closest("[data-op]");
    const meta = sessions.find((x) => x.id === id);
    if (op) {
      if (op.dataset.op === "fav") {
        const ok = bridge("fav", id);
        if (ok === true) {
          toast(meta && meta.fav ? "已取消收藏" : "已收藏");
          refreshSessions();
        } else if (ok === false) {
          toast("找不到这条会话");
        }
        return;
      }
      if (op.dataset.op === "rename") {
        askRename(id, (meta && meta.title) || id);
        return;
      }
      if (op.dataset.op === "move") {
        openPicker(id);
        return;
      }
      if (op.dataset.op === "del") {
        askDelete(id, (meta && meta.title) || id);
        return;
      }
    }
    openDetail(id);
  });

  $("#pgPrev").addEventListener("click", () => {
    page -= 1;
    renderList(true);
  });
  $("#pgNext").addEventListener("click", () => {
    page += 1;
    renderList(true);
  });

  $("#scopeSwitch").addEventListener("click", () => {
    favOnly = !favOnly;
    page = 0;
    $("#scopeSwitch").classList.toggle("on", favOnly);
    $("#labelAll").classList.toggle("on", !favOnly);
    $("#labelFav").classList.toggle("on", favOnly);
    renderList(true);
  });

  $("#btnBack").addEventListener("click", () => bridge("close"));

  // ---------------------------------------------------------------- 科目 chips（按科目筛选；唯一入口，无固定按钮）

  function renderChips() {
    // 全量重建：全部 + 每科目一个。新建/改名/删除统一在行内 ⇥ 移入弹层里做
    const frag = [];
    frag.push('<button class="fchip' + (subFilter ? "" : " on") + '" data-sub="" type="button">全部</button>');
    for (const s of subjects) {
      frag.push('<button class="fchip' + (subFilter === s.id ? " on" : "") +
        '" data-sub="' + esc(s.id) + '" type="button">' + esc(s.name) + "</button>");
    }
    $("#subBar").innerHTML = frag.join("");
  }

  $("#subBar").addEventListener("click", (e) => {
    const chip = e.target.closest(".fchip");
    if (!chip) {
      return;
    }
    subFilter = chip.dataset.sub || "";
    page = 0;
    renderChips();
    renderList(true);
  });

  // ---------------------------------------------------------------- 移入科目弹层（会话 ⇥ 打开；新建/改名/删除都在这一个弹层）

  function openPicker(sessionId) {
    pickTarget = sessionId;
    $("#pickTitle").textContent = "移入科目";
    renderPickList();
    $("#subpick").classList.add("on");
  }

  function renderPickList() {
    const cur = pickTarget
      ? (sessions.find((x) => x.id === pickTarget) || {}).subjectId || ""
      : "";
    const rows = [];
    rows.push('<button class="prow' + (cur ? "" : " on") + '" data-sub="" type="button">' +
      '<span class="pn">未分组</span>' + (cur ? "" : '<span class="pcur">当前</span>') + "</button>");
    for (const s of subjects) {
      rows.push('<button class="prow' + (cur === s.id ? " on" : "") + '" data-sub="' + esc(s.id) +
        '" type="button"><span class="pn">' + esc(s.name) + '</span>' +
        (cur === s.id ? '<span class="pcur">当前</span>' : "") +
        '<span class="pact" data-mgr="ren" title="重命名科目">✎</span>' +
        '<span class="pact danger" data-mgr="del" title="删除科目">✕</span></button>');
    }
    $("#subPickList").innerHTML = rows.length
      ? rows.join("")
      : '<div class="pempty">还没有科目，点下方「新建科目」先建一个</div>';
  }

  $("#subPickList").addEventListener("click", (e) => {
    const mgr = e.target.closest("[data-mgr]");
    const row = e.target.closest(".prow");
    if (!row) {
      return;
    }
    const subId = row.dataset.sub;
    if (mgr && subId) {
      if (mgr.dataset.mgr === "ren") {
        askSubjectRename(subId, row.querySelector(".pn").textContent);
      } else {
        askSubjectDelete(subId, row.querySelector(".pn").textContent);
      }
      return;
    }
    if (pickTarget) {
      const ok = bridge("subjAssign", pickTarget, subId);
      if (ok === true) {
        toast(subId ? "已移入「" + (subName(subId) || "科目") + "」" : "已移出科目");
        closeModal("#subpick");
        pickTarget = null;
        refreshSessions();
      } else if (ok === false) {
        toast("找不到这条会话或科目");
      }
    }
  });

  $("#subPickNo").addEventListener("click", () => {
    closeModal("#subpick");
    pickTarget = null;
  });
  $("#subPickNew").addEventListener("click", () => openNewSubject());
  $("#subpick").addEventListener("click", (e) => {
    if (e.target === $("#subpick")) {
      closeModal("#subpick");
      pickTarget = null;
    }
  });

  /** 新建科目：复用重命名输入模态（同 MV3 的「一个模态两个口径」） */
  function openNewSubject() {
    modalTarget = { kind: "subject-new" };
    $("#renameTitle").textContent = "新建科目";
    $("#renameInput").value = "";
    $("#rename").classList.add("on");
    $("#renameInput").focus();
  }

  function askSubjectRename(id, name) {
    modalTarget = { kind: "subject-ren", id: id };
    $("#renameTitle").textContent = "重命名科目";
    $("#renameInput").value = name;
    $("#rename").classList.add("on");
    $("#renameInput").focus();
    $("#renameInput").select();
  }

  function askSubjectDelete(id, name) {
    modalTarget = { kind: "subject-del", id: id };
    $("#confirmTitle").textContent = "删除科目";
    $("#confirmBody").innerHTML = "删除科目「<b>" + esc(name) +
      "</b>」？<br>科目里的会话只会移出，一个都不会删。";
    $("#confirm").classList.add("on");
  }

  // ---------------------------------------------------------------- 页内模态（层级最高；成功留列表原地）

  function askDelete(id, title) {
    modalTarget = { kind: "session-del", id: id };
    $("#confirmTitle").textContent = "删除会话";
    $("#confirmBody").innerHTML = "确定删除「<b id=\"confirmName\">" + esc(title) +
      "</b>」吗？<br>截图、回答与思考记录会一并删除，不可恢复。";
    $("#confirm").classList.add("on");
  }

  function askRename(id, title) {
    modalTarget = { kind: "session-ren", id: id };
    $("#renameTitle").textContent = "重命名会话";
    $("#renameInput").value = title;
    $("#rename").classList.add("on");
    $("#renameInput").focus();
    $("#renameInput").select();
  }

  function closeModal(sel) {
    $(sel).classList.remove("on");
    modalTarget = null;
  }

  $("#confirmNo").addEventListener("click", () => closeModal("#confirm"));
  $("#confirmYes").addEventListener("click", () => {
    const t = modalTarget;
    closeModal("#confirm");
    if (!t) {
      return;
    }
    if (t.kind === "subject-del") {
      const ok = bridge("subjDelete", t.id);
      if (ok === true) {
        toast("科目已删除，会话已移出");
        refreshSessions();       // 先灌新数据（subjects/sessions 都变）
        if (pickTarget) {
          renderPickList();      // 再重绘弹层，避免拿陈旧行渲染
        }
      } else if (ok === false) {
        toast("找不到该科目");
      }
      return;
    }
    const ok = bridge("delete", t.id);
    if (ok === true) {
      toast("删除成功");
      refreshSessions();
    } else if (ok === false) {
      toast("找不到这条会话");
    }
  });

  $("#renameNo").addEventListener("click", () => closeModal("#rename"));
  $("#renameYes").addEventListener("click", commitRename);
  $("#renameInput").addEventListener("keydown", (e) => {
    e.stopPropagation();
    if (e.key === "Enter") {
      e.preventDefault();
      commitRename();
    } else if (e.key === "Escape") {
      e.preventDefault();
      closeModal("#rename");
    }
  });

  function commitRename() {
    const t = modalTarget;
    const name = ($("#renameInput").value || "").trim();
    closeModal("#rename");
    if (!t || !name) {
      return;
    }
    if (t.kind === "subject-new") {
      const created = bridge("subjCreate", name);
      if (created && created.id) {
        toast("已新建科目「" + (created.name || name) + "」");
        refreshSessions();
        if (pickTarget) {
          renderPickList();
        }
      } else {
        toast("新建失败，名字不能为空");
      }
      return;
    }
    if (t.kind === "subject-ren") {
      const ok = bridge("subjRename", t.id, name);
      if (ok === true) {
        toast("科目已改名");
        refreshSessions();
        if (pickTarget) {
          renderPickList();
        }
      } else {
        toast("找不到该科目");
      }
      return;
    }
    const ok = bridge("rename", t.id, name);
    if (ok === true) {
      toast("重命名成功");
      refreshSessions();
    } else if (ok === false) {
      toast("找不到这条会话");
    }
  }

  $("#confirm").addEventListener("click", (e) => {
    if (e.target === $("#confirm")) {
      closeModal("#confirm");
    }
  });
  $("#rename").addEventListener("click", (e) => {
    if (e.target === $("#rename")) {
      closeModal("#rename");
    }
  });

  // ---------------------------------------------------------------- 详情（独立会话界面）

  function msgAt(i) {
    return detail && detail.messages ? detail.messages[i] : null;
  }

  function verifyHtml(m) {
    if (!m.verifyRan && !m.verifySkipped && !m.verifyPending) {
      return "";
    }
    let badge;
    let cls;
    if (m.verifyRan) {
      badge = m.verifyVerdict === "FIX" ? "核实：FIX（答案已修正）"
        : m.verifyVerdict === "OK" ? "核实：OK" : "已核实";
      cls = m.verifyVerdict === "FIX" ? "fix" : m.verifyVerdict === "OK" ? "ok" : "";
    } else if (m.verifySkipped) {
      badge = "跳过核实（初答自评确定）";
      cls = "skip";
    } else {
      badge = "待核实";
      cls = "";
    }
    const note = m.verifyNote
      ? '<div class="vnote">' + md(m.verifyNote) + "</div>"
      : "";
    return '<div class="verify"><div class="vhead"><span>核实</span>' +
      '<span class="chip ' + cls + '">' + badge + "</span></div>" + note + "</div>";
  }

  function rowHtml(m, i) {
    if (m.role === "user") {
      const img = (m.hasImage && m.imagePath)
        ? '<img class="shot" src="' + imageFor(m.imagePath) + '" data-path="' +
          esc(m.imagePath) + '" alt="题目截图">'
        : "";
      const text = m.text ? '<div class="utext">' + esc(m.text) + "</div>" : "";
      return img || text ? '<div class="msg user">' + img + text + "</div>" : "";
    }
    if (m.kind === "chat") {
      return '<div class="msg bot"><div class="chat">' + md(m.text || "") + "</div></div>";
    }
    const no = (m.no || "").replace(/[^\dA-Za-z]/g, "");
    const head = no ? "第" + no + "题 " : "";
    const ans = head + (m.ans || "");
    const think = m.think
      ? '<div class="think on ' + (m.thinkOpen ? "" : "fold") + '" data-think="' + i + '">' +
        '<button class="think-h" type="button">思考</button>' +
        '<div class="think-b">' + esc(m.think) + "</div></div>"
      : "";
    const tools = (m.tools && m.tools.length)
      ? '<div class="tools">' + m.tools.map((t) => '<div class="tool">' + esc(t) + "</div>").join("") + "</div>"
      : "";
    return '<div class="msg bot" data-mi="' + i + '">' + think +
      (ans ? '<div class="ans">' + md(ans) + "</div>" : "") +
      (m.why ? '<div class="why">' + md(m.why) + "</div>" : "") +
      verifyHtml(m) + tools + "</div>";
  }

  function renderDetail() {
    if (!detail) {
      return;
    }
    $("#dTitle").textContent = detail.title || "";
    const meta = sessions.find((x) => x.id === detail.id);
    $("#dFav").classList.toggle("on", !!(meta && meta.fav));
    const msgs = detail.messages || [];
    $("#dStream").innerHTML = msgs.map(rowHtml).filter(Boolean).join("") ||
      '<div class="dempty">这个会话还没有内容</div>';
  }

  function openDetail(id) {
    const s = bridge("session", id);
    if (!s || !s.id) {
      toast("会话不存在或已删除");
      return;
    }
    detail = s;
    renderDetail();
    $("#listView").hidden = true;
    const d = $("#detailView");
    d.hidden = false;
    d.classList.remove("anim-in-right");
    void d.offsetWidth;
    d.classList.add("anim-in-right");
    maybeStartPoll();
  }

  function closeDetail() {
    stopPoll();
    detail = null;
    $("#detailView").hidden = true;
    $("#listView").hidden = false;
    refreshSessions(); // 回列表即刷新（面板回合落盘后所见即所得）
  }

  $("#dBack").addEventListener("click", closeDetail);

  // 详情里的截图点开全屏查看（取消/保存，与面板同一查看器）
  $("#dStream").addEventListener("click", (e) => {
    const img = e.target.closest(".shot");
    if (img) {
      openShot(img.src, img.dataset.path || "");
    }
  });

  $("#dFav").addEventListener("click", () => {
    if (!detail) {
      return;
    }
    const meta = sessions.find((x) => x.id === detail.id);
    const turningOn = !(meta && meta.fav);
    const ok = bridge("fav", detail.id);
    if (ok === true) {
      toast(turningOn ? "已收藏" : "已取消收藏");
      refreshSessions();
      renderDetail();
    } else if (ok === false) {
      toast("找不到这条会话");
    }
  });

  // 追问：详情页不是引擎监听器（事件只到面板）→ 轮询 session(id) 拉最新
  function sendFollowup() {
    const t = ($("#dInput").value || "").trim();
    if (!t || !detail) {
      return;
    }
    const res = bridge("followup", detail.id, t);
    if (res === "ok") {
      $("#dInput").value = "";
      sentAt = Date.now();
      toast("已发送");
      maybeStartPoll();
    } else if (res === "busy") {
      toast("这条会话正在回答，稍等片刻");
    } else {
      toast("服务未就绪，请稍后再试");
    }
  }

  $("#dSend").addEventListener("click", sendFollowup);
  $("#dInput").addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      sendFollowup();
    }
  });

  function maybeStartPoll() {
    if (pollTimer || !detail) {
      return;
    }
    const busyNow = BUSY.indexOf(detail.status) >= 0;
    if (!busyNow && !sentAt) {
      return;
    }
    pollTimer = setInterval(() => {
      const s = bridge("session", detail.id);
      if (!s || !s.id) {
        stopPoll();
        return;
      }
      detail = s;
      renderDetail();
      const busy = BUSY.indexOf(s.status) >= 0;
      const grace = sentAt && Date.now() - sentAt < 15000;
      if (!busy && !grace) {
        sentAt = 0;
        stopPoll();
      }
    }, 1000);
  }

  function stopPoll() {
    if (pollTimer) {
      clearInterval(pollTimer);
      pollTimer = 0;
    }
  }

  // ---------------------------------------------------------------- 原生钩子 + 启动

  /** 硬件返回键：详情态回列表（消费掉），列表态交原生 finish（挂返回转场） */
  Host.back = function () {
    if (detail) {
      closeDetail();
      return true;
    }
    return false;
  };

  Host.onState = function (st) {
    if (st && st.sessions) {
      sessions = st.sessions;
      subjects = st.subjects || [];
      renderChips();
      renderList(false);
      if (detail) {
        const fresh = sessions.find((x) => x.id === detail.id);
        if (fresh) {
          $("#dTitle").textContent = fresh.title || "";
          $("#dFav").classList.toggle("on", !!fresh.fav);
        }
      }
    }
  };

  const first = bridge("ready");
  if (first && first.sessions) {
    sessions = first.sessions;
    subjects = first.subjects || [];
  }
  renderChips();
  renderList(false);
})();
