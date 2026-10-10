// 消息渲染纯函数：桌面 src/lib/md.js 的搬运 + 行结构扩展（ATX/setext 标题、GFM 表格、
// 无序列表、分隔线、引用块，语义对齐 python-markdown；抽屉与记录页共用同一份语义，别各自漂移）。
// 数学：$$…$$ / \(…\) / \[…\] / $…$ 走离线 KaTeX（vendor/katex，页面里排在 md.js 之前）；
//       katex 没加载到就回退老的 <span class="math"> 纯文本——不丢内容也不抛。
// 经典脚本挂全局：panel.html / record.html 都用 <script src="md.js"> 引入。
(() => {
  const esc = (s) =>
    String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  // 占位符 = NUL + 序号 + NUL：esc / 加粗 / 链接 / 行结构四步都碰不到它，成品最后原样回填
  const NUL = String.fromCharCode(0);
  const PH = (i) => NUL + i + NUL;
  const PH_RE = new RegExp(NUL + '(\\d+)' + NUL, 'g');

  // 定界符顺序要紧：$$ 必须先于 $ 试，否则 $$x$$ 会被拆成两个行内段
  const MATH = /\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\]|\\\(([^()]+?)\\\)|\$([^$\n]+)\$/g;

  function renderMath(tex, display) {
    const k = globalThis.katex;
    if (!k || typeof k.renderToString !== 'function') {
      return '<span class="math">' + esc(tex) + '</span>'; // 离线兜底
    }
    try {
      return k.renderToString(tex, { displayMode: display, throwOnError: false, strict: false });
    } catch (e) {
      return '<span class="math">' + esc(tex) + '</span>';
    }
  }

  // —— 行结构：ATX 标题 / GFM 表格 / 无序列表 / 分隔线 / 引用块（语义对齐 python-markdown 的
  //    headings + tables 扩展，实测口径：标题可打断段落、表格与列表不可（须空行/块后起）、
  //    `text` 下紧邻 `---`/`===` 是 setext 标题、孤立 `---` 才是分隔线、表体少列补空多列截断、
  //    表头分隔行的 :---: 决定对齐样式）。只吃行结构；粗体/链接/公式/代码在进本函数前已处理，
  //    NUL 占位符全程不带换行与 |，切行切表都碰不到。
  const splitRow = (line) => {
    let t = line.trim();
    if (t.charAt(0) === '|') t = t.slice(1);
    if (t.charAt(t.length - 1) === '|') t = t.slice(0, -1);
    return t.split('|').map((c) => c.trim());
  };
  const isDelimRow = (line) => {
    if (line.indexOf('|') < 0) return false;
    const cells = splitRow(line);
    return cells.length > 0 && cells.every((c) => /^:?-+:?$/.test(c));
  };
  const alignStyle = (c) =>
    c.charAt(0) === ':' && /:$/.test(c) ? ' style="text-align: center;"'
      : /:$/.test(c) ? ' style="text-align: right;"'
      : c.charAt(0) === ':' ? ' style="text-align: left;"' : '';
  const RE_ATX = /^(#{1,6})(.*)$/;
  const RE_SET_EXT = /^\s*={3,}\s*$/;
  const RE_RULE = /^\s*(-{3,}|\*{3,}|_{3,})\s*$/;
  const RE_LI = /^\s*[-*+]\s+(.*)$/;
  // 引用块行首记号：esc 已经把 `>` 转义成 `&gt;`，所以行首认的是 `&gt;`（0~3 空格缩进照 python）
  const RE_BQ = /^ {0,3}&gt; ?(.*)$/;

  function renderBlocks(s) {
    const lines = s.split('\n');
    const out = [];
    let buf = [];         // 待拼接的普通文本行（行间仍用 <br>，保持旧输出一字不差）
    let afterBlock = false;
    const last = () => (buf.length ? buf[buf.length - 1] : undefined);
    const canStart = () => buf.length === 0 || last() === '';   // 不能打断紧邻段落（python 口径）
    const flush = (trimEnd) => {
      if (trimEnd) while (buf.length && last() === '') buf.pop();
      if (buf.length) out.push(buf.join('<br>'));
      buf = [];
    };
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      if (afterBlock && line === '') continue;   // 块元素之间的空行不再落 <br>

      // 引用块：行首 `> ` 起块，可打断段落（python 口径：不须空行，`> ` 直接跟在文本行后也起块）。
      // 块的范围 = 起块行到本段（空行分隔）末尾：段内**非空**行即使没有 `>` 也吞进块里（python
      // 实测 `> 引用\n后文` 的「后文」在块内；这是 lazy 续行，与 CommonMark 同款），但**能再起块
      // 的行**（`#` 标题、`***`/`---` 这类分隔线）不吞——python 实测 `> 甲\n# 标题` 的标题落成
      // 引用块的兄弟节点。段间空行只在后面还有 `> ` 行时才留在块内（实测 `> 甲\n\n> 乙` 合成一个
      // blockquote、空行成为块内段落分隔；`> 甲\n\n后文` 则块在空行处结束）。块内剥掉一层 `> `
      // 后递归走本函数，所以块内标题/表格/列表/hr 与 `>>` 嵌套全部按同一口径落地。
      if (RE_BQ.test(line)) {
        flush(true);
        const inner = [];
        let j = i;
        while (j < lines.length) {
          const cur = lines[j];
          if (cur.trim() === '') {
            let k = j;
            while (k < lines.length && lines[k].trim() === '') k++;
            // 后面没有 `> ` 行了：空行留在外层（afterBlock 会吃掉），块到此为止
            if (k >= lines.length || !RE_BQ.test(lines[k])) break;
            inner.push('');   // 合并跨空行的两段引用：空行降级成块内段落分隔
            j = k;
            continue;
          }
          const q = RE_BQ.exec(cur);
          if (!q && (RE_ATX.test(cur) || RE_RULE.test(cur))) break;   // 再起块的行不吞
          inner.push(q ? q[1] : cur);
          j++;
        }
        out.push('<blockquote>' + renderBlocks(inner.join('\n')) + '</blockquote>');
        afterBlock = true;
        i = j - 1;
        continue;
      }

      // ATX 标题：#~###### 开头（python 口径：不强制 # 后有空格，尾部 # 序列剥掉）
      const atx = RE_ATX.exec(line);
      if (atx) {
        flush(true);
        const lv = atx[1].length;
        const content = atx[2].trim().replace(/#+$/, '').trim();
        out.push('<h' + lv + '>' + content + '</h' + lv + '>');
        afterBlock = true;
        continue;
      }

      // setext（紧邻文本行 + ---/===）→ h2/h1；孤立 ---/`***` → 分隔线；孤立 === → 原样留文本
      if (RE_RULE.test(line) || RE_SET_EXT.test(line)) {
        const eq = RE_SET_EXT.test(line);
        const dash = /^\s*-{3,}\s*$/.test(line);
        if ((eq || dash) && last() !== undefined && last() !== '') {
          const headText = buf.pop();
          flush(true);
          const lv = eq ? 1 : 2;
          out.push('<h' + lv + '>' + headText + '</h' + lv + '>');
        } else if (!eq) {
          flush(true);
          out.push('<hr>');
        } else {
          buf.push(line);
          afterBlock = false;
          continue;
        }
        afterBlock = true;
        continue;
      }

      // GFM 表格：表头行 + 紧随 |---| 分隔行起表（列数必须相等，否则整体按段落原文，
      // python 实测同款）；表体吃非空且带 | 的行，少列补空多列截断
      if (canStart() && line.indexOf('|') >= 0 && isDelimRow(lines[i + 1] || '')) {
        const head = splitRow(line);
        const delimCells = splitRow(lines[i + 1]);
        if (delimCells.length !== head.length) {
          buf.push(line);
          afterBlock = false;
          continue;
        }
        const aligns = delimCells.map(alignStyle);
        flush(true);
        const n = head.length;
        let html = '<table><thead><tr>';
        for (let c = 0; c < n; c++) html += '<th' + aligns[c] + '>' + head[c] + '</th>';
        html += '</tr></thead><tbody>';
        let j = i + 2;
        while (j < lines.length && lines[j].trim() !== '' && lines[j].indexOf('|') >= 0) {
          let cells = splitRow(lines[j]);
          if (cells.length > n) cells = cells.slice(0, n);
          while (cells.length < n) cells.push('');
          html += '<tr>';
          for (let c = 0; c < n; c++) html += '<td' + aligns[c] + '>' + cells[c] + '</td>';
          html += '</tr>';
          j++;
        }
        html += '</tbody></table>';
        out.push(html);
        afterBlock = true;
        i = j - 1;
        continue;
      }

      // 无序列表：连续 `- `/`* `/`+ ` 成一个 <ul>；紧邻段落时不打断（python 口径）
      if (canStart() && RE_LI.test(line)) {
        flush(true);
        let html = '<ul>';
        let li;
        while (i < lines.length && (li = RE_LI.exec(lines[i]))) {
          html += '<li>' + li[1] + '</li>';
          i++;
        }
        i--;
        out.push(html + '</ul>');
        afterBlock = true;
        continue;
      }

      buf.push(line);
      afterBlock = false;
    }
    flush(false);
    return out.join('');
  }

  function md(text) {
    const slots = [];
    const stash = (html) => {
      slots.push(html);
      return PH(slots.length - 1);
    };
    // 统一换行：样本是 CRLF，\r 会让 /^#/、空行判定（setext/表格/列表起表）全失灵
    let s = String(text ?? '').replace(/\r\n?/g, '\n');
    // 代码先摘：`a$b` 里的 $ 不是数学
    s = s.replace(/`([^`]+)`/g, (m, c) => stash('<code>' + esc(c) + '</code>'));
    // 数学再摘：代码已摘空，剩下的 $…$ / $$…$$ / \(…\) / \[…\] 都按公式渲染
    s = s.replace(MATH, (m, d1, d2, d3, d4) => {
      const display = d1 !== undefined || d2 !== undefined;
      const tex = (d1 !== undefined ? d1 : d2 !== undefined ? d2 : d3 !== undefined ? d3 : d4).trim();
      return stash(renderMath(tex, display));
    });
    s = esc(s);
    s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    s = s.replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer">$1</a>');
    // 行结构（标题/表格/列表/分隔线）在此落地，普通文本行间仍换 <br>
    s = renderBlocks(s);
    return s.replace(PH_RE, (m, i) => slots[Number(i)] ?? '');
  }

  globalThis.SporeMD = { esc, md };
})();
