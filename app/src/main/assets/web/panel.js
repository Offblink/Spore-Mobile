/* 面板页逻辑 —— 桌面 drawer.js 的行为移植（事件语义对齐 AgentEngine emit 契约）。
 * 数据流：原生推 onState 全量快照 / onEvent 流式增量；动作走 bridge(...)。 */
(() => {
  const { md } = globalThis.SporeMD;
  const $ = (s) => document.querySelector(s);

  const stream = $('#stream');
  const titleEl = $('#title');
  const statusEl = $('#status');
  const favBtn = $('#fav');
  const sessionsBtn = $('#sessions');
  const stopBtn = $('#stop');
  const minBtn = $('#min');
  const listpop = $('#listpop');
  const input = $('#input');
  const sendBtn = $('#send');
  const confirmMask = $('#confirm');
  const confirmName = $('#confirmName');
  const renameMask = $('#rename');
  const renameInput = $('#renameInput');
  const hd = $('#hd');

  let st = { session: null, sessions: [], busy: false };
  let listOn = false;
  // 已读水位（epoch ms）：用户**点开会话**时抬到该会话的 updated（与 native UnreadDotState 同口径）。
  // 红点 = 存在「非当前会话」且 updated > 水位 的会话；面板首次显示时正在看的会话按已读起算。
  // 打开列表**不算**已读（用户 2026-10-10 拍板：是点开会话才消红点，不是点开列表）；
  // 回合结束抬当前会话 updated 只动时间戳、不动水位 → 不会再无读自灭。
  let seenThrough = 0;
  let modalTarget = null; // {id} —— 模态的目标会话

  // ---------------------------------------------------------------- 状态拉取

  function refresh() {
    const s = bridge('state');
    if (s) {
      Host.onState(s);
    }
  }

  function msgAt(i) {
    const ms = (st.session && st.session.messages) || [];
    return ms[i];
  }

  function setBusy(b) {
    st.busy = !!b;
    input.disabled = st.busy;
    sendBtn.disabled = st.busy;
    input.placeholder = st.busy ? '回答生成中…' : '追问…';
    stopBtn.style.visibility = st.busy ? 'visible' : 'hidden';
  }

  function setStatus(text, isErr) {
    statusEl.textContent = text || '';
    statusEl.classList.toggle('on', !!text && !isErr);
    statusEl.classList.toggle('err', !!text && !!isErr);
  }

  // ---------------------------------------------------------------- 渲染：消息流

  function previewHtml(m) {
    const no = (m.no || '').replace(/[^\dA-Za-z]/g, '');
    const head = no ? '第' + no + '题 ' : '';
    // 题号前缀单独 esc、正文单独 md：拼在一起再 md 会让 `## 标题` 顶不掉前缀、首行块元素起不来
    return ((head || m.ans) ? '<div class="ans">' + esc(head) + md(m.ans || '') + '</div>' : '') +
      (m.why ? '<div class="why">' + md(m.why) + '</div>' : '');
  }

  function verifyHtml(m) {
    const status = st.session ? st.session.status : '';
    const live = m.liveNote || '';
    const show = m.verifyRan || m.verifySkipped || m.verifyPending ||
      status === 'verifying' || status === 'searching' || live;
    if (!show) {
      return '';
    }
    let badge = '';
    let cls = '';
    let note = '';
    if (m.verifyRan) {
      badge = m.verifyVerdict === 'FIX' ? '核实：FIX（答案已修正）'
        : m.verifyVerdict === 'OK' ? '核实：OK' : '已核实';
      cls = m.verifyVerdict === 'FIX' ? 'fix' : m.verifyVerdict === 'OK' ? 'ok' : '';
      note = m.verifyNote || '';
    } else if (m.verifySkipped) {
      badge = '跳过核实（初答自评确定）';
      cls = 'skip';
      note = m.verifyNote || '';
    } else if (m.verifyPending) {
      badge = '待核实';
      note = m.verifyNote || '';
    } else if (status === 'searching') {
      badge = '检索中…';
      note = live;
    } else {
      badge = '核实中…';
      note = live;
    }
    // badge 与说明**分行**（第四轮反馈）：vhead 一行、vnote 另起一行
    return '<div class="verify on"><div class="vhead"><span>核实</span>' +
      '<span class="chip ' + cls + '">' + badge + '</span></div>' +
      (note ? '<div class="vnote">' + md(note) + '</div>' : '') +
      '</div>';
  }

  function rowHtml(m, i) {
    if (m.role === 'user') {
      const img = (m.hasImage && m.imagePath)
        ? '<img class="shot" src="' + imageFor(m.imagePath) + '" data-path="' +
          esc(m.imagePath) + '" alt="题目截图">'
        : '';
      const text = m.text ? '<div class="utext">' + esc(m.text) + '</div>' : '';
      if (!img && !text) {
        return '';
      }
      return '<div class="msg user" data-mi="' + i + '">' + img + text + '</div>';
    }
    if (m.kind === 'chat') {
      return '<div class="msg bot chat-row" data-mi="' + i + '">' +
        '<div class="chat">' + md(m.text || '') + '</div></div>';
    }
    const think = m.think
      ? '<div class="think on ' + (m.thinkOpen ? '' : 'fold') + '" data-think="' + i + '">' +
        '<button class="think-h" type="button">思考</button>' +
        '<div class="think-b">' + esc(m.think) + '</div></div>'
      : '';
    const vbtn = (m.verifyPending && !st.busy)
      ? '<button class="vbtn on" type="button" data-v="' + i + '">核实一下</button>'
      : '';
    const tools = (m.tools && m.tools.length)
      ? '<div class="tools">' + m.tools.map((t) => '<div class="tool">' + esc(t) + '</div>').join('') + '</div>'
      : '';
    return '<div class="msg bot" data-mi="' + i + '">' + think +
      previewHtml(m) + verifyHtml(m) + vbtn + tools + '</div>';
  }

  function renderMsgs() {
    const msgs = (st.session && st.session.messages) || [];
    const near = stream.scrollHeight - stream.scrollTop - stream.clientHeight < 90;
    const html = msgs.map(rowHtml).filter(Boolean).join('');
    stream.innerHTML = html || '<div class="empty">还没有会话<br>点悬浮球截一道题开始</div>';
    if (near || st.busy) {
      requestAnimationFrame(() => {
        stream.scrollTop = stream.scrollHeight;
      });
    }
  }

  function renderTitle() {
    titleEl.textContent = (st.session && st.session.title) || '';
  }

  function curMeta() {
    const id = st.session && st.session.id;
    return (st.sessions || []).find((x) => x.id === id) || null;
  }

  function renderFav() {
    const m = curMeta();
    favBtn.classList.toggle('on', !!(m && m.fav));
  }

  function renderUnread() {
    // 红点语义 = **有我从未点开过的其它会话比已读水位更新**（不是「存在其它会话」——
    // 那样只要库里有 2 条就必亮；也不是「比当前会话更新」——回合结束把当前会话
    // updated 抬到全库最新会让它无读自灭，第四轮/2026-10-10 两轮实测都抓过）。
    const cur = st.session;
    if (!cur) {
      sessionsBtn.classList.toggle("has-unread", false);
      return;
    }
    if (!seenThrough) {
      seenThrough = cur.updated || 0; // 面板首次显示：正在看的这个按已读起算
    }
    const hit = (st.sessions || []).some(
      (x) => x.id !== cur.id && (x.updated || 0) > seenThrough);
    sessionsBtn.classList.toggle("has-unread", hit);
  }

  function renderList() {
    const cur = st.session && st.session.id;
    const list = st.sessions || [];
    if (!list.length) {
      listpop.innerHTML = '<div class="listempty">还没有会话<br>截一道题就开始了</div>';
      return;
    }
    listpop.innerHTML = list.map((e) => {
      const running = (st.running || []).indexOf(e.id) >= 0;
      const cls = 'srow' + (e.fav ? ' fav' : '') + (e.id === cur ? ' active' : '');
      return '<div class="' + cls + '" data-id="' + esc(e.id) + '">' +
        '<button class="act f" data-op="fav" type="button">★</button>' +
        '<div class="col"><div class="t">' + esc(e.title || e.id) + '</div>' +
        '<div class="ts">' + fmtShort(e.updated) +
        (running ? '<span class="gen"> · 生成中</span>' : '') + '</div></div>' +
        '<button class="act r" data-op="rename" type="button">✎</button>' +
        '<button class="act x" data-op="del" type="button">✕</button>' +
        '</div>';
    }).join('');
  }

  function renderAll() {
    renderTitle();
    renderFav();
    renderUnread();
    renderList();
    renderMsgs();
    setBusy(st.busy);
  }

  // ---------------------------------------------------------------- 事件

  Host.onState = (s) => {
    if (!s) {
      return;
    }
    st = s;
    if (!st.session) {
      st.session = { messages: [] };
    }
    if (!st.session.messages) {
      st.session.messages = [];
    }
    renderAll();
  };

  Host.onEvent = (ev) => {
    if (!ev || !ev.type) {
      return;
    }
    // 并行回合（第九轮）：别的会话的流式事件一律不进当前屏——当前屏只画自己那个会话。
    // 列表的红点/行数据靠结构事件后原生追推的 state 刷新，不靠这些增量。
    const curId = st.session && st.session.id;
    if (ev.sid && curId && ev.sid !== curId) {
      return;
    }
    const ms = (st.session && st.session.messages) || [];
    switch (ev.type) {
      case 'title':
        if (st.session) {
          st.session.title = ev.title || '';
        }
        renderTitle();
        break;

      case 'status':
        setStatus(ev.text || '');
        break;

      case 'answer-start':
        setBusy(true);
        setStatus('读题中…');
        renderMsgs();
        break;

      case 'chat-start':
        setBusy(true);
        setStatus('');
        renderMsgs();
        break;

      case 'answer-delta': {
        const m = ms[ev.idx];
        if (!m) {
          break;
        }
        m.no = ev.no;
        m.title = ev.title;
        m.ans = ev.ans;
        m.why = ev.why;
        // 正文开始流入 = 思考结束（用户没手动接管过就自动收起）
        if (!m.userFolded && m.ans) {
          m.thinkOpen = false;
        }
        renderMsgs();
        break;
      }

      case 'think-delta': {
        if ((ev.kind || 'answer') !== 'answer') {
          break;
        }
        const m = ms[ev.idx];
        if (!m) {
          break;
        }
        m.think = ev.think || '';
        if (!m.userFolded && !m.ans) {
          m.thinkOpen = true; // 思考进行中默认展开（桌面心智）
        }
        renderMsgs();
        break;
      }

      case 'tool': {
        const m = ms[ev.idx];
        if (!m) {
          break;
        }
        if (!m.tools) {
          m.tools = [];
        }
        m.tools.push((ev.name === 'web' ? '读取 ' : '检索 ') + (ev.brief || ''));
        renderMsgs();
        break;
      }

      case 'verify-delta': {
        const m = ms[ev.idx];
        if (!m) {
          break;
        }
        if (ev.done) {
          m.verifyNote = ev.note || '';
          m.verifyVerdict = ev.verdict || '';
          m.verifyRan = !!ev.ran;
          m.verifySkipped = !!ev.skipped;
          m.verifyPending = !!ev.pending;
          m.liveNote = '';
        } else {
          m.liveNote = ev.note || '';
          if (ev.ran !== undefined) {
            m.verifyRan = !!ev.ran;
          }
          if (ev.pending !== undefined) {
            m.verifyPending = !!ev.pending;
          }
        }
        renderMsgs();
        break;
      }

      case 'chat-delta': {
        const m = ms[ev.idx];
        if (!m) {
          break;
        }
        m.text = ev.total || '';
        renderMsgs();
        break;
      }

      case 'turn-end':
        setBusy(false);
        if (ev.aborted) {
          setStatus('已停止');
        } else if (ev.error) {
          setStatus('');
        } else {
          setStatus('完成');
        }
        break;

      case 'error':
        setBusy(false);
        setStatus('出错了', true);
        toast(ev.message || '失败');
        break;

      case 'session-new':
        // 删除当前会话后引擎会换新会话并发这条——**不许**顺手关掉列表
        //（第四轮反馈：重命名/删除后要留在列表里）。列表只在点行切换时关。
        if (st.session) {
          st.session.title = ev.title || '';
        }
        renderTitle();
        break;

      default:
        break;
    }
  };

  // ---------------------------------------------------------------- listpop

  function openList() {
    listOn = true;
    // 打开列表不抬水位、不清红点：清红点唯一触发 = 用户点开会话（见 renderUnread）
    renderUnread();
    renderList();
    listpop.classList.add('on');
  }

  function closeList() {
    listOn = false;
    listpop.classList.remove('on');
  }

  sessionsBtn.addEventListener('click', () => {
    if (listOn) {
      closeList();
    } else {
      openList();
    }
  });

  listpop.addEventListener('click', (e) => {
    // 收藏会在当帧 refresh() 重建 innerHTML → 冒泡到 document 时 target 已脱链
    // → closest('#listpop') 落空 → 被误判成「点空白」关掉列表（第五轮反馈）。
    // 列表内的点击一律在源头拦住，关列表只属于行切换与真正的空白。
    e.stopPropagation();
    const row = e.target.closest('.srow');
    if (!row) {
      return;
    }
    const id = row.dataset.id;
    const op = e.target.closest('[data-op]');
    if (op && op.dataset.op === 'fav') {
      const ok = bridge('fav', id);
      if (ok === true) {
        const meta = (st.sessions || []).find((x) => x.id === id);
        toast(meta && meta.fav ? '已取消收藏' : '已收藏');
        refresh();
      } else if (ok === false) {
        toast('找不到这条会话');
      }
      return;
    }
    if (op && op.dataset.op === 'rename') {
      const meta = (st.sessions || []).find((x) => x.id === id);
      askRename(id, (meta && meta.title) || id);
      return;
    }
    if (op && op.dataset.op === 'del') {
      const meta = (st.sessions || []).find((x) => x.id === id);
      askDelete(id, (meta && meta.title) || id);
      return;
    }
    // 点行 = 切入该会话（仅当前会话也关一下列表）——**点开会话 = 已读**：
    // 抬水位到「刚在看的那个」与「即将打开的这条」的 updated（清红点的唯一触发；
    // 前者也要算：它一直在屏幕上，回合结束把自己的 updated 抬成最新，不许回来点亮红点）
    const cur = st.session && st.session.id;
    if (id !== cur) {
      const ok = bridge('open', id);
      if (ok === true) {
        const meta = (st.sessions || []).find((x) => x.id === id);
        seenThrough = Math.max(seenThrough,
          (st.session && st.session.updated) || 0, (meta && meta.updated) || 0);
        closeList();
        refresh();
      } else if (ok === false) {
        toast('找不到这条会话');
      }
    } else {
      closeList();
    }
  });

  // 点空白关列表：模态里的点击不算（第四轮反馈）；💬 自己也不算
  document.addEventListener('click', (e) => {
    if (e.target.closest('.modal-mask')) {
      return;
    }
    if (e.target.closest('#listpop') || e.target.closest('#sessions')) {
      return;
    }
    if (listOn) {
      closeList();
    }
  });

  // ---------------------------------------------------------------- 模态（层级高于 listpop；成功只关模态不关列表）

  function askDelete(id, title) {
    modalTarget = { id: id };
    confirmName.textContent = title;
    confirmMask.classList.add('on');
  }

  function askRename(id, title) {
    modalTarget = { id: id };
    renameInput.value = title || '';
    renameMask.classList.add('on');
    renameInput.focus();
    renameInput.select();
  }

  function closeModal(mask) {
    mask.classList.remove('on');
    modalTarget = null;
  }

  $('#confirmNo').addEventListener('click', () => closeModal(confirmMask));
  $('#confirmYes').addEventListener('click', () => {
    const t = modalTarget;
    closeModal(confirmMask);
    if (!t) {
      return;
    }
    const ok = bridge('delete', t.id);
    if (ok === true) {
      toast('删除成功');
      refresh(); // 列表留着，只换数据
    } else if (ok === false) {
      toast('正在回答，稍等片刻再操作');
    }
  });

  $('#renameNo').addEventListener('click', () => closeModal(renameMask));
  $('#renameYes').addEventListener('click', commitRename);
  renameInput.addEventListener('keydown', (e) => {
    e.stopPropagation();
    if (e.key === 'Enter') {
      e.preventDefault();
      commitRename();
    } else if (e.key === 'Escape') {
      e.preventDefault();
      closeModal(renameMask);
    }
  });

  function commitRename() {
    const t = modalTarget;
    const name = (renameInput.value || '').trim();
    closeModal(renameMask);
    if (!t || !name) {
      return;
    }
    const ok = bridge('rename', t.id, name);
    if (ok === true) {
      toast('重命名成功');
      refresh(); // 列表留着
    } else if (ok === false) {
      toast('正在回答，稍等片刻再操作');
    }
  }

  confirmMask.addEventListener('click', (e) => {
    if (e.target === confirmMask) {
      closeModal(confirmMask);
    }
  });
  renameMask.addEventListener('click', (e) => {
    if (e.target === renameMask) {
      closeModal(renameMask);
    }
  });
  window.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') {
      return;
    }
    if (confirmMask.classList.contains('on')) {
      closeModal(confirmMask);
    } else if (renameMask.classList.contains('on')) {
      closeModal(renameMask);
    }
  });

  // ---------------------------------------------------------------- 顶栏动作 + 拖动

  favBtn.addEventListener('click', () => {
    const cur = st.session && st.session.id;
    if (!cur) {
      return;
    }
    const meta = curMeta();
    const turningOn = !(meta && meta.fav);
    const ok = bridge('fav', cur);
    if (ok === true) {
      toast(turningOn ? '已收藏' : '已取消收藏');
      refresh();
    } else if (ok === false) {
      toast('正在回答，稍等片刻再操作');
    }
  });

  stopBtn.addEventListener('click', () => bridge('stop'));
  minBtn.addEventListener('click', () => bridge('hide'));

  let dragStart = null;
  let dragging = false;
  let justDragged = false;
  hd.addEventListener('touchstart', (e) => {
    dragStart = { x: e.touches[0].screenX, y: e.touches[0].screenY };
    dragging = false;
    bridge('drag', 'down', 0, 0);
  }, { passive: true });
  hd.addEventListener('touchmove', (e) => {
    if (!dragStart) {
      return;
    }
    const dx = e.touches[0].screenX - dragStart.x;
    const dy = e.touches[0].screenY - dragStart.y;
    if (!dragging && Math.abs(dx) < 4 && Math.abs(dy) < 4) {
      return;
    }
    dragging = true;
    const dpr = window.devicePixelRatio || 1;
    bridge('drag', 'move', Math.round(dx * dpr), Math.round(dy * dpr));
    e.preventDefault();
  }, { passive: false });
  hd.addEventListener('touchend', () => {
    justDragged = dragging;
    dragStart = null;
    dragging = false;
  });
  // 刚拖完不许顺手触发光标下的按钮
  hd.addEventListener('click', (e) => {
    if (justDragged) {
      justDragged = false;
      e.stopPropagation();
      e.preventDefault();
    }
  }, true);

  // ---------------------------------------------------------------- 消息流交互（事件委托）

  stream.addEventListener('click', (e) => {
    const thinkH = e.target.closest('.think-h');
    if (thinkH) {
      const box = thinkH.closest('.think');
      const m = msgAt(Number(box.dataset.think));
      if (m) {
        m.userFolded = true;
        m.thinkOpen = !m.thinkOpen;
        renderMsgs();
      }
      return;
    }
    const vb = e.target.closest('.vbtn');
    if (vb) {
      bridge('verifyNow');
      return;
    }
    const img = e.target.closest('.shot');
    if (img) {
      openShot(img.src, img.dataset.path || ''); // 点图 → 全屏查看（取消/保存）
    }
  });

  // ---------------------------------------------------------------- composer

  function submit() {
    const t = (input.value || '').trim();
    if (!t || st.busy) {
      return;
    }
    input.value = '';
    bridge('followup', t);
  }

  sendBtn.addEventListener('click', submit);
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      submit();
    }
  });

  // 原生入口（球长按抽屉：CaptureService→AnswerPanel.eval）——显式开/关/切换
  window.SporePanel = {
    openList: openList,
    closeList: closeList,
    toggleList: function () {
      if (listOn) {
        closeList();
      } else {
        openList();
      }
    }
  };

  // ---------------------------------------------------------------- 启动：ready 握手

  const first = bridge('ready');
  if (first) {
    Host.onState(first);
  }
})();
