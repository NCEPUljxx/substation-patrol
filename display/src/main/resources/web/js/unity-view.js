/**
 * Unity WebGL 3D 视图：2D/3D 切换与摄像机桥接（与 app.js 解耦）
 */
(function (global) {
  'use strict';

  var DEFAULT_GRID_W = 30;
  var DEFAULT_GRID_H = 30;
  var UNITY_BTN_2D = '\uD83D\uDCD0 2D视图';
  var UNITY_BTN_3D = '\uD83C\uDFAE 3D视图';
  var UNITY_BRIDGE = 'GameController';

  var is3DView = false;
  var needsFrameReload = false;
  var cameraDrag = { active: false, mode: 'none', lastX: 0, lastY: 0 };

  var $unityShell = null;
  var $unityFrame = null;
  var $unityInput = null;
  var $btnUnity = null;
  var $mapStack = null;
  var $welcome = null;

  var getLiveData = function () { return null; };
  var getReplayData = function () { return null; };
  var isCanvasReady = function () { return false; };
  var requestFinalizeCanvas = function () {};

  function init(hooks) {
    $unityShell = document.getElementById('unity-shell');
    $unityFrame = document.getElementById('unity-frame');
    $unityInput = document.getElementById('unity-input');
    $btnUnity = document.getElementById('btn-unity');
    $mapStack = document.getElementById('map-stack');
    $welcome = document.getElementById('welcome-overlay');

    if (hooks) {
      if (hooks.getLiveData) getLiveData = hooks.getLiveData;
      if (hooks.getReplayData) getReplayData = hooks.getReplayData;
      if (hooks.isCanvasReady) isCanvasReady = hooks.isCanvasReady;
      if (hooks.requestFinalizeCanvas) requestFinalizeCanvas = hooks.requestFinalizeCanvas;
    }

    initUnityCameraInput();
    if ($btnUnity) {
      $btnUnity.addEventListener('click', toggleView);
    }
  }

  function is3D() {
    return is3DView;
  }

  function onCanvasReady() {
    if ($mapStack && !is3DView) {
      $mapStack.style.display = 'block';
    }
    if (is3DView) {
      syncSize();
    }
  }

  function reloadFrame() {
    if (!$unityFrame) return;
    needsFrameReload = false;
    $unityFrame.src = 'unity/index.html?_=' + Date.now();
  }

  /** 仿真重置：退出 3D 并重载 iframe，清掉 Unity 内缓存的探索格 */
  function resetForNewSimulation() {
    exit();
    needsFrameReload = true;
    reloadFrame();
  }

  function syncSize() {
    if (!$unityShell) return;
    var mapArea = document.querySelector('.map-area');
    if (!mapArea) return;

    var liveData = getLiveData();
    var replayData = getReplayData();
    var availW = mapArea.clientWidth - 20;
    var availH = mapArea.clientHeight - 20;
    var w = DEFAULT_GRID_W;
    var h = DEFAULT_GRID_H;

    if (liveData && liveData.taskConfig) {
      w = parseInt(liveData.taskConfig.mapWidth, 10) || DEFAULT_GRID_W;
      h = parseInt(liveData.taskConfig.mapHeight, 10) || DEFAULT_GRID_H;
    } else if (replayData) {
      w = replayData.mapWidth || DEFAULT_GRID_W;
      h = replayData.mapHeight || DEFAULT_GRID_H;
    }

    var cellW = Math.floor(availW / w);
    var cellH = Math.floor(availH / h);
    var cellSize = Math.max(4, Math.min(cellW, cellH));
    $unityShell.style.width = (w * cellSize) + 'px';
    $unityShell.style.height = (h * cellSize) + 'px';
  }

  function resolveUnityInstance() {
    try {
      if (!$unityFrame || !$unityFrame.contentWindow) return null;
      return $unityFrame.contentWindow.unityInstance || null;
    } catch (ignored) {
      return null;
    }
  }

  function sendUnityCamera(method, payload) {
    var instance = resolveUnityInstance();
    if (!instance) return;
    instance.SendMessage(UNITY_BRIDGE, method, payload || '');
  }

  function resolveCameraDragMode(button, shiftKey, altKey) {
    if (button === 0 && !shiftKey && !altKey) return 'pan';
    if (button === 0 && (shiftKey || altKey)) return 'orbit';
    if (button === 1 || button === 2) return 'orbit';
    return 'none';
  }

  function onUnityInputDown(event) {
    if (!is3DView || !$unityShell || $unityShell.hidden) return;
    event.preventDefault();
    event.stopPropagation();
    cameraDrag.mode = resolveCameraDragMode(event.button, event.shiftKey, event.altKey);
    if (cameraDrag.mode === 'none') return;
    cameraDrag.active = true;
    cameraDrag.lastX = event.clientX;
    cameraDrag.lastY = event.clientY;
    if ($unityInput && $unityInput.setPointerCapture) {
      $unityInput.setPointerCapture(event.pointerId);
    }
  }

  function onUnityInputMove(event) {
    if (!cameraDrag.active || cameraDrag.mode === 'none') return;
    event.preventDefault();
    var dx = event.clientX - cameraDrag.lastX;
    var dy = event.clientY - cameraDrag.lastY;
    cameraDrag.lastX = event.clientX;
    cameraDrag.lastY = event.clientY;
    if (dx === 0 && dy === 0) return;
    var payload = dx + ',' + dy;
    if (cameraDrag.mode === 'pan') sendUnityCamera('OnWebPan', payload);
    else sendUnityCamera('OnWebOrbit', payload);
  }

  function onUnityInputUp(event) {
    cameraDrag.active = false;
    cameraDrag.mode = 'none';
    if ($unityInput && $unityInput.hasPointerCapture && $unityInput.hasPointerCapture(event.pointerId)) {
      $unityInput.releasePointerCapture(event.pointerId);
    }
  }

  function onUnityInputWheel(event) {
    if (!is3DView || !$unityShell || $unityShell.hidden) return;
    event.preventDefault();
    event.stopPropagation();
    sendUnityCamera('OnWebZoom', String(-event.deltaY));
  }

  function initUnityCameraInput() {
    if (!$unityInput) return;
    $unityInput.addEventListener('pointerdown', onUnityInputDown, { passive: false });
    $unityInput.addEventListener('contextmenu', function (event) { event.preventDefault(); });
    document.addEventListener('pointermove', onUnityInputMove, { passive: false });
    document.addEventListener('pointerup', onUnityInputUp);
    document.addEventListener('pointercancel', onUnityInputUp);
    if ($unityShell) {
      $unityShell.addEventListener('wheel', onUnityInputWheel, { passive: false });
    }
  }

  function show2DMapView() {
    var mapArea = document.querySelector('.map-area');
    if (mapArea) mapArea.classList.remove('map-area-unity');
    cameraDrag.active = false;
    cameraDrag.mode = 'none';

    if ($unityShell) {
      $unityShell.hidden = true;
      $unityShell.classList.remove('active');
    }

    var liveData = getLiveData();
    var replayData = getReplayData();
    var ready = isCanvasReady();
    if ($mapStack) {
      $mapStack.style.display = (ready || liveData || replayData) ? 'block' : 'none';
    }
    if ($welcome && !ready && !liveData && !replayData) {
      $welcome.style.display = 'flex';
    }
  }

  function show3DMapView() {
    if (needsFrameReload) {
      reloadFrame();
    }
    if ($welcome) $welcome.style.display = 'none';
    if ($mapStack) $mapStack.style.display = 'none';
    var mapArea = document.querySelector('.map-area');
    if (mapArea) mapArea.classList.add('map-area-unity');
    syncSize();
    if ($unityShell) {
      $unityShell.hidden = false;
      $unityShell.classList.add('active');
    }
  }

  function toggleView() {
    is3DView = !is3DView;
    if (is3DView) {
      show3DMapView();
      if ($btnUnity) $btnUnity.textContent = UNITY_BTN_2D;
      return;
    }
    show2DMapView();
    if ($btnUnity) $btnUnity.textContent = UNITY_BTN_3D;
    if (!isCanvasReady() && getLiveData()) {
      requestFinalizeCanvas();
    }
  }

  function exit() {
    if (!is3DView) return;
    is3DView = false;
    if ($btnUnity) $btnUnity.textContent = UNITY_BTN_3D;
    show2DMapView();
  }

  global.UnityView = {
    init: init,
    is3D: is3D,
    onCanvasReady: onCanvasReady,
    syncSize: syncSize,
    exit: exit,
    resetForNewSimulation: resetForNewSimulation
  };
}(window));
