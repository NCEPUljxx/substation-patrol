/**
 * Unity WebGL 内 WebSocket 地址在构建时写死为 localhost。
 * 在 Unity 加载前拦截 WebSocket 构造，改为与 app.js 相同的动态主机名。
 */
(function () {
  'use strict';

  var WS_PORT = (function () {
    var match = /(?:^|[?&])wsPort=(\d+)(?:&|$)/.exec(window.location.search);
    return match ? match[1] : '8888';
  })();

  function buildWebSocketUrl() {
    var wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return wsProtocol + '//' + window.location.hostname + ':' + WS_PORT;
  }

  function shouldRewrite(url) {
    return typeof url === 'string'
      && (url.indexOf('localhost') !== -1 || url.indexOf('127.0.0.1') !== -1);
  }

  var targetUrl = buildWebSocketUrl();
  var NativeWebSocket = window.WebSocket;

  function PatchedWebSocket(url, protocols) {
    var resolvedUrl = shouldRewrite(url) ? targetUrl : url;
    if (protocols !== undefined) {
      return new NativeWebSocket(resolvedUrl, protocols);
    }
    return new NativeWebSocket(resolvedUrl);
  }

  PatchedWebSocket.prototype = NativeWebSocket.prototype;
  PatchedWebSocket.CONNECTING = NativeWebSocket.CONNECTING;
  PatchedWebSocket.OPEN = NativeWebSocket.OPEN;
  PatchedWebSocket.CLOSING = NativeWebSocket.CLOSING;
  PatchedWebSocket.CLOSED = NativeWebSocket.CLOSED;

  window.WebSocket = PatchedWebSocket;
  window.__unityWsUrl = targetUrl;
}());
