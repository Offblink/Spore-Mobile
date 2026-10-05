// 消息渲染纯函数：桌面 src/lib/md.js 的搬运（抽屉与记录页共用同一份语义，别各自漂移）。
// 数学：$$…$$ / \(…\) / \[…\] / $…$ 走离线 KaTeX（vendor/katex，页面里排在 md.js 之前）；
//       katex 没加载到就回退老的 <span class="math"> 纯文本——不丢内容也不抛。
// 经典脚本挂全局：panel.html / record.html 都用 <script src="md.js"> 引入。
(() => {
  const esc = (s) =>
    String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  // 占位符 = NUL + 序号 + NUL：esc / 加粗 / 链接 / 换行四步都碰不到它，成品最后原样回填
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

  function md(text) {
    const slots = [];
    const stash = (html) => {
      slots.push(html);
      return PH(slots.length - 1);
    };
    let s = String(text ?? '');
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
    s = s.replace(/\n/g, '<br>');
    return s.replace(PH_RE, (m, i) => slots[Number(i)] ?? '');
  }

  globalThis.SporeMD = { esc, md };
})();
