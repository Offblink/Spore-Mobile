/* 记录页逻辑：列表（分组视图：各科目 + 未分组，一滑到底长页面 + 收藏筛选 + 科目筛选 + 页内模态）→ 独立详情视图（转写 + 追问轮询）。
 * 多选：长按卡片进多选模式（左边圆形单选框）；从单选框起笔涂抹连选——起笔在已选卡片上
 *       这笔先取消，否则先选择；中途折返即换向（段起点挪到拐点）；多选下支持批量收藏/移入/删除。
 * 数据：ready/sessions = 索引 meta + 科目表；详情按需 session(id) 单取；改名/删除/收藏/追问/科目四 op 全走原生桥。 */
(() => {
  const { md } = globalThis.SporeMD;
  const $ = (s) => document.querySelector(s);
  const BUSY = ["answering", "verifying", "searching"];

  let sessions = [];
  let subjects = [];   // 科目表（subjects.json 全量，chips 与移入弹层共用）
  let subFilter = "";  // 科目筛选："" = 全部
  let pickTargets = null; // 移入弹层目标会话 id 数组（非 null = 弹层开着；单条 = [id]）
  let favOnly = false;
  let detail = null;
  let pollTimer = 0;
  let sentAt = 0; // 最近一次追问被接受的时间：15s 宽限（容忍服务冷启动）
  let modalTarget = null;

  // ---------------------------------------------------------------- 多选模式状态
  let selecting = false;      // 长按进入；取消按钮/返回键/删空退出（切科目不退——跨科目多选，Round 17）
  let selected = new Set();   // 选中的会话 id（renderList 按它回放 .on）
  let suppressClick = false;  // 长按进模式或涂抹收笔后的那次 click 要吃掉

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
      }
      renderChips();
      renderList(false);
    }
  }

  /** 单行卡 html（分组视图共用；勾选态按 selected 回放）。
   *  注意别叫 rowHtml——详情视图有同名函数（渲染消息行），同一 IIFE 作用域会互相覆盖 */
  function rowCardHtml(s) {
    return '<div class="rrow' + (s.fav ? " is-fav" : "") + (selected.has(s.id) ? " on" : "") +
      '" data-id="' + esc(s.id) + '">' +
      '<span class="ck" data-ck><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
      'stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<path d="M5 13l4 4 10-10"/></svg></span>' +
      '<div class="col"><div class="t">' + esc(s.title || s.id) + "</div>" +
      '<div class="ts">' + fmtTime(s.updated) + "</div></div>" +
      '<div class="acts">' +
      '<button class="act f" data-op="fav" type="button">★</button>' +
      '<button class="act m" data-op="move" type="button" title="移入科目">' +
      '<svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" ' +
      'stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<path d="M3 12h10"/><path d="M10 7l5 5-5 5"/><path d="M18 5v14"/></svg></button>' +
      '<button class="act r" data-op="rename" type="button">✎</button>' +
      '<button class="act x" data-op="del" type="button">✕</button>' +
      "</div></div>";
  }

  function renderList(animate) {
    const list = filtered();

    // 选中集按现有会话裁剪：删掉的/不在库里的不再算选中；裁空就直接退多选
    if (selecting) {
      const valid = new Set(sessions.map((s) => s.id));
      selected.forEach((id) => { if (!valid.has(id)) selected.delete(id); });
      if (!selected.size) {
        setSelecting(false);
      }
    }

    $("#empty").hidden = list.length > 0;
    $("#empty").textContent = subFilter ? "这个科目下还没有会话"
      : favOnly ? "还没有收藏的会话" : "暂无搜题记录";

    // 分组视图（用户拍板：不再平铺全部会话）：各科目一组 +「未分组」收尾。
    // 宁滥勿缺：会话必落且只落一组——subjectId 对得上科目表的进该组，
    // 空/悬挂的进「未分组」兜底；分组总和 = list。空组不出头（计数照样对得上）。
    const buckets = new Map(); // 组键（"" = 未分组）→ 行 html[]
    for (const s of list) {
      const key = s.subjectId && subjects.some((x) => x.id === s.subjectId)
        ? s.subjectId : "";
      if (!buckets.has(key)) {
        buckets.set(key, []);
      }
      buckets.get(key).push(rowCardHtml(s));
    }
    const out = [];
    const pushGroup = (name, rows) => {
      if (rows && rows.length) {
        out.push('<div class="rhead">' + esc(name) +
          '<span class="rhn">' + rows.length + "</span></div>");
        out.push.apply(out, rows);
      }
    };
    for (const sub of subjects) {
      pushGroup(sub.name, buckets.get(sub.id));
    }
    pushGroup("未分组", buckets.get(""));
    $("#rows").innerHTML = out.join("");

    syncSelChrome();

    if (animate) {
      const rows = $("#rows");
      rows.classList.remove("anim-fade-up");
      void rows.offsetWidth; // 重启入场动画
      rows.classList.add("anim-fade-up");
    }
  }

  // ---------------------------------------------------------------- 多选模式（长按进入；顶栏/底栏随模式切换）

  function setSelecting(on) {
    selecting = on;
    if (!on) {
      selected.clear();
      // 行上的 .on 只在 renderList 里按 selected 回放：退多选不重绘 → 勾残留
      // （再次进多选「上次的还在勾着」的根因）→ 就地复位所有行的勾选态
      for (const el of $("#rows").querySelectorAll(".rrow.on")) {
        el.classList.remove("on");
      }
    }
    $("#listView").classList.toggle("sel", on);
    $("#batchbar").hidden = !on;
    syncSelChrome();
  }

  /** 顶栏标题（模式下变「已选 N 项」）+ 底栏计数与可用性，随选中集实时同步 */
  function syncSelChrome() {
    $("#ptitle").textContent = selecting ? "已选 " + selected.size + " 项" : "搜题记录";
    $("#selCount").textContent = "已选 " + selected.size;
    const none = !selected.size;
    $("#bFav").disabled = none;
    $("#bMove").disabled = none;
    $("#bDel").disabled = none;
  }

  function rowEl(id) {
    return document.querySelector('#rows .rrow[data-id="' + id + '"]');
  }

  /** 单卡勾选：改集合 + 只刷这一格的 .on（不起整列表重绘，滚动位置不丢） */
  function setRowSel(id, on) {
    if (on === selected.has(id)) {
      return;
    }
    if (on) {
      selected.add(id);
    } else {
      selected.delete(id);
    }
    const el = rowEl(id);
    if (el) {
      el.classList.toggle("on", on);
    }
    syncSelChrome();
  }

  // ---------------------------------------------------------------- 列表点击（模式里单击 = 勾选；否则走原单条操作）

  $("#rows").addEventListener("click", (e) => {
    if (suppressClick) {
      suppressClick = false;
      return;
    }
    const row = e.target.closest(".rrow");
    if (!row) {
      return;
    }
    const id = row.dataset.id;
    if (selecting) {
      setRowSel(id, !selected.has(id));
      return;
    }
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
        openPicker([id]);
        return;
      }
      if (op.dataset.op === "del") {
        askDelete(id, (meta && meta.title) || id);
        return;
      }
    }
    openDetail(id);
  });

  // ---------------------------------------------------------------- 多选手势：长按进模式；单选框起笔涂抹（可反选）
  //
  // 语义（用户拍板）：从某个单选框开始拖 = 涂抹，选中「当前段起点 → 笔尖所在卡」之间全部；
  // 起笔卡已选 → 本笔先反着来（取消选择）。**中途可换向**：笔尖行号一折返，
  // 方向立刻翻转、新段从拐点起算——1,2,3 ↘ −3,−2,−1 ↘ 1,2,3 一段一段接力。
  // 涂抹只认单选框起笔：卡片其余区域的拖动留给滚动（长页面一滑到底）。
  // 笔尖贴到上下缘（顶栏下 / 批量底栏上）→ 列表自动滚，滚过的行继续涂（见下「边界自动滚动」）。
  const HOLD_MS = 500;   // 长按进多选的判定时长
  const SLOP = 10;       // px：超过就算「动了」（滚动/涂抹），不算长按/点选
  let holdTimer = 0;     // 长按定时器（非 0 = 挂着）
  let holdRow = null;    // 长按落点卡
  let holdX = 0;
  let holdY = 0;
  let held = false;      // 本笔长按已触发（收笔的 click 要吃掉）
  let paint = null;      // 本笔涂抹 {seg, prev, dx, dir, moved}

  function rowsArr() {
    // 只收行卡：#rows 里混着分组头（.rhead），按 children 索引会把头当行（涂抹跨组会错位）
    return Array.prototype.slice.call($("#rows").querySelectorAll(".rrow"));
  }

  function rowUnder(x, y) {
    const el = document.elementFromPoint(x, y);
    return el ? el.closest(".rrow") : null;
  }

  /** 当前段起点 ↔ 笔尖卡 之间整段落选中/取消（段内重放，幂等） */
  function paintRange(toIdx) {
    const arr = rowsArr();
    const a = Math.min(paint.seg, toIdx);
    const b = Math.max(paint.seg, toIdx);
    for (let i = a; i <= b; i++) {
      const el = arr[i];
      if (el) {
        setRowSel(el.dataset.id, paint.dir);
      }
    }
  }

  /** 笔尖挪到第 idx 行：落选中/取消；中途折返（行号方向翻）即换向，新段从拐点起算 */
  function paintTo(idx) {
    if (idx < 0 || idx === paint.prev) {
      return;
    }
    const d = idx > paint.prev ? 1 : -1;
    if (paint.dx && d !== paint.dx) {
      paint.dir = !paint.dir;
      paint.seg = paint.prev;
    }
    paint.dx = d;
    paint.prev = idx;
    paint.moved = true;
    paintRange(idx);
  }

  // ---------------------------------------------------------------- 边界自动滚动（用户 Round 18）
  //
  // 涂抹笔尖贴到屏幕上下缘（顶栏之下 / 批量底栏之上）时，列表自己滚起来，滚过的行继续按
  // 当前方向涂抹——手指停在边上不用来回挪，就能把一整屏选完。笔尖不动也会滚：滚动期间没有
  // pointermove，靠 requestAnimationFrame 拿最后笔尖位置持续取行（rowUnder）接着涂。
  const EDGE = 90;       // px：上下各留这么高的滚动带（一行卡 ≈ 82px，留一点余量）
  const AUTO_MIN = 3;    // px/帧：刚进带的保底速度
  const AUTO_MAX = 16;   // px/帧：贴到最边上的上限（60fps 下 ≈ 960px/s）
  let autoRaf = 0;
  const lastPt = { x: 0, y: 0 }; // 笔尖最后位置（滚动中无 pointermove，靠它取行）

  /** 笔尖高度 → 本帧滚动量（+ 下滚 / − 上滚 / 0 = 不在带里）；离边越近越快 */
  function edgeDy(y) {
    const top = document.querySelector("#listView .topbar").getBoundingClientRect().bottom;
    const bar = $("#batchbar");
    const bottom = bar.hidden ? window.innerHeight : bar.getBoundingClientRect().top;
    const speed = (depth) => Math.round(AUTO_MIN + (AUTO_MAX - AUTO_MIN) * (1 - depth / EDGE));
    if (y >= top && y < top + EDGE) {
      return -speed(y - top);
    }
    if (y > bottom - EDGE && y <= bottom) {
      return speed(bottom - y);
    }
    return 0;
  }

  function autoTick() {
    autoRaf = 0;
    if (!paint || !selecting) {
      return;
    }
    const dy = edgeDy(lastPt.y);
    if (!dy) {
      return; // 出带了：等下一次 pointermove 再启动
    }
    const before = window.scrollY;
    window.scrollBy(0, dy);
    if (window.scrollY === before) {
      return; // 到顶/到底滚不动就停，别空转
    }
    const over = rowUnder(lastPt.x, lastPt.y); // 滚了一行 → 笔尖下的行换了，接着涂
    if (over) {
      paintTo(rowsArr().indexOf(over));
    }
    autoRaf = requestAnimationFrame(autoTick);
  }

  /** 每笔 pointermove 同步一次：进带就起滚动循环，出带/收笔就停 */
  function autoSync(e) {
    lastPt.x = e.clientX;
    lastPt.y = e.clientY;
    const wanted = !!(paint && selecting && edgeDy(e.clientY));
    if (wanted && !autoRaf) {
      autoRaf = requestAnimationFrame(autoTick);
    } else if (!wanted && autoRaf) {
      cancelAnimationFrame(autoRaf);
      autoRaf = 0;
    }
  }

  function autoStop() {
    if (autoRaf) {
      cancelAnimationFrame(autoRaf);
      autoRaf = 0;
    }
  }

  $("#rows").addEventListener("pointerdown", (e) => {
    if (!e.isPrimary) {
      return; // 多指只认主指，别让第二根手指搅局
    }
    suppressClick = false;
    const row = e.target.closest(".rrow");
    if (!row) {
      return;
    }
    if (e.target.closest(".ck") && selecting) {
      // 涂抹起笔：首段方向看起笔格（起在已选上 = 本笔先取消）；seg = 起笔格
      const idx = rowsArr().indexOf(row);
      paint = { seg: idx, prev: idx, dx: 0, dir: !selected.has(row.dataset.id), moved: false };
      e.preventDefault(); // 这笔不给 WebView 当滚动
      return;
    }
    if (selecting) {
      return; // 模式里卡片区域：点选交给 click，拖动留给滚动
    }
    // 非模式：挂长按（越过 slop 即撤，别把滚动误判成长按）
    holdRow = row;
    holdX = e.clientX;
    holdY = e.clientY;
    held = false;
    holdTimer = setTimeout(() => {
      holdTimer = 0;
      held = true;
      setSelecting(true);
      setRowSel(holdRow.dataset.id, true);
      holdRow = null;
    }, HOLD_MS);
  });

  $("#rows").addEventListener("pointermove", (e) => {
    if (!e.isPrimary) {
      return;
    }
    if (holdTimer) {
      const dx = e.clientX - holdX;
      const dy = e.clientY - holdY;
      if (dx * dx + dy * dy > SLOP * SLOP) {
        clearTimeout(holdTimer);
        holdTimer = 0;
        holdRow = null; // 动了 = 滚动手势，长按作废
      }
      return;
    }
    if (paint && selecting) {
      autoSync(e);
      const over = rowUnder(e.clientX, e.clientY);
      if (over) {
        paintTo(rowsArr().indexOf(over));
      }
      e.preventDefault();
    }
  });

  function endStroke() {
    autoStop();
    if (holdTimer) {
      clearTimeout(holdTimer);
      holdTimer = 0;
      holdRow = null;
    }
    if (paint) {
      if (paint.moved) {
        suppressClick = true; // 涂抹收笔那下别再触发 click
      } else {
        // 单选框上的轻点（没动）：就地翻选，吃掉随后的 click 防双翻
        const row = rowsArr()[paint.seg];
        if (row) {
          setRowSel(row.dataset.id, !selected.has(row.dataset.id));
        }
        suppressClick = true;
      }
      paint = null;
    }
    if (held) {
      suppressClick = true; // 长按进模式的那笔，收尾 click 不能把刚勾上的又翻回去
      held = false;
    }
  }

  $("#rows").addEventListener("pointerup", (e) => {
    if (e.isPrimary) {
      endStroke();
    }
  });
  $("#rows").addEventListener("pointercancel", (e) => {
    if (e.isPrimary) {
      endStroke();
      suppressClick = false; // cancel 后没有 click，别把标志留给下一笔
    }
  });
  // 长按不该弹 WebView 的文字选择/右键菜单
  $("#rows").addEventListener("contextmenu", (e) => e.preventDefault());

  $("#selCancel").addEventListener("click", () => setSelecting(false));

  // ---------------------------------------------------------------- 批量操作（多选底栏）

  /** 批量收藏：全已收藏 → 这次统一取消；否则把没收藏的都收上 */
  $("#bFav").addEventListener("click", () => {
    const sel = sessions.filter((s) => selected.has(s.id));
    if (!sel.length) {
      return;
    }
    const toFav = sel.some((s) => !s.fav);
    let n = 0;
    for (const s of sel) {
      if (!!s.fav === toFav) {
        continue; // 已是目标状态，别再翻
      }
      if (bridge("fav", s.id) === true) {
        n += 1;
      }
    }
    toast(n ? (toFav ? "已收藏 " + n + " 条" : "已取消收藏 " + n + " 条") : "操作失败");
    refreshSessions();
  });

  /** 批量移入科目：整包丢给移入弹层（多目标版 openPicker） */
  $("#bMove").addEventListener("click", () => {
    if (selected.size) {
      openPicker(Array.from(selected));
    }
  });

  /** 批量删除：确认框带条数，确定后逐条走同一座桥，成功即退多选 */
  $("#bDel").addEventListener("click", () => {
    if (!selected.size) {
      return;
    }
    modalTarget = { kind: "session-del-batch" };
    $("#confirmTitle").textContent = "删除会话";
    $("#confirmBody").innerHTML = "确定删除选中的 <b>" + selected.size +
      "</b> 条会话吗？<br>截图、回答与思考记录会一并删除，不可恢复。";
    $("#confirm").classList.add("on");
  });

  $("#scopeSwitch").addEventListener("click", () => {
    if (selecting) {
      setSelecting(false); // 换筛选前先退多选：选中集要跟着视图走
    }
    favOnly = !favOnly;
    $("#scopeSwitch").classList.toggle("on", favOnly);
    $("#labelAll").classList.toggle("on", !favOnly);
    $("#labelFav").classList.toggle("on", favOnly);
    renderList(true);
  });

  /** 顶栏「‹」：多选中先退多选（与硬件返回同一条出口），否则关页 */
  $("#btnBack").addEventListener("click", () => {
    if (selecting) {
      setSelecting(false);
      return;
    }
    bridge("close");
  });

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
    // 多选跨科目（用户 Round 17 追加）：切 chips 不退多选——选中集按全量 sessions 裁剪
    // （renderList 不看筛选集），跨科目选中后批量移入/删除照常生效
    subFilter = chip.dataset.sub || "";
    renderChips();
    renderList(true);
  });

  // ---------------------------------------------------------------- 大标题（HIG large title：34pt 随内容滚走，
  // 滚过顶栏后紧凑标题淡入顶栏；同时点亮 scroll-edge 渐隐带）
  const listBar = $("#listView .topbar");
  const bigTitle = $("#bigTitle");
  function syncTitleChrome() {
    listBar.classList.toggle("scrolled",
      bigTitle.getBoundingClientRect().bottom <= listBar.getBoundingClientRect().bottom);
  }
  window.addEventListener("scroll", syncTitleChrome, { passive: true });
  syncTitleChrome();

  // ---------------------------------------------------------------- 移入科目弹层（会话 ⇥ 打开；新建/改名/删除都在这一个弹层）

  /** 目标可为多条（多选底栏批量移入）；单条 = [id]，与原行为等价 */
  function openPicker(ids) {
    pickTargets = ids;
    $("#pickTitle").textContent = "移入科目" +
      (ids.length > 1 ? "（" + ids.length + " 条）" : "");
    renderPickList();
    $("#subpick").classList.remove("out"); // 撤退中的滑下 → 取消，避免到点定时器卸掉新弹层
    $("#subpick").classList.add("on");
  }

  function renderPickList() {
    // 多条同科目才亮「当前」；科目不一致（mixed）则不亮任何行
    const subs = (pickTargets || []).map(
      (id) => (sessions.find((x) => x.id === id) || {}).subjectId || "");
    const mixed = new Set(subs).size > 1;
    const cur = mixed ? null : (subs[0] || "");
    const rows = [];
    rows.push('<button class="prow' + (!mixed && !cur ? " on" : "") + '" data-sub="" type="button">' +
      '<span class="pn">未分组</span>' + (!mixed && !cur ? '<span class="pcur">当前</span>' : "") + "</button>");
    for (const s of subjects) {
      const on = !mixed && cur === s.id;
      rows.push('<button class="prow' + (on ? " on" : "") + '" data-sub="' + esc(s.id) +
        '" type="button"><span class="pn">' + esc(s.name) + '</span>' +
        (on ? '<span class="pcur">当前</span>' : "") +
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
    if (pickTargets && pickTargets.length) {
      let ok = 0;
      for (const id of pickTargets) {
        if (bridge("subjAssign", id, subId) === true) {
          ok += 1;
        }
      }
      if (ok) {
        const msg = pickTargets.length > 1
          ? "已将 " + ok + " 条" + (subId ? "移入「" + (subName(subId) || "科目") + "」" : "移出科目")
          : (subId ? "已移入「" + (subName(subId) || "科目") + "」" : "已移出科目");
        toast(msg);
        closeModal("#subpick");
        pickTargets = null;
        refreshSessions();
      } else {
        toast("找不到这条会话或科目");
      }
    }
  });

  $("#subPickNo").addEventListener("click", () => {
    closeModal("#subpick");
    pickTargets = null;
  });
  $("#subPickNew").addEventListener("click", () => openNewSubject());
  $("#subpick").addEventListener("click", (e) => {
    if (e.target === $("#subpick")) {
      closeModal("#subpick");
      pickTargets = null;
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
    const el = $(sel);
    modalTarget = null;
    if (sel === "#subpick") {
      // 底部弹层：damped 滑下再卸（330ms > .3s 动画）；重开时 openPicker 会先撤 .out，
      // 到点的定时器见到 .out 已不在 → 不动新弹层
      if (!el.classList.contains("on") || el.classList.contains("out")) return;
      el.classList.add("out");
      setTimeout(() => {
        if (el.classList.contains("out")) el.classList.remove("on", "out");
      }, 330);
      return;
    }
    el.classList.remove("on");
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
        if (pickTargets) {
          renderPickList();      // 再重绘弹层，避免拿陈旧行渲染
        }
      } else if (ok === false) {
        toast("找不到该科目");
      }
      return;
    }
    if (t.kind === "session-del-batch") {
      // 批量删除：先把选中集抄出来（refresh 会裁剪 selected），逐条走同一座桥
      const ids = Array.from(selected);
      let n = 0;
      for (const id of ids) {
        if (bridge("delete", id) === true) {
          n += 1;
        }
      }
      toast(n ? "已删除 " + n + " 条" : "找不到这些会话");
      setSelecting(false);
      refreshSessions();
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
        if (pickTargets) {
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
        if (pickTargets) {
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

  /** 硬件返回键：详情态回列表 → 多选态退多选（都消费掉），列表态才交原生 finish */
  Host.back = function () {
    if (detail) {
      closeDetail();
      return true;
    }
    if (selecting) {
      setSelecting(false);
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
