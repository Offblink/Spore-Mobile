/* 底部导航：玻璃 Tab 条的点击 / 左右滑动切页 + 入场动画重播 + 滚动最小化
 * （navbar.css 的伴生脚本，三页共用；条本身的样式在 glassbar.css）。
 *
 * 底栏 = Mobile-Design 的 glass-tab-bar（玻璃底栏，三页浮在内容上，见 glassbar.css）。
 * 路由与桥（点击与滑动共用同一张表）：home → close（finish 回主页）；
 * records → openRecords；settings → openSettings；已在本页的 tab 直接 return。
 * 滑动方向：往左滑 = 去右边那一页，往右滑 = 退到左边那一页（与整页平移方向一致）。
 * 判据：横滑 ≥60px 且横向位移 ≥ 纵向 1.6 倍。
 * 四道闸（不抢页面自己的手势）：① 记录页详情视图 / 多选模式开着（那两处底栏已让位）；
 * ② 弹层开着；③ 起手落在横向可滚元素（科目 chips）里；④ 起手落在玻璃条上（那是点按区）。
 * 桥缺失（无头浏览器冒烟）时静默不动作。 */
(function () {
  "use strict";

  var TABS = ["home", "records", "settings"];
  var CUR = document.body.getAttribute("data-tab") || "home";
  var GO = {
    home: "close",
    records: "openRecords",
    settings: "openSettings"
  };
  var root = document.documentElement;
  var clearT = 0;

  function go(tab) {
    if (!tab || tab === CUR) {
      return;
    }
    var method = GO[tab];
    if (method) {
      bridge(method);
    }
  }

  function scheduleClear() {
    clearTimeout(clearT);
    // 动画 300ms；跑完把 data-from 摘掉（不留悬挂属性，重播时才不会误判）
    clearT = setTimeout(function () { root.removeAttribute("data-from"); }, 420);
  }

  /**
   * 重播一次入场（整页平移）。
   * 回主页时主页是「恢复」而不是重载（WebView 没重新加载），原生喊这一声才有动画。
   * 摘属性 → 强制重排 → 再挂上 = 干干净净的第二遍（不断开的话浏览器认为动画没变，不重播）。
   */
  function replay(from) {
    root.removeAttribute("data-from");
    void root.offsetWidth;
    root.setAttribute("data-from", from);
    scheduleClear();
  }
  window.__navEnter = replay;

  /** 这一笔手势该不该由我们接管（页面自己的模式优先） */
  function canSwipe(node) {
    if (document.querySelector(".modal-mask.on")) {
      return false;
    }
    // 玻璃条上是点按区（tab 就在那儿）：起手落在条里就不接管横滑，免得按 tab 的手指
    // 拖出 60px 就莫名其妙翻页
    if (node && node.closest && node.closest(".lg-tabbar")) {
      return false;
    }
    var detail = document.getElementById("detailView");
    if (detail && !detail.hasAttribute("hidden")) {
      return false;   // 记录页详情视图：有追问输入，别抢
    }
    var list = document.getElementById("listView");
    if (list && list.classList.contains("sel")) {
      return false;   // 多选模式：涂抹连选就是横滑
    }
    for (var el = node; el && el !== document.body; el = el.parentElement) {
      var ox = getComputedStyle(el).overflowX;
      if (ox === "auto" || ox === "scroll") {
        return false;   // 横向可滚内容（科目 chips）自己滚
      }
    }
    return true;
  }

  function handleSwipe(dx, dy) {
    if (Math.abs(dx) < 60 || Math.abs(dx) < Math.abs(dy) * 1.6) {
      return;
    }
    var idx = TABS.indexOf(CUR);
    go(TABS[dx < 0 ? idx + 1 : idx - 1]);
  }

  function bindSwipe() {
    var x0 = 0, y0 = 0, tracking = false;
    document.addEventListener("touchstart", function (e) {
      tracking = e.touches.length === 1 && canSwipe(e.target);
      if (tracking) {
        x0 = e.touches[0].clientX;
        y0 = e.touches[0].clientY;
      }
    }, { passive: true });
    document.addEventListener("touchend", function (e) {
      if (!tracking) {
        return;
      }
      tracking = false;
      var t = e.changedTouches && e.changedTouches[0];
      if (t) {
        handleSwipe(t.clientX - x0, t.clientY - y0);
      }
    }, { passive: true });

    // 鼠标/触控板拖拽走同一套判据：桌面 Chromium 里也能验滑动切页（真机走 touch 分支）
    var down = false;
    document.addEventListener("mousedown", function (e) {
      if (e.button !== 0) {
        return;
      }
      down = canSwipe(e.target);
      if (down) {
        x0 = e.clientX;
        y0 = e.clientY;
      }
    });
    document.addEventListener("mouseup", function (e) {
      if (!down) {
        return;
      }
      down = false;
      handleSwipe(e.clientX - x0, e.clientY - y0);
    });
  }

  /**
   * 玻璃条的两件事：点 tab 切页 + 滚动最小化（glass-tab-bar 的签名行为）。
   * 最小化判据取自 Mobile-Design demo：向下滚过 140px 收起标签，回滚或近顶（<80px）弹回。
   * 三页都是整窗滚动（没有内层滚动容器），所以看 window.scrollY；记录页详情视图滚的是它
   * 自己的 .dstream，且那时本条已让位（record.css 收起），不会误触。
   */
  function bindBar() {
    var bar = document.querySelector(".lg-tabbar");
    if (!bar) {
      return;
    }
    // 点当前页的 tab 直接被 go() 挡掉：不重复 startActivity、也不回压栈
    bar.addEventListener("click", function (e) {
      var it = e.target.closest ? e.target.closest(".lg-tab") : null;
      if (it) {
        go(it.getAttribute("data-tab"));
      }
    });

    var lastY = 0;
    var onScroll = function () {
      var y = window.scrollY || document.documentElement.scrollTop || 0;
      if (y > 140 && y > lastY + 2) {
        bar.classList.add("compact");
      } else if (y < lastY - 2 || y < 80) {
        bar.classList.remove("compact");
      }
      lastY = y;
    };
    window.addEventListener("scroll", onScroll, { passive: true });
    onScroll();

    /**
     * 视口几何迟到的接法（2026-10-11 真机「进设置页先沉在很底部再往上跳」）。
     *
     * 机制：切页动画里 `.sheet` 探出视口 → 文档变宽 → 横向滚动条出现/消失（navbar.css 已用
     * `html[data-from]{overflow-x:hidden}` 掐掉）；系统栏 insets 迟到也会让**视口（ICB）缩一截**。
     * 两者都表现为「`bottom` 锚定的条变的是包含块高度、`bottom` 自己的计算值没变」→
     * `transition: bottom` 根本不触发，就是硬跳（morning 那条兜底对这个机制是空转的）。
     *
     * 接法：先按位移把条**推回原位**（此刻还没上屏），下一帧再把 transform 过渡归 0
     * → 顺滑滑到新落位。**只用过渡、元素始终可见**（上午用 opacity 兜位置偏差，结果整条消失）。
     * 两个触发源：`resize` 事件（即时）+ 下面那段限时轮询（兜住「纠正在 load 结束才发生、
     * 且不派发 resize」的那种 —— 真机看到的就是进度条消失时位置才对上）。
     */
    var lastH = window.innerHeight;
    var checkViewport = function () {
      var h = window.innerHeight;
      var dy = lastH - h;   // >0 = 视口缩了，条的新落位比旧落位高 dy
      lastH = h;
      if (Math.abs(dy) < 1) {
        return;             // 只有宽度变（旋转等）：纵向落位没动，别多此一举
      }
      if (getComputedStyle(bar).display === "none") {
        // 记录页详情/多选模式条不在场：清掉可能残留的 transform，回来时是干净位置
        bar.style.transition = "";
        bar.style.transform = "";
        return;
      }
      bar.style.transition = "none";
      bar.style.transform = "translateY(" + dy + "px)";  // 退回旧落位（同帧内，尚未上屏）
      void bar.offsetHeight;                             // 强制重排：让推回值在本次布局生效
      bar.style.transition = "transform var(--t-pop) var(--damped)";
      bar.style.transform = "translateY(0)";
    };
    window.addEventListener("resize", checkViewport);

    // 兜底轮询：真机实测「有进度条时位置是错的、进度条消失才对」——纠正在**载入完成**那一刻
    // 才发生，而那种变化不一定派发 resize（也可能派发在本脚本绑定之前），只挂监听会漏。
    // 头几秒每帧盯一次 innerHeight（没变就是一次读属性，几乎零成本），命中就走同一条推回 +
    // 过渡归 0；到点自己停，绝不常驻烧帧。load/pageshow 各再给一小段窗口。
    var watchStop = 0, watching = false;
    var watchFor = function (ms) {
      watchStop = Math.max(watchStop, Date.now() + ms);
      if (watching) {
        return;
      }
      watching = true;
      (function tick() {
        if (Date.now() > watchStop) {
          watching = false;
          return;
        }
        checkViewport();
        requestAnimationFrame(tick);
      })();
    };
    watchFor(2500);
    window.addEventListener("load", function () { watchFor(2000); });
    window.addEventListener("pageshow", function () { watchFor(1200); });

    /* 几何时间线（**临时诊断，定位后删**）：真机现象是「进度条消失那一刻位置才对上」，
     * 光靠推断已经两轮没猜中 —— 把变化点记下来回写操作日志（设置页 → 日志 → 复制）。
     * 只记数字：ih=布局视口高 / vv=visualViewport 高 / top=条的落位 / st=readyState。
     * 谁变了、变的时机与幅度都在日志里；一条都没变 = 不是几何问题，转查渲染层。 */
    if (window.Spore && typeof window.Spore.geomLog === "function") {
      var t0 = Date.now();
      var snap = function () {
        var vv = window.visualViewport;
        return "ih=" + Math.round(window.innerHeight)
          + " vv=" + (vv ? Math.round(vv.height) : "-")
          + " top=" + bar.getBoundingClientRect().top.toFixed(1)
          + " st=" + document.readyState;
      };
      // 基线**同步**取：否则头 50ms 里发生的变化会被当成基线吞掉
      var prev = snap(), hits = [];
      var iv = setInterval(function () {
        var s = snap();
        if (s !== prev) {
          if (hits.length < 12) {
            hits.push((Date.now() - t0) + "ms " + s);
          }
          prev = s;
        }
        if (Date.now() - t0 > 4000) {   // 4s：要盖过「进度条消失」那一刻（load 结束）
          clearInterval(iv);
          if (hits.length) {
            try { Spore.geomLog("geom[web] " + hits.join(" | ")); } catch (e) { /* 日志是旁路 */ }
          }
        }
      }, 50);
    }
    // 滑完把内联 transform/transition 清干净：留着 translateY(0) 会让后人把内联样式误当状态读，
    // 也会给条常驻一个合成层。只认 transform 的收尾（height 那条是 compact 的，别碰）。
    bar.addEventListener("transitionend", function (e) {
      // 只认条自己的收尾：transitionend 会冒泡，`.lg-tab svg` 的 transform 结束也会打到这儿
      if (e.target !== bar || e.propertyName !== "transform" || bar.style.transform !== "translateY(0px)") {
        return;
      }
      bar.style.transition = "";
      bar.style.transform = "";
    });
  }

  document.addEventListener("DOMContentLoaded", function () {
    // 首次加载：动画在首帧就起跑了（早于这里），只接管收尾清场，别重播——
    // 重播会把已经跑了一半的动画拽回起点，看着就是「闪一下」。
    if (root.getAttribute("data-from")) {
      scheduleClear();
    }
    bindBar();
    bindSwipe();
  });
})();
