/* 启动页动画：粉点从 S 外侧沿弧线飞到 e 外侧落地（不碰字母）→ 压扁回弹（水滴感），
 * 点动画 1.1s；落地（638ms）后底部小字从左往右打字出现 + 粉光标闪，
 * 原生 onPageFinished + 2.2s 跳主页。路径锚点全部按实测量；纯展示，无桥。 */
(() => {
  const stage = document.getElementById("stage");
  const word = document.getElementById("word");
  const fly = document.getElementById("fly");
  const DUR = 1100;
  const TYPE_AFTER = 700;   // 落地压扁之后起打（638ms 撞地 + 62ms 缓冲）
  const TYPE_STEP = 65;     // 每字间隔
  const TEXT = "让答案触手可及";

  function boot() {
    const st = stage.getBoundingClientRect();
    const sR = document.getElementById("lS").getBoundingClientRect();
    const eR = document.getElementById("lE").getBoundingClientRect();
    const base = document.getElementById("bl").getBoundingClientRect().bottom - st.top;
    const fs = parseFloat(getComputedStyle(word).fontSize);
    const r = fly.offsetWidth / 2;
    const top = sR.top - st.top;                 // 字顶（span 框顶）
    const gap = fs * 0.35;                       // 起落点离 S/e 的间隔（用户：不要紧贴）

    // 三次贝塞尔：两端控制点竖直抬高 → 起降近垂直、贴着字母外侧走，
    // 横向进入字母区之前已爬到字顶 + r + 8 之上 → 轨迹全程不与字母重合。
    const x0 = sR.left - st.left - gap;          // S 尾巴左侧（留间隔）
    const x2 = eR.right - st.left + gap;         // e 尾巴右侧（留间隔，落点=那颗小粉点）
    const L = x2 - x0;
    // x(t)/L = t²(3-2t)；点的右缘触到 S 左缘时 x0 偏移 = gap - r → 解出该 t
    const target = Math.min(0.99, Math.max(0.01, (gap - r) / L));
    let lo = 0;
    let hi = 1;
    for (let i = 0; i < 24; i++) {               // 单调函数，二分足够
      const mid = (lo + hi) / 2;
      if (mid * mid * (3 - 2 * mid) < target) {
        lo = mid;
      } else {
        hi = mid;
      }
    }
    const te = (lo + hi) / 2;
    const need = (base - top) + r + 8;           // 入字区时离基线应有高度
    const rise = need / (3 * te * (1 - te));     // y(t) = base - rise·3t(1-t)
    const cy = base - rise;

    fly.style.offsetPath = 'path("M ' + x0 + ' ' + base + ' C ' + x0 + ' ' + cy +
      ', ' + x2 + ' ' + cy + ', ' + x2 + ' ' + base + '")';

    // 系统关动效：直接摆终态（点在落点、小字全出），不飞不打字；原生 2.2s 跳转不变
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      fly.style.offsetDistance = "100%";
      fly.style.transform = "scale(1)";
      const tagR = document.getElementById("tag");
      tagR.classList.add("on");
      document.getElementById("tagText").textContent = TEXT;
      return;
    }

    // 一条时间线：pop 出生 → 弧线飞行 → 落地压扁 → 回弹拉伸 → 小幅余震 → 静止
    // transform-origin = 底边中心 = 路径锚点，压扁自然以基线为「地面」
    fly.animate([
      { offset: 0,    offsetDistance: "0%",   transform: "scale(0.2)", opacity: 0, easing: "ease-out" },
      { offset: 0.06, offsetDistance: "0%",   transform: "scale(1)",   opacity: 1, easing: "cubic-bezier(.45,0,.25,1)" },
      { offset: 0.58, offsetDistance: "100%", transform: "scale(1)",   easing: "cubic-bezier(.3,0,.4,1)" },
      { offset: 0.66, offsetDistance: "100%", transform: "scale(1.75,.5)",  easing: "ease-out" },   // 撞地压扁
      { offset: 0.78, offsetDistance: "100%", transform: "scale(.8,1.3)",   easing: "ease-in-out" }, // 水滴回弹拉伸
      { offset: 0.88, offsetDistance: "100%", transform: "scale(1.18,.86)", easing: "ease-in-out" }, // 余震
      { offset: 1,    offsetDistance: "100%", transform: "scale(1)",   easing: "ease-out" }
    ], { duration: DUR, fill: "forwards", easing: "linear" });

    // 圆点落地后：底部小字打字出现（先亮行 → 光标已在闪 → 逐字左往右）
    setTimeout(() => {
      const tag = document.getElementById("tag");
      const tagText = document.getElementById("tagText");
      tag.classList.add("on");
      let i = 0;
      const tick = setInterval(() => {
        i += 1;
        tagText.textContent = TEXT.slice(0, i);
        if (i >= TEXT.length) {
          clearInterval(tick); // 打完只剩光标继续闪，等原生跳转
        }
      }, TYPE_STEP);
    }, TYPE_AFTER);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
