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
