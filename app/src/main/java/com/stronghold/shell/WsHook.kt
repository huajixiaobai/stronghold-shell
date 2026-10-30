package com.stronghold.shell

/**
 * 联机注入脚本生成器。
 *
 * 【工作原理】
 *   游戏页面（由手机内 Node 在 127.0.0.1:3000 提供）里，上游 net.js 会这样连服务器：
 *
 *       const WS = this.WS || globalThis.WebSocket;   // net.js:230 —— 运行时读取
 *       const url = this.url || defaultWsUrl();        // "$ws://$location.host/ws"
 *       let ws = new WS(url);
 *
 *   因为 WebSocket 是在 connect() 时才从 globalThis 取的（不是模块加载时缓存），
 *   所以我们在页面脚本运行前把 window.WebSocket 换掉，就能把所有指向 /ws 的
 *   连接改写到用户填的服务器地址。
 *
 * 【为什么与上游解耦】
 *   脚本只认两个运行时特征：
 *     ① 协议是 ws: / wss:
 *     ② pathname 恰好是 "/ws"
 *   不引用上游任何变量名、函数名、模块路径。只要这两个契约不变（上游
 *   public/js/net.js 里 defaultWsUrl() 与 path 定义），上游升级任意版本都不用改这里。
 *
 * 【资源走本地】
 *   页面本身、素材、字体全部由手机内 Node 提供，不经过公网服务器，
 *   所以联机流量只有 WebSocket 的房间同步消息。
 */
object WsHook {

    /** JS 桥对象名：连接失败时回调原生弹提示 */
    const val BRIDGE_NAME = "__StrongholdBridge"

    /** 注入成功后置位的全局标记，供原生侧自检（window[HOOK_FLAG] === true 表示已接管） */
    const val HOOK_FLAG = "__strongholdHookInstalled"

    /** 上游 WebSocket 路径，改了这里就得跟着改（见类注释） */
    private const val WS_PATH = "/ws"

    /**
     * 生成注入脚本。
     * @param serverAddress 已规范化的 "host:port"
     * @param useTls 服务器是否走 wss（当前部署是明文 HTTP，固定 false）
     */
    fun build(serverAddress: String, useTls: Boolean = false): String {
        val host = jsString(serverAddress)
        val scheme = if (useTls) "wss:" else "ws:"
        return """
(function () {
  'use strict';
  try {
    if (window.__strongholdWsHooked) return;
    window.__strongholdWsHooked = true;

    var TARGET_HOST   = "$host";
    var TARGET_SCHEME = "$scheme";
    var TARGET_PATH   = "$WS_PATH";

    // 连接序号：给每条 WebSocket 一个稳定 id，原生侧据此记账
    var sockSeq = 0;

    var OrigWS = window.WebSocket;
    if (typeof OrigWS !== 'function') return;

    function parse(url) {
      try { return new URL(String(url), window.location.href); }
      catch (e) { return null; }
    }

    // 只有 ws/wss 且路径正好是 /ws 才改写
    function shouldRewrite(u) {
      if (!u) return false;
      if (u.protocol !== 'ws:' && u.protocol !== 'wss:') return false;
      return u.pathname === TARGET_PATH;
    }

    function buildTarget(qs) {
      return TARGET_SCHEME + '//' + TARGET_HOST + TARGET_PATH + (qs || '');
    }

    function notifyFail(reason) {
      try {
        var b = window.$BRIDGE_NAME;
        if (b && typeof b.onConnectionFailed === 'function') {
          b.onConnectionFailed(String(reason || ''));
        }
      } catch (e) { /* 桥不存在时静默 */ }
    }

    function notify(event) {
      try {
        var b = window.$BRIDGE_NAME;
        if (b && typeof b.onSocketEvent === 'function') {
          b.onSocketEvent(String(event || ''));
        }
      } catch (e) { /* 桥不存在时静默 */ }
    }

    function PatchedWebSocket(url, protocols) {
      var raw = String(url == null ? '' : url);
      var parsed = parse(raw);
      var finalUrl = raw;
      var rewritten = false;

      if (shouldRewrite(parsed)) {
        // 保留原始 query（如果有），只换 scheme + host
        finalUrl = buildTarget(parsed.search);
        rewritten = true;
      }

      var ws;
      if (arguments.length > 1) {
        ws = Reflect.construct(OrigWS, [finalUrl, protocols], PatchedWebSocket);
      } else {
        ws = Reflect.construct(OrigWS, [finalUrl], PatchedWebSocket);
      }

      if (rewritten) {
        var opened = false;
        // 每条连接一个 id：页面里可能同时开着多条 WS，原生侧按 id 记账才不会误判断开
        var sockId = 's' + (++sockSeq);
        try {
          ws.addEventListener('open', function () {
            opened = true;
            notify('LINK_OK|' + sockId);
          });
          ws.addEventListener('error', function () {
            if (!opened) notifyFail('无法连接服务器 ' + TARGET_HOST);
          });
          ws.addEventListener('close', function (ev) {
            if (!opened && ev && ev.wasClean === false && ev.code !== 1000) {
              notifyFail('连接被断开 ' + TARGET_HOST + ' 码' + (ev.code || '?'));
            } else if (opened) {
              notify('LINK_CLOSED|' + sockId + '|' + (ev && ev.code));
            }
          });
        } catch (e) { /* 事件挂不上不影响主流程 */ }
      }
      return ws;
    }

    // 原型链保持一致：ws instanceof WebSocket 成立，instanceof OrigWS 也成立
    PatchedWebSocket.prototype = OrigWS.prototype;

    // 复制静态常量（CONNECTING/OPEN/CLOSING/CLOSED）
    ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function (k) {
      try {
        if (k in OrigWS) PatchedWebSocket[k] = OrigWS[k];
      } catch (e) {}
    });

    // 让 toString() / name 看起来仍是 WebSocket（有些库会检查）
    try {
      Object.defineProperty(PatchedWebSocket, 'name', { value: 'WebSocket' });
    } catch (e) {}

    window.WebSocket = PatchedWebSocket;

    // 同时挂到 globalThis（部分环境 window === globalThis，双保险）
    try { if (typeof globalThis !== 'undefined') globalThis.WebSocket = PatchedWebSocket; } catch (e) {}

    // 真正挂上之后才置自检标记，原生侧据此判断是否接管成功
    try { window.$HOOK_FLAG = true; } catch (e) {}

    // 注：这里原本会往页面右下/左下角画一个「联机已接管 → 目标地址」的常驻横幅，
    // 仅用于早期排查（logcat 被系统屏蔽、Toast 只弹一次）。联机链路稳定后已移除，
    // 避免长期遮挡游戏 HUD。当前接管状态可看「多人游戏」页，或由原生侧一次性 Toast 提示。
  } catch (e) {
    // 注入失败不阻塞游戏：单机仍然可玩
    try { console.warn('[stronghold] ws hook failed', e); } catch (e2) {}
  }
})();
""".trimIndent()
    }

    /**
     * 把字符串转成安全的 JS 字面量内容。
     * 注意：地址在入口处已经过 ServerStore.normalizeAddress 校验（只允许 [A-Za-z0-9.-] 与冒号），
     * 这里仍做一次全量转义，防御未来校验规则放宽。
     */
    private fun jsString(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\x3C")
                '>' -> sb.append("\\x3E")
                '&' -> sb.append("\\x26")
                '/' -> sb.append("\\/")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else ->
                    if (c.code < 0x20) sb.append("\\x").append(c.code.toString(16).padStart(2, '0'))
                    else sb.append(c)
            }
        }
        return sb.toString()
    }
}