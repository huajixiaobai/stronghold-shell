package com.stronghold.shell

/**
 * 游戏界面显示修正：注入一段 CSS，在页面渲染前生效。
 *
 * 【解决什么问题】
 *   对局画面左上角「盟约」横条两端，会冒出两个灰黑色的 ‹ › 方块按钮。
 *   它们其实是游戏自带的滚动箭头，不是壳加的 —— 但在这块屏幕上会被误点亮，
 *   看起来像多出来的按键。
 *
 * 【为什么会被点亮】
 *   上游 `js/ui/bondStrip.js` 里判断能否滚动的写法是：
 *
 *       right = el.scrollLeft + el.clientWidth < el.scrollWidth - 1
 *
 *   只要 `scrollWidth` 比 `clientWidth` 大 1px 以上，就认为「右边还有内容」，
 *   箭头从 disabled（opacity .3）变成全亮。
 *
 *   而盟约条里「本局禁用」这个角标（`.bslot__count.bslot__off`）宽 37px，
 *   比 30px 的槽位宽，天然会向右溢出几个像素。实测 800×360 视口下
 *   `clientWidth=97 / scrollWidth=104`，多出 7px —— 于是右箭头被点亮。
 *   同一页面模拟成 1280×720 时 `161/161` 不溢出，两个箭头都回到 .3 透明度。
 *
 *   所以这是「极小溢出被当成可滚动」的判定问题，屏幕越窄越容易触发。
 *
 * 【怎么解决】
 *   两个箭头都 `display: none` —— 既不画出来，也不再占那 44px。
 *   顺带解决了另一个观感问题：箭头占位时，盟约图标被推离左边的
 *   「收起盟约」按钮整整 44px，看起来中间空一大截；去掉后两者间距
 *   从 54px 收到 8px，和浏览器里的观感一致。
 *
 *   滚动能力不受影响：盟约条本身是 `overflow-x: auto`，手指照样能横向拖动。
 *
 *   顺带把底部那条横向滚动条也藏掉 —— 溢出存在时 `overflow-x: auto` 会画一条
 *   深色细线，在深色画面上很碍眼。用 scrollbar-width / ::-webkit-scrollbar
 *   双写覆盖 Chromium 和 WebKit 两条路径，滚动行为不变，只是不绘制。
 */
object UiPatch {

    /** 生成注入脚本（返回一段可执行 JS，外面用 <script> 包起来） */
    fun build(): String = """
(function () {
  'use strict';
  try {
    if (window.__strongholdUiPatch) return;
    window.__strongholdUiPatch = true;

    var css = [
      /* 盟约条两端的滚动箭头：一个 44px 的深色方块，本身就显眼；
         更碍事的是它占位，把盟约图标推离左边的收起按钮 44px。
         直接 display:none —— 不画也不占位，图标紧贴按钮。
         滚动能力保留（.bstrip 仍是 overflow-x:auto，手指照样能滑）。 */
      '.bstrip-scroll__arrow{display:none !important;}',
      /* 盟约条底部的横向滚动条：内容被 44px 触摸热区撑出溢出，
         overflow-x:auto 就会在下方画一条深色细线。滚动能力保留，只是不画出来。 */
      '.gm__bonds .bstrip-scroll .bstrip{scrollbar-width:none !important;}',
      '.gm__bonds .bstrip-scroll .bstrip::-webkit-scrollbar{display:none !important;width:0 !important;height:0 !important;}'
    ].join('\n');

    var st = document.createElement('style');
    st.id = '__sh_ui_patch';
    st.appendChild(document.createTextNode(css));
    (document.head || document.documentElement).appendChild(st);
  } catch (e) { /* 注入失败不影响游戏 */ }
})();
"""
}
