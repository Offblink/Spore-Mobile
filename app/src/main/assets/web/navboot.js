/* 底栏入场起点（必须在 <head> 同步加载：要在首帧之前把 data-from 落到 <html>，
 * 否则胶囊会先在终点闪一下、再被 navbar.js 拽回起点，看着像跳帧）。
 *
 * 来源 tab 由原生的 loadUrl 片段带进来（file:///android_asset/...html#from=<tab>）——
 * 切页是 Activity 转场，新页必须知道「从哪一格来」才画得出行程。
 * 片段（#）不参与 file:// 的资源解析，比 query 稳。 */
(function () {
  "use strict";
  var m = /(?:^|[#&])from=([a-z]+)/.exec(location.hash || "");
  if (m && /^(home|records|settings)$/.test(m[1])) {
    document.documentElement.setAttribute("data-from", m[1]);
  }
})();
