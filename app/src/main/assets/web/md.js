// 消息渲染纯函数：桌面 src/lib/md.js 的搬运 + 行结构扩展（ATX/setext 标题、GFM 表格、
// 无序/有序/嵌套列表、引用块、分隔线、围栏与 4 空格缩进代码；行内 粗体/斜体/链接/图片/转义，
// 语义对齐 python-markdown 的 headings+tables+fenced_code+nl2br）。抽屉与记录页共用同一份语义，
// 别各自漂移。口径全部按本机 python-markdown 3.9 实测（对照脚本见 tools 之外的本机实验脚本）。
// 数学：$$…$$ / \(…\) / \[…\] / $…$ 走离线 KaTeX（vendor/katex，页面里排在 md.js 之前）；
//       katex 没加载到就回退老的 <span class="math"> 纯文本——不丢内容也不抛。
// 经典脚本挂全局：panel.html / record.html 都用 <script src="md.js"> 引入。
(() => {
  const esc = (s) =>
    String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  // 占位符 = NUL + 序号 + NUL：esc / 强调 / 链接 / 行结构四步都碰不到它，成品最后原样回填
  const NUL = String.fromCharCode(0);
  const PH = (i) => NUL + i + NUL;
  // 块级占位符（围栏代码 / 4 空格缩进代码整块）：多带一个 F，好让 renderBlocks 认出来
  // 「这行是块元素，单独成行落地、别并进相邻文本行」，而行内公式/行内代码的占位符照旧按文本流拼
  const PH_BLOCK = (i) => NUL + 'F' + i + NUL;
  // 转义占位符（`\*` 这类，python ESCAPE_RE）：同样按文本流拼，只是躲开后面的强调/链接/块结构识别
  const PH_ESC = (i) => NUL + 'E' + i + NUL;
  const PH_RE = new RegExp(NUL + '([FE]?)(\\d+)' + NUL, 'g');

  // 摘下来的片段（代码/公式/强调/图片/链接/转义字面量）都进这里，成品最后按占位符序号填回
  let slots = [];
  const stash = (html) => {
    slots.push(html);
    return PH(slots.length - 1);
  };

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

  // —— 行内：转义字面量 → 图片 → 链接 → 非强调守卫 → 粗体/斜体（python inlinepatterns 的优先级顺序）。
  // 图片/链接摘成占位符，一是让属性里的 * _ 不被强调规则啃掉（python 图片 alt 原样输出），
  // 二是链接文字要单独过一遍强调（python 对匹配到的节点内部用更低优先级再跑一遍）。
  // 反斜杠转义集合 = python Markdown.ESCAPED_CHARS（\ ` * _ { } [ ] ( ) > # + - . !）+ tables 扩展追加的 |；
  // 它必须早于强调/链接/块结构识别落地（`\*` 不是强调、`\- ` 不是列表、`\#` 不是标题），
  // 但 esc 已把 `>` 变成 &gt;，所以回填的是 esc(字面量)。`\$` 不在此集合里（python 同款，原样留 `\$`）。
  const RE_ESCAPE = /\\([\\`*_{}\[\]()>#+\-.!|])/g;
  const RE_IMG = /!\[([^\]]*)\]\(\s*(https?:\/\/[^\s)]+?)(?:\s+(&quot;([\s\S]*?)&quot;|'([\s\S]*?)'))?\s*\)/g;
  const RE_LINK = /(?<!!)\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g;
  // python NOT_STRONG_RE：被空白夹着（或行首/行尾）的 * ** *** / _ __ ___ 只是字面记号。
  // 行首单个 `*` 例外——那可能是无序列标记号，留给 renderBlocks 认（python 也是先切块后行内）。
  const RE_NOT_STRONG = /(^|[^\S\n])(\*{1,3}|_{1,3})(?=[^\S\n]|$)/gm;
  // python EmStrong 口径实测：***x***/___x___ → strong+em；**x** → strong（内容可再含 *，如 **a*b**）；
  // *x* → em（星号进词内也认，a*b*c）；_/__ 带词边界（(?<!\w)…(?!\w)，python 的 \w 含 CJK），
  // 所以 foo_bar_baz / 1_2_3 / _斜体_中文 不算强调。DOTALL：可跨行（[^*] / [\s\S] 天然跨行）。
  const W = '[\\p{L}\\p{N}_]';
  const RE_STRONG_EM_STAR = /\*\*\*([^*]+)\*\*\*/g;
  const RE_STRONG_EM_US = new RegExp('(?<!' + W + ')___(?!_)([\\s\\S]+?)(?<!_)___(?!' + W + ')', 'gu');
  const RE_STRONG_STAR = /\*\*([\s\S]+?)\*\*/g;
  const RE_STRONG_US = new RegExp('(?<!' + W + ')__(?!_)([\\s\\S]+?)(?<!_)__(?!' + W + ')', 'gu');
  const RE_EM_STAR = /\*([^*]+)\*/g;
  const RE_EM_US = new RegExp('(?<!' + W + ')_(?!_)([\\s\\S]+?)(?<!_)_(?!' + W + ')', 'gu');

  function emphasis(s) {
    return s
      .replace(RE_STRONG_EM_STAR, '<strong><em>$1</em></strong>')
      .replace(RE_STRONG_EM_US, '<strong><em>$1</em></strong>')
      .replace(RE_STRONG_STAR, '<strong>$1</strong>')
      .replace(RE_STRONG_US, '<strong>$1</strong>')
      .replace(RE_EM_STAR, '<em>$1</em>')
      .replace(RE_EM_US, '<em>$1</em>');
  }

  function inline(s) {
    s = s.replace(RE_IMG, (m, alt, src, titleTok, titleDq, titleSq) => {
      const title = titleDq !== undefined ? titleDq : titleSq;
      return stash('<img alt="' + alt + '" src="' + src + '"' +
        (title !== undefined ? ' title="' + title + '"' : '') + '>');
    });
    s = s.replace(RE_LINK, (m, text, href) =>
      stash('<a href="' + href + '" target="_blank" rel="noreferrer">' + emphasis(text) + '</a>'));
    s = s.replace(RE_NOT_STRONG, (m, pre, run) => {
      if (pre === '' && run === '*') return m;   // 行首单个 * = 列表记号，留给 renderBlocks
      slots.push(run);
      return pre + PH_ESC(slots.length - 1);
    });
    return emphasis(s);
  }

  // —— 行结构：ATX 标题 / GFM 表格 / 列表（无序、有序、嵌套）/ 分隔线 / 引用块 — 语义对齐
  //    python-markdown 实测口径：标题可打断段落、表格与列表不可（须空行/块后起）、
  //    `text` 下紧邻 `---`/`===` 是 setext 标题、孤立 `---` 才是分隔线、表体少列补空多列截断、
  //    表头分隔行的 :---: 决定对齐样式。只吃行结构；强调/链接/图片/公式/代码在进本函数前已处理，
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
  // 列表项一行：缩进 + 记号（无序 `- * +` / 有序 `1.`）+ 空白 + 正文。python 实测：
  // 有序认 `1. ` 不认 `1)`；无序列记号可混用（- * + 同一 ul）；4 空格缩进的 `- `/`1. ` 是代码块不是列表。
  const RE_LIST_ITEM = /^(\s*)([-*+]|\d+\.)[ \t]+(.*)$/;
  // 围栏代码整块（python fenced_code 的预处理器同款）：顶格 + 编号 ≥3 + 语言串单记号 +
  // 闭合围栏与开启**逐字符相同**（\1 反向引用，实测 4 个 ` 开、3 个 ` 闭不上）；未闭合不匹配。
  const RE_FENCE_BLOCK = /^(~{3,}|`{3,})[ ]*\{?\.?([a-zA-Z0-9_+-]*)\}?[ ]*\n([\s\S]*?)(?<=\n)\1[ ]*(?=\n|$)/gm;
  // 引用块行首记号：esc 已经把 `>` 转义成 `&gt;`，所以行首认的是 `&gt;`（0~3 空格缩进照 python）
  const RE_BQ = /^ {0,3}&gt; ?(.*)$/;
  // 引用块行首前缀（可嵌套 `> > `）：缩进代码提取早于 esc，所以这里认的是**裸** `>`；
  // 落地时前缀原样带进占位符行，等 esc 之后再交给 renderBlocks 的 `&gt;` 口径吃
  const RE_BQ_PREFIX = /^( {0,3}(?:> ?)+)([\s\S]*)$/;
  const RE_BLOCK_PH = /^\x00F\d+\x00$/;

  const isMarkerLine = (line) => RE_LIST_ITEM.test(line);
  const indentOf = (s) => {
    let n = 0;
    for (let k = 0; k < s.length; k++) {
      const c = s[k];
      if (c === ' ') n++;
      else if (c === '\t') n += 4;   // python 把 tab 当 4 空格
      else break;
    }
    return n;
  };
  const stripIndent = (s, n) => {
    let k = 0, w = 0;
    while (k < s.length && w < n) {
      const c = s[k];
      if (c === ' ') { w++; k++; }
      else if (c === '\t') { w += 4; k++; }
      else break;
    }
    return s.slice(k);
  };

  // —— 4 空格缩进代码块（python IndentedCodeProcessor 实测口径）：起块要「块起点」（文本开头、
  //    空行之后、或紧跟在标题/分隔线/围栏/代码块这类块元素之后——`text\n    x` 不是代码，
  //    `# h\n    y` 与围栏后紧跟的缩进行都是），起块后连续吃掉「缩进 ≥4」的行，行内空行保留、
  //    块尾空行丢掉，内容按原样（再剥一层 4 空格）esc 后进 <pre><code>；4 空格缩进与列表**互斥**：
  //    列表记号行 / 列表内缩进行（列表上下文未关）不算代码（`- a\n\n      code` 归列表）。
  //    引用块内同理：按剥掉 `&gt; ` 前缀后的正文缩进判定，落地时占位符行带上原前缀，
  //    好让 renderBlocks 的引用块递归里也认得出这是块元素。
  function extractCode(s) {
    const lines = s.split('\n');
    const out = [];
    let listOpen = false;      // 上一行还在列表上下文里（含列表内缩进行）
    let pendingBlank = true;   // 处在块起点（文本开头 / 空行后 / 块元素后）
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const pm = RE_BQ_PREFIX.exec(line);
      const prefix = pm ? pm[1] : '';
      const body = pm ? pm[2] : line;
      if (body.trim() === '') { pendingBlank = true; out.push(line); continue; }
      const ind = indentOf(body);
      if (ind >= 4 && pendingBlank && !listOpen) {
        const buf = [];
        let j = i, blanks = 0;
        while (j < lines.length) {
          const cm = RE_BQ_PREFIX.exec(lines[j]);
          const cp = cm ? cm[1] : '';
          const cb = cm ? cm[2] : lines[j];
          if (cb.trim() === '') { blanks++; j++; continue; }
          if (cp !== prefix || indentOf(cb) < 4) break;
          while (blanks > 0) { buf.push(''); blanks--; }
          buf.push(stripIndent(cb, 4));
          j++;
        }
        slots.push('<pre><code>' + esc(buf.join('\n') + '\n') + '</code></pre>');
        out.push(prefix + PH_BLOCK(slots.length - 1));
        i = j - 1;
        pendingBlank = true;   // 代码块也是块元素
        listOpen = false;
        continue;
      }
      if (isMarkerLine(body) && ind <= 3) listOpen = true;
      else if (ind === 0) listOpen = false;
      pendingBlank = RE_BLOCK_PH.test(body) || RE_ATX.test(body) || RE_RULE.test(body);
      out.push(line);
    }
    return out.join('\n');
  }

  // —— 列表（含嵌套）。python 实测缩进阈值：子列表要比父项缩进**多 4 空格**（`- a\n  - b` 是同级
  //    两个 li，`- a\n    - b` 才是 li 内嵌 ul）；缩进 1~3 一律当同级兄弟项，且子列表类型由子项
  //    记号自己定（`- a` 下 `    1. b` 是嵌 ol，`1. a` 下 `    - b` 是嵌 ul）。项内续行（无记号的
  //    非空行）进 li 里用 <br> 续（python 同款，缩进按本层剥掉）；空行收尾（python 的 loose list
  //    会把 li 正文包 <p>，本实现保持 tight，见 known）。返回 [html, 下一行下标]。
  function parseList(lines, start) {
    const m0 = RE_LIST_ITEM.exec(lines[start]);
    const baseIndent = m0[1].length;
    const ordered = /^\d+\.$/.test(m0[2]);
    let html = ordered ? '<ol>' : '<ul>';
    let i = start;
    while (i < lines.length) {
      const m = RE_LIST_ITEM.exec(lines[i]);
      if (!m) break;
      const ind = m[1].length;
      if (ind < baseIndent || ind >= baseIndent + 4) break;   // 更浅/更深都不归本层
      let li = m[3];
      i++;
      while (i < lines.length) {
        const line = lines[i];
        if (line.trim() === '') break;                        // 空行收尾
        const cm = RE_LIST_ITEM.exec(line);
        if (cm) {
          const ci = cm[1].length;
          if (ci >= baseIndent + 4) {                         // 更深 → 子列表进 li
            const sub = parseList(lines, i);
            li += sub[0];
            i = sub[1];
            continue;
          }
          break;                                              // 同级/更浅 → 交回外层
        }
        if (RE_ATX.test(line) || RE_RULE.test(line) || RE_BQ.test(line) || RE_BLOCK_PH.test(line)) {
          break;                                              // 能再起块的行不吞（python 口径）
        }
        li += '<br>' + stripIndent(line, baseIndent);          // 续行
        i++;
      }
      html += '<li>' + li + '</li>';
    }
    return [html + (ordered ? '</ol>' : '</ul>'), i];
  }

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

      // 围栏/缩进代码块（md() 层已摘成整块占位符 NUL+F+序号+NUL）：单独成块落地，
      // 不并进相邻文本行（否则前后会多出 <br>）
      if (RE_BLOCK_PH.test(line)) {
        flush(true);
        out.push(line);
        afterBlock = true;
        continue;
      }

      // 引用块：行首 `> ` 起块，可打断段落（python 口径：不须空行，`> ` 直接跟在文本行后也起块）。
      // 块的范围 = 起块行到本段（空行分隔）末尾：段内**非空**行即使没有 `>` 也吞进块里（python
      // 实测 `> 引用\n后文` 的「后文」在块内；这是 lazy 续行，与 CommonMark 同款），但**能再起块
      // 的行**（`#` 标题、`***`/`---` 这类分隔线）不吞——python 实测 `> 甲\n# 标题` 的标题落成
      // 引用块的兄弟节点。段间空行只在后面还有 `> ` 行时才留在块内（实测 `> 甲\n\n> 乙` 合成一个
      // blockquote、空行成为块内段落分隔；`> 甲\n\n后文` 则块在空行处结束）。块内剥掉一层 `> `
      // 后递归走本函数，所以块内标题/表格/列表/代码/hr 与 `>>` 嵌套全部按同一口径落地。
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
      // python 实测同款）；表体吃非空且带 | 的行，少列补空多列截断。
      // 表格外面包一层 .tablewrap：窄屏/长内容时横向滚动（CSS overflow-x:auto），
      // 表格本体仍是 <table>（border-collapse:collapse 那套样式不变）。
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
        let html = '<div class="tablewrap"><table><thead><tr>';
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
        html += '</tbody></table></div>';
        out.push(html);
        afterBlock = true;
        i = j - 1;
        continue;
      }

      // 列表（无序/有序/嵌套一体）：紧邻段落不打断（python 口径，与无序列表同款）；
      // 起块行的缩进 0~3（≥4 那种在 extractCode 里已经按代码块摘走了）
      const lm = RE_LIST_ITEM.exec(line);
      if (canStart() && lm && lm[1].length <= 3) {
        flush(true);
        const li = parseList(lines, i);
        out.push(li[0]);
        afterBlock = true;
        i = li[1] - 1;
        continue;
      }

      buf.push(line);
      afterBlock = false;
    }
    flush(false);
    return out.join('');
  }

  function md(text) {
    slots = [];
    // 统一换行：样本是 CRLF，\r 会让 /^#/、空行判定（setext/表格/列表起表）全失灵
    let s = String(text ?? '').replace(/\r\n?/g, '\n');
    // 围栏代码必须先摘（同 python fenced_code 的预处理顺序）：`([^`]+)` 会把 ``` 的开闭反引号
    // 配成一对、把整段当行内代码吃掉。顶格 + 编号 ≥3 + 语言串单记号 + **闭合围栏与开启逐字符
    // 相同**（python 用反向引用）；块内只 esc、不再走 markdown（python 口径：块内不解析）。
    // 引用块里的围栏天然不匹配（python 实测 `> ``` ` 不是围栏）——这里同样要求行首即记号。
    s = s.replace(RE_FENCE_BLOCK, (m, fence, lang, body) => {
      const cls = lang ? ' class="language-' + lang + '"' : '';
      slots.push('<pre><code' + cls + '>' + esc(body) + '</code></pre>');
      return PH_BLOCK(slots.length - 1);
    });
    // 4 空格缩进代码块（同样要在行内处理之前摘走，块内不解析 markdown）
    s = extractCode(s);
    // 代码先摘：`a$b` 里的 $ 不是数学。`(?<!\\)` 对齐 python BACKTICK_RE：`\`` 不是行内代码定界符
    s = s.replace(/(?<!\\)`([^`]+)`/g, (m, c) => stash('<code>' + esc(c) + '</code>'));
    // 数学再摘：代码已摘空，剩下的 $…$ / $$…$$ / \(…\) / \[…\] 都按公式渲染
    s = s.replace(MATH, (m, d1, d2, d3, d4) => {
      const display = d1 !== undefined || d2 !== undefined;
      const tex = (d1 !== undefined ? d1 : d2 !== undefined ? d2 : d3 !== undefined ? d3 : d4).trim();
      return stash(renderMath(tex, display));
    });
    // 反斜杠转义：早于 esc/图片/链接/强调落地（`\*` 不是强调、`\- ` 不是列表、`\#` 不是标题）。
    // 回填的是 esc(字面量)——`\>` 要落成 &gt; 而不是裸标签。行内代码/公式/代码块已先摘走，
    // 所以块内 `\*` 保持原样（python 同款：代码里不转义）。
    s = s.replace(RE_ESCAPE, (m, c) => {
      slots.push(esc(c));
      return PH_ESC(slots.length - 1);
    });
    s = esc(s);
    // 行内：图片/链接/粗体/斜体（python 口径，详见上面 inline 的注释）
    s = inline(s);
    // 行结构（标题/表格/列表/分隔线/引用块/代码块）在此落地，普通文本行间仍换 <br>
    s = renderBlocks(s);
    // 多趟回填：链接文字里的图片之类会产生「占位符套占位符」（Python 侧也是同名 placeholder 机制）
    for (let pass = 0; pass < 8 && s.indexOf(NUL) >= 0; pass++) {
      s = s.replace(PH_RE, (m, f, i) => slots[Number(i)] ?? '');
    }
    return s;
  }

  globalThis.SporeMD = { esc, md };
})();
