/**
 * 变电站巡检仿真系统 — 前端核心逻辑 (双层canvas浅色主题版)
 */
(function () {
  'use strict';

  // ══════════════ 常量
  var CELL_SIZE = 18;
  var DEFAULT_GRID_W = 30;
  var DEFAULT_GRID_H = 30;
  var RECONNECT_DELAY_MS = 3000;
  var MAX_ROUTE_DRAW = 10;
  var REPLAY_FRAME_MS = 200;

  // ══════════════ 状态色
  var STATUS_COLORS = {
    IDLE: '#9E9E9E', WAITING_ROUTE: '#FF9800', READY: '#4CAF50',
    MOVING: '#2196F3', BLOCKED: '#F44336'
  };
  var STATUS_NAMES = {
    IDLE: '空闲', WAITING_ROUTE: '等待路径', READY: '就绪',
    MOVING: '移动中', BLOCKED: '受阻'
  };

  // ══════════════ 每车固定颜色（与 Unity 3D 端一致：同色相、降饱和）
  var CAR_COLORS = ['#A6665F','#58829E','#51936D','#785C83','#AF8B51','#44877A','#A85370','#408F99'];

  // ══════════════ 浅色主题颜色
  var LIGHT = {
    gridBg: '#D0D0D0',
    gridLine: '#94A3B8',
    explored: '#B0C4DE',
    obstacle: '#C0C0C0',
    obstacleFill: '#A0A0A0',
    sealedFill: '#E53935'
  };

  // ══════════════ DOM
  var $mapStack = document.getElementById('map-stack');
  var $welcome  = document.getElementById('welcome-overlay');
  var mapCanvas = document.getElementById('map-canvas');
  var mapCtx    = mapCanvas.getContext('2d');
  var carCanvas = document.getElementById('car-canvas');
  var carCtx    = carCanvas.getContext('2d');

  var $tick = document.getElementById('info-tick');
  var $rate = document.getElementById('info-rate');
  var $elapsed = document.getElementById('info-elapsed');
  var $modeTag = document.getElementById('info-mode');
  var $carsPanel = document.getElementById('cars-panel');
  var $leaderboard = document.getElementById('leaderboard');

  var $btnStart = document.getElementById('btn-start');
  var $btnPause = document.getElementById('btn-pause');
  var $btnReset = document.getElementById('btn-reset');
  var $btnAddCar = document.getElementById('btn-addcar');
  var $btnReplay = document.getElementById('btn-replay');
  var $btnLive = document.getElementById('btn-live');

  var $replayControls = document.getElementById('replay-controls');
  var $replayRunSelect = document.getElementById('replay-run-select');
  var $replaySlider = document.getElementById('replay-slider');
  var $replayTickLbl = document.getElementById('replay-tick-label');
  var $replayToggle = document.getElementById('replay-toggle');

  var $cfgWidth = document.getElementById('cfg-width');
  var $cfgHeight = document.getElementById('cfg-height');
  var $cfgCarCount = document.getElementById('cfg-carCount');
  var initialCarCount = $cfgCarCount ? String($cfgCarCount.value || '3') : '3';
  var $cfgObstacleRatio = document.getElementById('cfg-obstacleRatio');
  var $cfgAlgorithm = document.getElementById('cfg-algorithm');
  var $cfgTickInterval = document.getElementById('cfg-tickInterval');
  var $lblObstacleRatio = document.getElementById('lbl-obstacleRatio');
  var $lblTickInterval = document.getElementById('lbl-tickInterval');

  var $pwdModal = document.getElementById('changepw-modal');
  var $pwdOld = document.getElementById('cpw-old');
  var $pwdNew = document.getElementById('cpw-new');
  var $pwdErr = document.getElementById('cpw-err');
  var $pwdSubmit = document.getElementById('cpw-submit');
  var $pwdCancel = document.getElementById('cpw-cancel');

  // ══════════════ 状态
  var ws = null;
  var liveData = null;
  var mode = 'live';
  var canvasReady = false;
  var mapLayerDirty = true;
  var cachedMapBlock = null;
  var cachedMapSealed = null;
  var renderedMapView = null;
  var lastMapRenderTick = -1;
  var MAP_RENDER_INTERVAL = 3;

  var replay = { currentTick: 0, maxTick: 0, playing: false, timerId: null };
  var replayData = null;
  var taskCompleteShown = false;
  var savePopupShown = false;
  var simulationFrozenTick = null;
  var localRunStarter = '';
  var lastKnownRunStartedBy = '';

  var elapsedTimerId = null;
  var startTimestamp = null;
  var wasEverConnected = false;
  var userRole = null;
  var currentOperator = 'unknown';
  var selectedReplayRunId = null;
  var lastSavedRunId = null;

  var addCarPending = { active: false, carId: '', baselineCount: 0, timerId: null };
  var ADD_CAR_TIMEOUT_MS = 30000;
  var ADD_CAR_LABEL_DEFAULT = '+ 添加小车';
  var WS_PORT = 8888;
  var isEditingTickInterval = false;
  var suppressTickIntervalSync = false;
  var MIN_TICK_INTERVAL_MS = 100;
  var MAX_TICK_INTERVAL_MS = 2000;

  // ══════════════ WebSocket
  function buildWebSocketUrl() {
    var wsProtocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    return wsProtocol + '//' + location.hostname + ':' + WS_PORT;
  }

  function connectWebSocket() {
    ws = new WebSocket(buildWebSocketUrl());
    ws.onopen = onSocketOpen;
    ws.onmessage = onSocketMessage;
    ws.onclose = onSocketClose;
    ws.onerror = onSocketError;
  }

  function onSocketOpen() {
    console.log('[app] WS connected');
    if (wasEverConnected) {
      ws.send(JSON.stringify({ type: 'RESET', data: {} }));
    }
  }

  function syncCarCountFromLiveData(data) {
    if (!$cfgCarCount || !data || !data.cars) return;
    var count = data.cars.length;
    if (count <= 0) return;
    var countStr = String(count);
    $cfgCarCount.value = countStr;
    initialCarCount = countStr;
  }

  function syncCarCountFromLaunchedCar(carId) {
    if (!$cfgCarCount || !carId) return;
    var carNumber = parseInt(String(carId).replace(/\D/g, ''), 10);
    if (carNumber <= 0) return;
    var countStr = String(Math.max(parseInt($cfgCarCount.value, 10) || 0, carNumber));
    $cfgCarCount.value = countStr;
    initialCarCount = countStr;
  }

  function onSocketMessage(event) {
    wasEverConnected = true;
    var msg = JSON.parse(event.data);
    if (msg.type === 'REPLAY_DATA') { receiveReplayData(msg); return; }
    if (msg.type === 'REPLAY_ERROR') { alert(msg.error || '回放加载失败'); return; }
    if (msg.type === 'RUN_ARCHIVED') {
      return;
    }
    if (msg.type === 'CAR_PENDING') { beginAddCarPending(msg.carId); return; }
    if (msg.type === 'CAR_LAUNCHED') {
      clearAddCarPending(true);
      syncCarCountFromLaunchedCar(msg.carId);
      return;
    }
    if (msg.type === 'CAR_LAUNCH_FAILED') { failAddCarPending(msg.reason); return; }
    liveData = normalizeMapPayload(msg);
    if (mode === 'live') {
      syncTickIntervalFromTaskConfig(liveData.taskConfig);
      syncControlButtons(liveData);
      syncCarCountFromLiveData(liveData);
      finalizeCanvas();
      if (canvasReady) {
        var tick = liveData.tick || 0;
        if (shouldRepaintMapLayer(tick)) {
          renderMapLayer();
          lastMapRenderTick = tick;
        } else if (liveData.mapView) {
          paintIncrementalExplored(liveData.mapView);
        }
        renderCarsLayer();
        renderCarsPanel(liveData);
        renderLeaderboard(liveData);
        updateGlobalInfo(liveData);
        maybeCompleteAddCarPending(liveData);
      }
    }
  }

  function beginAddCarPending(carId) {
    if (!$btnAddCar) return;
    clearAddCarPendingTimer();
    addCarPending.active = true;
    addCarPending.carId = carId || '';
    addCarPending.baselineCount = (liveData && liveData.cars) ? liveData.cars.length : 0;
    $btnAddCar.disabled = true;
    $btnAddCar.textContent = addCarPending.carId
      ? ('添加 ' + addCarPending.carId + '...')
      : '添加中...';
    addCarPending.timerId = setTimeout(function () {
      failAddCarPending('超时：请查看 logs/ 下 car-' + (addCarPending.carId || 'CarXXX') + '-*.log');
    }, ADD_CAR_TIMEOUT_MS);
  }

  function maybeCompleteAddCarPending(data) {
    if (!addCarPending.active || !data.cars) return;
    if (addCarPending.carId) {
      for (var i = 0; i < data.cars.length; i++) {
        var pendingId = 'Car' + String(data.cars[i].number).padStart(3, '0');
        if (pendingId === addCarPending.carId && data.cars.length > addCarPending.baselineCount) {
          clearAddCarPending(true);
          syncCarCountFromLiveData(data);
          return;
        }
      }
    }
    if (data.cars.length > addCarPending.baselineCount) {
      clearAddCarPending(true);
      syncCarCountFromLiveData(data);
    }
  }

  function failAddCarPending(reason) {
    clearAddCarPending(false);
    if (reason) {
      window.alert('添加小车失败：' + reason);
    }
  }

  function clearAddCarPending(wasSuccessful) {
    if (!$btnAddCar) return;
    clearAddCarPendingTimer();
    addCarPending.active = false;
    addCarPending.carId = '';
    addCarPending.baselineCount = 0;
    $btnAddCar.disabled = false;
    $btnAddCar.textContent = wasSuccessful ? '✓ 已添加' : ADD_CAR_LABEL_DEFAULT;
    if (wasSuccessful) {
      setTimeout(function () {
        if ($btnAddCar && $btnAddCar.textContent === '✓ 已添加') {
          $btnAddCar.textContent = ADD_CAR_LABEL_DEFAULT;
        }
      }, 1500);
    }
  }

  function clearAddCarPendingTimer() {
    if (addCarPending.timerId) {
      clearTimeout(addCarPending.timerId);
      addCarPending.timerId = null;
    }
  }

  function clearMapCaches() {
    cachedMapBlock = null;
    cachedMapSealed = null;
    renderedMapView = null;
    lastMapRenderTick = -1;
    mapLayerDirty = true;
  }

  function paintIncrementalExplored(mapView) {
    if (!canvasReady || !mapView) return;
    var gridW = getGridW(liveData);
    var gridH = getGridH(liveData);
    if (!renderedMapView) {
      renderedMapView = createEmptyView(gridW, gridH);
    }
    var ctx = mapCtx;
    var cs = CELL_SIZE;
    ctx.fillStyle = LIGHT.explored;
    for (var r = 0; r < gridH; r++) {
      if (!mapView[r]) continue;
      for (var c = 0; c < gridW; c++) {
        if (mapView[r][c] && !renderedMapView[r][c]) {
          ctx.fillRect(c * cs, r * cs, cs, cs);
          renderedMapView[r][c] = true;
        }
      }
    }
  }

  function syncRenderedMapView(mapView, gridW, gridH) {
    renderedMapView = mapView ? cloneView(mapView) : createEmptyView(gridW, gridH);
  }

  function shouldRepaintMapLayer(tick) {
    if (mapLayerDirty) return true;
    if (tick <= 1) return true;
    if ((liveData.explorationRate || 0) >= 100) return true;
    return tick - lastMapRenderTick >= MAP_RENDER_INTERVAL;
  }

  function normalizeMapPayload(data) {
    var w = getGridW(data);
    var h = getGridH(data);
    if (data.mapViewB64) {
      data.mapView = decodeBitmapB64(data.mapViewB64, w, h);
    }
    if (data.mapBlockB64) {
      cachedMapBlock = decodeBitmapB64(data.mapBlockB64, w, h);
    }
    if (cachedMapBlock) {
      data.mapBlock = cachedMapBlock;
    }
    if (data.mapSealedB64) {
      cachedMapSealed = decodeBitmapB64(data.mapSealedB64, w, h);
    }
    if (cachedMapSealed) {
      data.mapSealed = cachedMapSealed;
    }
    return data;
  }

  function decodeBitmapB64(b64, width, height) {
    var binary = atob(b64);
    var bitmap = [];
    for (var row = 0; row < height; row++) {
      var rowBits = [];
      for (var col = 0; col < width; col++) {
        var offset = row * width + col;
        var byteIdx = (offset / 8) | 0;
        var bitIdx = 7 - (offset % 8);
        var byteVal = byteIdx < binary.length ? binary.charCodeAt(byteIdx) : 0;
        rowBits.push(((byteVal >> bitIdx) & 1) === 1);
      }
      bitmap.push(rowBits);
    }
    return bitmap;
  }

  function onSocketClose() {
    console.log('[app] WS closed, reconnecting in ' + (RECONNECT_DELAY_MS / 1000) + 's');
    setTimeout(connectWebSocket, RECONNECT_DELAY_MS);
  }

  function onSocketError(err) { console.error('[app] WS error', err); }

  function buildTaskConfigFromForm() {
    return {
      mapWidth: String($cfgWidth.value),
      mapHeight: String($cfgHeight.value),
      carCount: String(initialCarCount),
      obstacleRatio: String($cfgObstacleRatio.value),
      algorithm: $cfgAlgorithm.value,
      tickInterval: String($cfgTickInterval.value),
      active: 'true',
      operator: currentOperator
    };
  }

  function ensureLiveDataTaskConfig() {
    if (!liveData) {
      liveData = { tick: 0, explorationRate: 0, cars: [] };
    }
    if (!liveData.taskConfig || !liveData.taskConfig.mapWidth) {
      liveData.taskConfig = buildTaskConfigFromForm();
    }
  }

  // ══════════════ Canvas 尺寸
  function finalizeCanvas() {
    ensureLiveDataTaskConfig();
    if (!liveData || !liveData.taskConfig) return;
    var mapArea = document.querySelector('.map-area');
    if (!mapArea) return;
    var availW = mapArea.clientWidth - 20;
    var availH = mapArea.clientHeight - 20;
    if (availW < 100 || availH < 100) return;

    var w = parseInt(liveData.taskConfig.mapWidth, 10) || DEFAULT_GRID_W;
    var h = parseInt(liveData.taskConfig.mapHeight, 10) || DEFAULT_GRID_H;
    var cellW = Math.floor(availW / w);
    var cellH = Math.floor(availH / h);
    var nextCellSize = Math.max(4, Math.min(cellW, cellH));
    var cw = w * nextCellSize;
    var ch = h * nextCellSize;

    if (canvasReady && mapCanvas.width === cw && mapCanvas.height === ch
        && CELL_SIZE === nextCellSize) {
      return;
    }

    CELL_SIZE = nextCellSize;
    mapCanvas.width = cw;
    mapCanvas.height = ch;
    carCanvas.width = cw;
    carCanvas.height = ch;

    canvasReady = true;
    mapLayerDirty = true;
    if ($welcome) $welcome.style.display = 'none';
    if (window.UnityView) {
      UnityView.onCanvasReady();
    } else if ($mapStack) {
      $mapStack.style.display = 'block';
    }
  }

  // ══════════════ 地图层（静态：网格+探索+障碍物）
  function renderMapLayer() {
    if (!liveData || !canvasReady) return;
    var data = liveData;
    var gridW = getGridW(data), gridH = getGridH(data);
    var ctx = mapCtx, cs = CELL_SIZE;

    // 清空
    ctx.clearRect(0, 0, mapCanvas.width, mapCanvas.height);

    // 网格背景
    ctx.fillStyle = LIGHT.gridBg;
    ctx.fillRect(0, 0, gridW * cs, gridH * cs);

    // 已探索
    if (data.mapView) {
      ctx.fillStyle = LIGHT.explored;
      for (var r = 0; r < gridH; r++) {
        if (!data.mapView[r]) continue;
        for (var c = 0; c < gridW; c++) {
          if (data.mapView[r][c]) ctx.fillRect(c * cs, r * cs, cs, cs);
        }
      }
    }

    // 障碍物（跳过有车格子）
    if (data.mapBlock) {
      var occupied = {};
      if (data.cars) {
        for (var i = 0; i < data.cars.length; i++) {
          var p = data.cars[i].position;
          if (p) occupied[p.x + ',' + p.y] = true;
        }
      }
      ctx.fillStyle = LIGHT.obstacleFill;
      for (var rr = 0; rr < gridH; rr++) {
        if (!data.mapBlock[rr]) continue;
        for (var cc = 0; cc < gridW; cc++) {
          if (data.mapBlock[rr][cc] && !occupied[cc + ',' + rr]) {
            ctx.fillRect(cc * cs, rr * cs, cs, cs);
          }
        }
      }
    }

    // 任务完成时，密封不可达区域红色填充
    if ((data.explorationRate || 0) >= 100 && data.mapSealed) {
      ctx.fillStyle = LIGHT.sealedFill;
      for (var sr = 0; sr < gridH; sr++) {
        if (!data.mapSealed[sr]) continue;
        for (var sc = 0; sc < gridW; sc++) {
          if (data.mapSealed[sr][sc]) {
            ctx.fillRect(sc * cs, sr * cs, cs, cs);
          }
        }
      }
    }

    // 网格线（最上层，所有格子都有描边）
    ctx.strokeStyle = LIGHT.gridLine;
    ctx.lineWidth = 0.5;
    for (var i = 0; i <= gridW; i++) {
      var x = i * cs; ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, gridH * cs); ctx.stroke();
    }
    for (var j = 0; j <= gridH; j++) {
      var y = j * cs; ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(gridW * cs, y); ctx.stroke();
    }
    syncRenderedMapView(data.mapView, gridW, gridH);
    mapLayerDirty = false;
  }

  // ══════════════ 小车层（动态：路线+小车）
  function renderCarsLayer() {
    if (!liveData || !canvasReady) return;
    var data = liveData;
    var ctx = carCtx, cs = CELL_SIZE;
    ctx.clearRect(0, 0, carCanvas.width, carCanvas.height);

    // 路线
    if (data.cars) {
      for (var i = 0; i < data.cars.length; i++) drawRoute(data.cars[i], ctx, cs);
    }
    // 小车
    if (data.cars) {
      for (var i = 0; i < data.cars.length; i++) drawCar(data.cars[i], ctx, cs);
    }
  }

  function shouldDrawRoute(car) {
    if (!car.routeList || car.routeList.length === 0) return false;
    if (car.status === 'READY' || car.status === 'MOVING' || car.status === 'BLOCKED') return true;
    return car.status === 'WAITING_ROUTE';
  }

  function drawRoute(car, ctx, cs) {
    if (!car.position || !shouldDrawRoute(car)) return;
    var routeList = car.routeList;
    var carColor = CAR_COLORS[(car.number - 1) % CAR_COLORS.length] || '#3498DB';
    ctx.strokeStyle = carColor + '80';
    ctx.lineWidth = Math.max(2, cs * 0.1);
    ctx.lineCap = 'round';
    var px = car.position.x * cs + cs / 2;
    var py = car.position.y * cs + cs / 2;
    var drawFrom = findRouteDrawStart(car.position, routeList);
    var steps = Math.min(routeList.length - drawFrom, MAX_ROUTE_DRAW);
    var prevX = car.position.x;
    var prevY = car.position.y;
    for (var i = 0; i < steps; i++) {
      var point = routeList[drawFrom + i];
      var gx = point.x;
      var gy = point.y;
      if (gx === prevX && gy === prevY) continue;
      if (Math.abs(gx - prevX) + Math.abs(gy - prevY) !== 1) continue;
      var nx = gx * cs + cs / 2;
      var ny = gy * cs + cs / 2;
      ctx.beginPath();
      ctx.moveTo(px, py);
      ctx.lineTo(nx, ny);
      ctx.stroke();
      px = nx;
      py = ny;
      prevX = gx;
      prevY = gy;
    }
  }

  function findRouteDrawStart(position, routeList) {
    for (var i = 0; i < routeList.length; i++) {
      var gx = routeList[i].x;
      var gy = routeList[i].y;
      if (gx === position.x && gy === position.y) {
        return i + 1 < routeList.length ? i + 1 : i;
      }
      if (Math.abs(gx - position.x) + Math.abs(gy - position.y) === 1) {
        return i;
      }
    }
    return 0;
  }

  function drawCar(car, ctx, cs) {
    if (!car.position) return;
    var cx = car.position.x * cs + cs / 2;
    var cy = car.position.y * cs + cs / 2;
    var color = CAR_COLORS[(car.number - 1) % CAR_COLORS.length] || '#9E9E9E';
    var outerR = cs / 2 - 1;

    ctx.beginPath();
    ctx.arc(cx, cy, outerR, 0, Math.PI * 2);
    ctx.fillStyle = color;
    ctx.fill();

    var innerR = Math.max(3, outerR - 2);
    ctx.beginPath();
    ctx.arc(cx, cy, innerR, 0, Math.PI * 2);
    ctx.fillStyle = '#FFFFFF';
    ctx.fill();

    ctx.fillStyle = '#000';
    var fs = Math.max(7, Math.floor(cs * 0.4));
    ctx.font = 'bold ' + fs + 'px Consolas, monospace';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(String(car.number), cx, cy);
  }

  // ══════════════ 右侧面板
  function renderCarsPanel(data) {
    if (!data.cars || data.cars.length === 0) {
      $carsPanel.innerHTML = '<div class="car-card placeholder"><p>等待车辆数据...</p></div>';
      return;
    }
    var html = '';
    for (var i = 0; i < data.cars.length; i++) {
      html += buildCarCard(data.cars[i]);
    }
    $carsPanel.innerHTML = html;
  }

  function buildCarCard(car) {
    var statusName = STATUS_NAMES[car.status] || car.status;
    var color = STATUS_COLORS[car.status] || '#9E9E9E';
    var posStr = car.position ? '(' + car.position.x + ', ' + car.position.y + ')' : 'N/A';
    var targetStr = car.target ? '(' + car.target.x + ', ' + car.target.y + ')' : '无';
    var stepsPct = Math.min(100, car.steps);
    return '<div class="car-card">' +
      '<div class="car-header">' +
        '<span class="car-number">Car ' + car.number + '</span>' +
        '<span class="car-status-tag" style="background:' + color + '">' + statusName + '</span>' +
      '</div><div class="car-detail">' +
        '<div>位置: ' + posStr + '</div>' +
        '<div>目标: ' + targetStr + '</div>' +
        '<div>步数: ' + car.steps + '</div>' +
        '<div class="steps-bar-wrap"><div class="steps-bar-fill" style="width:' + stepsPct + '%"></div></div>' +
      '</div></div>';
  }

  function renderLeaderboard(data) {
    if (!data.cars || data.cars.length === 0) { $leaderboard.innerHTML = ''; return; }
    var sorted = data.cars.slice().sort(function (a, b) {
      return (b.effectiveSteps || 0) - (a.effectiveSteps || 0);
    });
    var html = '';
    for (var i = 0; i < sorted.length; i++) {
      var effective = sorted[i].effectiveSteps || 0;
      html += '<li>Car ' + sorted[i].number + ': ' + effective + '/' + sorted[i].steps + ' 步</li>';
    }
    $leaderboard.innerHTML = html;
  }

  function isSimulationComplete(data) {
    if (!data) { return false; }
    if (data.taskConfig && data.taskConfig.active === 'false') { return true; }
    return (data.explorationRate || 0) >= 100;
  }

  function isSimulationInProgress(data) {
    if (!data || isSimulationComplete(data)) return false;
    if ((data.tick || 0) > 0) return true;
    return !!(data.taskConfig && data.taskConfig.active === 'true'
      && data.cars && data.cars.length > 0);
  }

  function syncControlButtons(data) {
    if (!data || mode !== 'live') return;
    if (isSimulationComplete(data) || taskCompleteShown) {
      $btnStart.disabled = false;
      $btnPause.disabled = true;
      $btnPause.textContent = '⏯ 暂停';
      return;
    }
    if (isSimulationInProgress(data)) {
      $btnStart.disabled = true;
      $btnPause.disabled = false;
      return;
    }
    $btnStart.disabled = false;
    $btnPause.disabled = true;
    $btnPause.textContent = '⏯ 暂停';
  }

  function resolveRunStarter(data) {
    if (data && data.runStartedBy) {
      lastKnownRunStartedBy = data.runStartedBy;
    }
    return (data && data.runStartedBy) || lastKnownRunStartedBy
      || (data && data.taskConfig && data.taskConfig.operator) || '';
  }

  function applyTaskCompleteUi(data, tick, rate) {
    $tick.textContent = '节拍: ' + tick;
    var startedBy = resolveRunStarter(data);
    if (isCurrentRunOperator(startedBy)) {
      $rate.textContent = '探索率: ' + rate + '% ✓ 任务完成';
      $modeTag.textContent = '✓ 任务完成';
    } else if (startedBy) {
      $rate.textContent = '探索率: ' + rate + '% ✓ 任务完成（观看）';
      $modeTag.textContent = '✓ 由 ' + startedBy + ' 发起的任务已完成';
    } else {
      $rate.textContent = '探索率: ' + rate + '% ✓ 任务完成';
      $modeTag.textContent = '✓ 任务完成';
    }
    $modeTag.hidden = false;
    if (elapsedTimerId) stopElapsedTimer();
    $btnStart.disabled = false;
    $btnPause.disabled = true;
    $btnPause.textContent = '⏯ 暂停';
    if (!taskCompleteShown) {
      taskCompleteShown = true;
    }
    if (!savePopupShown && isCurrentRunOperator(startedBy)) {
      savePopupShown = true;
      var snapshot = Object.assign({}, data, { tick: tick, explorationRate: rate });
      showSavePopup(snapshot, rate);
    }
  }

  function isCurrentRunOperator(runStartedBy) {
    var starter = runStartedBy || localRunStarter || '';
    if (!starter || starter === 'unknown') {
      return localRunStarter !== '' && localRunStarter !== 'unknown'
        && currentOperator === localRunStarter;
    }
    if (currentOperator === starter) {
      return true;
    }
    return localRunStarter !== '' && localRunStarter === starter;
  }

  function refreshCurrentOperator(done) {
    if (!window.Auth || typeof Auth.getCurrentUser !== 'function') {
      done();
      return;
    }
    Auth.getCurrentUser().then(function (user) {
      if (user && user.success) {
        currentOperator = user.username || user.displayName || 'unknown';
      }
      done();
    }).catch(function () { done(); });
  }

  function updateGlobalInfo(data) {
    if (taskCompleteShown && simulationFrozenTick !== null) {
      applyTaskCompleteUi(data, simulationFrozenTick, 100);
      return;
    }

    var rate = data.explorationRate || 0;
    var tick = data.tick || 0;
    if (isSimulationComplete(data)) {
      simulationFrozenTick = tick;
      applyTaskCompleteUi(data, tick, 100);
      return;
    }

    $tick.textContent = '节拍: ' + tick;
    $rate.textContent = '探索率: ' + rate + '%';
    syncControlButtons(data);
    if (data.tick >= 1 && !startTimestamp) {
      startTimestamp = Date.now(); startElapsedTimer();
    }
  }

  function showSavePopup(data, rate) {
    var elapsed = 0;
    if (startTimestamp) {
      elapsed = Math.floor((Date.now() - startTimestamp) / 1000);
    }
    var overlay = document.createElement('div');
    overlay.style.cssText = 'display:flex;position:fixed;top:0;left:0;width:100%;height:100%;'
      + 'background:rgba(0,0,0,0.5);z-index:9999;justify-content:center;align-items:center';
    overlay.innerHTML =
      '<div style="background:#FFF;border-radius:16px;padding:36px;width:400px;text-align:center;'
      + 'box-shadow:0 8px 40px rgba(0,0,0,0.2)">'
      + '<div style="font-size:52px;margin-bottom:16px">✅</div>'
      + '<h2 style="color:#1E293B;margin-bottom:10px">探索完成！</h2>'
      + '<p style="color:#64748B;font-size:14px;margin-bottom:24px">探索率:' + rate
      + '% | 耗时:' + elapsed + 's | 是否保存？</p>'
      + '<div style="display:flex;gap:12px">'
      + '<button id="ap-save" style="flex:1;height:48px;background:#3B82F6;color:#fff;border:none;'
      + 'border-radius:10px;font-size:15px;cursor:pointer;font-weight:600">💾 保存记录</button>'
      + '<button id="ap-discard" style="flex:1;height:48px;background:#F1F5F9;color:#64748B;'
      + 'border:1px solid #CBD5E1;border-radius:10px;font-size:15px;cursor:pointer;font-weight:600">'
      + '🗑 不保存</button></div></div>';
    document.body.appendChild(overlay);
    document.getElementById('ap-save').onclick = function () {
      overlay.remove();
      var steps = 0, effective = 0, count = data.cars ? data.cars.length : 0;
      var cars = [];
      for (var i = 0; i < (data.cars || []).length; i++) {
        var car = data.cars[i];
        var carSteps = car.steps || 0;
        var carEffective = car.effectiveSteps || 0;
        steps += carSteps;
        effective += carEffective;
        cars.push({
          carId: 'Car' + String(car.number).padStart(3, '0'),
          steps: carSteps,
          effectiveSteps: carEffective,
          status: car.status || ''
        });
      }
      var wastedSteps = steps - effective;
      var efficiencyPercent = steps > 0 ? Math.round(effective / steps * 100) : 0;
      var balanceScore = computeBalanceScore(cars);
      var algorithm = ($cfgAlgorithm && $cfgAlgorithm.value) || (data.taskConfig && data.taskConfig.algorithm) || '';
      var obstacleRatio = parseFloat(($cfgObstacleRatio && $cfgObstacleRatio.value) || (data.taskConfig && data.taskConfig.obstacleRatio) || 0);
      var mapWidth = parseInt((data.taskConfig && data.taskConfig.mapWidth) || DEFAULT_GRID_W, 10);
      var mapHeight = parseInt((data.taskConfig && data.taskConfig.mapHeight) || DEFAULT_GRID_H, 10);
      var rec = {
        explorationRate: rate,
        tick: data.tick || 0,
        duration: elapsed,
        totalSteps: steps,
        totalEffectiveSteps: effective,
        efficiencyPercent: efficiencyPercent,
        wastedSteps: wastedSteps,
        carCount: count,
        algorithm: algorithm,
        obstacleRatio: obstacleRatio,
        mapWidth: mapWidth,
        mapHeight: mapHeight,
        balanceScore: balanceScore,
        cars: cars,
        timestamp: Date.now(),
        date: new Date().toLocaleString()
      };
      fetch('/api/analysis/records', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
        body: JSON.stringify(rec)
      }).then(function (resp) { return resp.json(); })
        .then(function (body) {
          if (body && body.success) {
            lastSavedRunId = body.runId || null;
            loadReplayRunList(lastSavedRunId);
            alert('已保存到场次 #' + body.runId + '！可在「统计分析」与「路径回放」查看。');
            return;
          }
          alert('保存失败：' + ((body && body.error) || '未知错误'));
        })
        .catch(function () {
          alert('保存失败：无法连接 Display 服务器');
        });
    };
    document.getElementById('ap-discard').onclick = function () {
      overlay.remove();
      fetch('/api/analysis/discard', {
        method: 'POST',
        headers: authHeaders()
      }).catch(function () { /* 忽略网络错误，不影响继续操作 */ });
    };
  }

  function computeBalanceScore(cars) {
    if (!cars || cars.length < 2) { return 1; }
    var effectiveVals = [];
    for (var i = 0; i < cars.length; i++) {
      effectiveVals.push(cars[i].effectiveSteps || 0);
    }
    var sum = 0;
    for (var j = 0; j < effectiveVals.length; j++) { sum += effectiveVals[j]; }
    var avg = sum / effectiveVals.length;
    if (avg === 0) { return 1; }
    var variance = 0;
    for (var k = 0; k < effectiveVals.length; k++) {
      variance += (effectiveVals[k] - avg) * (effectiveVals[k] - avg);
    }
    variance /= effectiveVals.length;
    var std = Math.sqrt(variance);
    return Math.max(0, Math.round((1 - std / avg) * 100)) / 100;
  }

  function startElapsedTimer() {
    elapsedTimerId = setInterval(function () {
      var elapsed = Math.floor((Date.now() - startTimestamp) / 1000);
      var min = String(Math.floor(elapsed / 60)).padStart(2, '0');
      var sec = String(elapsed % 60).padStart(2, '0');
      $elapsed.textContent = '⏱ ' + min + ':' + sec;
    }, 1000);
  }

  function stopElapsedTimer() { clearInterval(elapsedTimerId); elapsedTimerId = null; }

  // ══════════════ 控制面板
  function sendCommand(msg) {
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(msg));
  }

  function beginSimulationStart() {
    startTimestamp = null;
    if (elapsedTimerId) stopElapsedTimer();
    taskCompleteShown = false;
    savePopupShown = false;
    simulationFrozenTick = null;
    lastKnownRunStartedBy = '';
    lastSavedRunId = null;
    localRunStarter = currentOperator !== 'unknown' ? currentOperator : '';
    canvasReady = false;
    clearMapCaches();
    mapLayerDirty = true;
    $modeTag.hidden = true;
    liveData = {
      tick: 0,
      explorationRate: 0,
      taskConfig: buildTaskConfigFromForm(),
      cars: []
    };
    finalizeCanvas();
    if (canvasReady) {
      renderMapLayer();
      renderCarsLayer();
    }
    sendCommand({ type: 'SET_CONFIG', data: liveData.taskConfig });
    $btnStart.disabled = true;
    $btnPause.disabled = false;
    $btnPause.textContent = '⏯ 暂停';
  }

  function onStartClick() {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      alert('WebSocket 未连接，请确认 Display 模块已启动并刷新页面');
      return;
    }
    refreshCurrentOperator(beginSimulationStart);
  }

  function onPauseClick() {
    sendCommand({ type: 'TOGGLE_PAUSE' });
    var label = $btnPause.textContent;
    $btnPause.textContent = (label.indexOf('暂停') !== -1) ? '▶ 继续' : '⏯ 暂停';
  }

  function onResetClick() {
    sendCommand({ type: 'RESET' });
    clearMapCaches();
    canvasReady = false;
    $btnStart.disabled = false;
    $btnPause.disabled = true;
    $btnPause.textContent = '⏯ 暂停';
    if (elapsedTimerId) stopElapsedTimer();
    startTimestamp = null;
    $elapsed.textContent = '⏱ 00:00';
    if (mode === 'replay') exitReplay();
    taskCompleteShown = false;
    savePopupShown = false;
    simulationFrozenTick = null;
    localRunStarter = '';
    lastKnownRunStartedBy = '';
    lastSavedRunId = null;
    if ($cfgCarCount) { $cfgCarCount.value = initialCarCount; }
    liveData = null;
    replayData = null;
    replay.currentTick = 0;
    replay.maxTick = 0;
    canvasReady = false;
    mapLayerDirty = true;
    resetCanvas();
  }

  function resetCanvas() {
    if (window.UnityView && UnityView.resetForNewSimulation) {
      UnityView.resetForNewSimulation();
    } else if (window.UnityView) {
      UnityView.exit();
    }
    if ($welcome) $welcome.style.display = 'flex';
    if ($mapStack) $mapStack.style.display = 'none';
    $carsPanel.innerHTML = '<div class="car-card placeholder"><p>等待车辆数据...</p></div>';
    $leaderboard.innerHTML = '';
    $rate.textContent = '探索率: 0%';
    $tick.textContent = '节拍: 0';
    $modeTag.hidden = true;
  }

  function onAddCarClick() {
    if (!$btnAddCar || $btnAddCar.disabled || addCarPending.active) return;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      window.alert('WebSocket 未连接，无法添加小车');
      return;
    }
    $btnAddCar.disabled = true;
    $btnAddCar.textContent = '请求中...';
    sendCommand({ type: 'ADD_CAR' });
  }

  function onObstacleRatioInput() { $lblObstacleRatio.textContent = Math.round($cfgObstacleRatio.value * 100) + '%'; }
  function onTickIntervalInput() { $lblTickInterval.textContent = $cfgTickInterval.value + 'ms'; }

  function syncTickIntervalFromTaskConfig(taskConfig) {
    if (!taskConfig || !$cfgTickInterval || isEditingTickInterval) {
      return;
    }
    var remote = parseInt(taskConfig.tickInterval, 10);
    if (isNaN(remote) || remote < MIN_TICK_INTERVAL_MS || remote > MAX_TICK_INTERVAL_MS) {
      return;
    }
    var local = parseInt($cfgTickInterval.value, 10);
    if (local === remote) {
      return;
    }
    suppressTickIntervalSync = true;
    $cfgTickInterval.value = String(remote);
    onTickIntervalInput();
    suppressTickIntervalSync = false;
  }

  function onTickIntervalChange() {
    if (suppressTickIntervalSync) {
      return;
    }
    sendCommand({ type: 'SET_TICK_INTERVAL', data: { interval: parseInt($cfgTickInterval.value, 10) } });
  }

  // ══════════════ 回放
  function authHeaders() {
    var token = window.Auth ? Auth.getToken() : localStorage.getItem('auth_token');
    return token ? { Authorization: 'Bearer ' + token } : {};
  }

  function formatRunLabel(run) {
    var started = run.startedAt ? new Date(run.startedAt).toLocaleString() : '';
    return '#' + run.id + ' ' + (run.algorithm || '') + ' ' + run.explorationRate + '% tick' + run.maxTick
      + (started ? ' (' + started + ')' : '');
  }

  function loadReplayRunList(selectRunId) {
    if (!$replayRunSelect || !window.Auth || !Auth.getToken()) {
      return;
    }
    fetch('/api/replay/runs?page=1&size=50', { headers: authHeaders() })
      .then(function (r) { return r.json(); })
      .then(function (body) {
        if (!body || !body.success) {
          return;
        }
        var runs = body.runs || [];
        var html = '<option value="current">当前场次（内存）</option>';
        for (var i = 0; i < runs.length; i++) {
          var run = runs[i];
          html += '<option value="' + run.id + '">' + formatRunLabel(run) + '</option>';
        }
        $replayRunSelect.innerHTML = html;
        if (selectRunId) {
          $replayRunSelect.value = String(selectRunId);
        }
      })
      .catch(function () { /* ignore */ });
  }

  function requestReplayBySelection() {
    if (window.UnityView) UnityView.exit();
    var selected = $replayRunSelect ? $replayRunSelect.value : 'current';
    $modeTag.hidden = false;
    $modeTag.textContent = '加载回放数据...';
    if (selected === 'current') {
      selectedReplayRunId = null;
      if (replayData && replayData.maxTick > 0) {
        replay.currentTick = 0;
        replay.playing = false;
        mode = 'replay';
        $btnReplay.hidden = true;
        $btnLive.hidden = false;
        $replayControls.hidden = false;
        $modeTag.textContent = '◀ 回放中（当前场次）';
        $replaySlider.min = 0;
        $replaySlider.max = replay.maxTick;
        $replaySlider.value = 0;
        updateReplayLabel();
        renderReplayFrame();
        return;
      }
      ws.send(JSON.stringify({ type: 'REQUEST_REPLAY' }));
      return;
    }
    selectedReplayRunId = parseInt(selected, 10);
    fetch('/api/replay/runs/' + selectedReplayRunId, { headers: authHeaders() })
      .then(function (r) { return r.json(); })
      .then(function (body) {
        if (!body || !body.success || !body.data) {
          alert((body && body.error) || '加载历史场次失败');
          return;
        }
        receiveReplayData(body.data);
      })
      .catch(function () { alert('加载历史场次失败'); });
  }

  function receiveReplayData(msg) {
    replayData = msg;
    replay.maxTick = msg.maxTick || 0;
    replay.currentTick = 0;
    replay.playing = false;

    var w = msg.mapWidth || 30;
    var h = msg.mapHeight || 30;

    // 定尺寸
    var mapArea = document.querySelector('.map-area');
    if (mapArea) {
      var availW = mapArea.clientWidth - 20, availH = mapArea.clientHeight - 20;
      CELL_SIZE = Math.max(4, Math.min(Math.floor(availW / w), Math.floor(availH / h)));
      var cw = w * CELL_SIZE, ch = h * CELL_SIZE;
      mapCanvas.width = cw; mapCanvas.height = ch;
      carCanvas.width = cw; carCanvas.height = ch;
      canvasReady = true;
    }
    if ($welcome) $welcome.style.display = 'none';
    if ($mapStack) $mapStack.style.display = 'block';

    // 车位置索引
    replayData._carIndex = {};
    var histories = msg.carHistories || {};
    for (var carId in histories) {
      var arr = histories[carId];
      if (!Array.isArray(arr)) arr = [];
      var parsed = [];
      for (var i = 0; i < arr.length; i++) {
        var e = (typeof arr[i] === 'string') ? JSON.parse(arr[i]) : arr[i];
        parsed.push({ x: e.x, y: e.y, tick: e.tick });
      }
      replayData._carIndex[carId] = parsed;
    }

    // tick→mapView
    var events = msg.explorationEvents || [];
    if (!Array.isArray(events)) events = [];
    replayData._tickViews = [];
    var currentView = createEmptyView(w, h);
    var eventIdx = 0;
    var parsedEvents = [];
    for (var i2 = 0; i2 < events.length; i2++) {
      var parts = (typeof events[i2] === 'string') ? events[i2].split(',') : [events[i2].tick, events[i2].row, events[i2].col];
      parsedEvents.push({ tick: parseInt(parts[0], 10), row: parseInt(parts[1], 10), col: parseInt(parts[2], 10) });
    }
    parsedEvents.sort(function (a, b) { return a.tick - b.tick; });
    for (var tick2 = 0; tick2 <= replay.maxTick; tick2++) {
      while (eventIdx < parsedEvents.length && parsedEvents[eventIdx].tick <= tick2) {
        var ev = parsedEvents[eventIdx];
        if (ev.row >= 0 && ev.row < h && ev.col >= 0 && ev.col < w) currentView[ev.row][ev.col] = true;
        eventIdx++;
      }
      replayData._tickViews[tick2] = cloneView(currentView);
    }
    if (msg.mapViewB64) {
      var finalView = decodeBitmapB64(msg.mapViewB64, w, h);
      replayData._tickViews[replay.maxTick] = mergeMapViews(
        replayData._tickViews[replay.maxTick] || createEmptyView(w, h), finalView);
    }
    replayData._mapBlock = msg.mapBlock || [];
    replayData._mapSealed = msg.mapSealed || [];

    mode = 'replay';
    $btnReplay.hidden = true;
    $btnLive.hidden = false;
    $replayControls.hidden = false;
    $modeTag.hidden = false;
    var runLabel = msg.runId ? ('场次 #' + msg.runId) : '当前场次';
    $modeTag.textContent = '◀ 回放中（' + runLabel + '）';
    $replaySlider.min = 0;
    $replaySlider.max = replay.maxTick;
    $replaySlider.value = 0;
    updateReplayLabel();
    renderReplayFrame();
  }

  function createEmptyView(w, h) { var v = []; for (var r = 0; r < h; r++) { v[r] = []; for (var c = 0; c < w; c++) v[r][c] = false; } return v; }
  function cloneView(v) { return v.map(function (r) { return r.slice(); }); }
  function mergeMapViews(eventView, finalView) {
    var merged = cloneView(eventView);
    for (var r = 0; r < finalView.length; r++) {
      if (!finalView[r]) continue;
      if (!merged[r]) merged[r] = [];
      for (var c = 0; c < finalView[r].length; c++) {
        if (finalView[r][c]) merged[r][c] = true;
      }
    }
    return merged;
  }

  function enterReplay() {
    requestReplayBySelection();
  }

  function exitReplay() {
    if (window.UnityView) UnityView.exit();
    mode = 'replay'; stopReplayTimer(); mode = 'live';
    $btnReplay.hidden = false; $btnLive.hidden = true; $replayControls.hidden = true; $modeTag.hidden = true;
    mapLayerDirty = true;
    if (liveData) { renderMapLayer(); renderCarsLayer(); }
  }

  function renderReplayFrame() {
    if (!replayData) return;
    var tick = replay.currentTick;
    var w2 = replayData.mapWidth || 30, h2 = replayData.mapHeight || 30;
    var mapView = (replayData._tickViews && replayData._tickViews[tick]) || replayData._tickViews[0] || createEmptyView(w2, h2);

    var frame = {
      tick: tick, explorationRate: tick >= replay.maxTick ? 100 : 0,
      taskConfig: { mapWidth: String(w2), mapHeight: String(h2) },
      mapView: mapView, mapBlock: replayData._mapBlock || [],
      mapSealed: replayData._mapSealed || [], cars: []
    };

    var index = replayData._carIndex || {};
    for (var carId in index) {
      var pos = lookupReplayPosition(carId, tick);
      if (pos) {
        var num = carId.replace('Car', '');
        frame.cars.push({ carId: carId, number: parseInt(num, 10) || 0, position: pos, target: null, routeList: [], status: 'MOVING', steps: 0 });
      }
    }

    // 渲染到地图层
    liveData = frame;
    renderMapLayer();
    // 渲染车到小车层
    var ctx2 = carCtx, cs2 = CELL_SIZE;
    ctx2.clearRect(0, 0, carCanvas.width, carCanvas.height);
    for (var i = 0; i < frame.cars.length; i++) drawCar(frame.cars[i], ctx2, cs2);
    updateReplayLabel();
    liveData = null;
  }

  function lookupReplayPosition(carId, tick) {
    var arr = replayData && replayData._carIndex && replayData._carIndex[carId];
    if (!arr || arr.length === 0) return null;
    var best = null;
    for (var i = 0; i < arr.length; i++) {
      if (arr[i].tick <= tick) best = arr[i]; else break;
    }
    return best ? { x: best.x, y: best.y } : null;
  }

  function updateReplayLabel() {
    $replayTickLbl.textContent = 'Tick ' + replay.currentTick + ' / ' + replay.maxTick;
    $replaySlider.value = replay.currentTick;
  }

  function startReplayTimer() {
    stopReplayTimer(); replay.playing = true; $replayToggle.textContent = '⏸';
    replay.timerId = setInterval(replayTickForward, REPLAY_FRAME_MS);
  }
  function stopReplayTimer() {
    replay.playing = false; $replayToggle.textContent = '▶';
    if (replay.timerId) { clearInterval(replay.timerId); replay.timerId = null; }
  }
  function replayTickForward() {
    if (replay.currentTick < replay.maxTick) { replay.currentTick++; $replaySlider.value = replay.currentTick; renderReplayFrame(); }
    else stopReplayTimer();
  }

  // ══════════════ 工具
  function onCanvasContextMenu(e) {
    e.preventDefault();
    if (!liveData || !canvasReady) return;
    var rect = carCanvas.getBoundingClientRect();
    var scaleX = carCanvas.width / rect.width, scaleY = carCanvas.height / rect.height;
    var col = Math.floor((e.clientX - rect.left) * scaleX / CELL_SIZE);
    var row = Math.floor((e.clientY - rect.top) * scaleY / CELL_SIZE);
    var gridW = getGridW(liveData), gridH = getGridH(liveData);
    if (row < 0 || row >= gridH || col < 0 || col >= gridW) return;
    sendCommand({ type: 'TOGGLE_OBSTACLE', data: { row: row, col: col } });
    mapLayerDirty = true;
  }

  function getGridW(data) { return (data.taskConfig && parseInt(data.taskConfig.mapWidth, 10)) || DEFAULT_GRID_W; }
  function getGridH(data) { return (data.taskConfig && parseInt(data.taskConfig.mapHeight, 10)) || DEFAULT_GRID_H; }

  // ══════════════ 改密
  function onPwdSubmit() {
    $pwdErr.textContent = '';
    var oldP = $pwdOld.value.trim(), newP = $pwdNew.value.trim();
    if (!oldP || !newP) { $pwdErr.textContent = '请输入新旧密码'; return; }
    if (newP.length < 3) { $pwdErr.textContent = '新密码至少3位'; return; }
    fetch('/api/auth/change-password', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + (localStorage.getItem('token') || '') },
      body: JSON.stringify({ oldPassword: oldP, newPassword: newP })
    }).then(function (r) { return r.json(); }).then(function (d) {
      if (d.success) { $pwdModal.style.display = 'none'; $pwdOld.value = ''; $pwdNew.value = ''; alert('密码修改成功'); }
      else $pwdErr.textContent = d.error || '修改失败';
    });
  }

  // ══════════════ 事件绑定
  $btnStart.addEventListener('click', onStartClick);
  $btnPause.addEventListener('click', onPauseClick);
  $btnReset.addEventListener('click', onResetClick);
  if ($btnAddCar) $btnAddCar.addEventListener('click', onAddCarClick);
  carCanvas.addEventListener('contextmenu', onCanvasContextMenu);
  $btnReplay.addEventListener('click', enterReplay);
  $btnLive.addEventListener('click', exitReplay);
  $cfgObstacleRatio.addEventListener('input', onObstacleRatioInput);
  $cfgTickInterval.addEventListener('input', onTickIntervalInput);
  $cfgTickInterval.addEventListener('change', onTickIntervalChange);
  $cfgTickInterval.addEventListener('pointerdown', function () { isEditingTickInterval = true; });
  $cfgTickInterval.addEventListener('pointerup', function () { isEditingTickInterval = false; });
  $cfgTickInterval.addEventListener('pointercancel', function () { isEditingTickInterval = false; });
  $cfgTickInterval.addEventListener('blur', function () { isEditingTickInterval = false; });
  $replaySlider.addEventListener('input', onReplaySliderInput);
  $replayToggle.addEventListener('click', onReplayToggleClick);
  document.getElementById('replay-step-prev').addEventListener('click', onReplayStepPrev);
  document.getElementById('replay-step-next').addEventListener('click', onReplayStepNext);
  if ($pwdSubmit) $pwdSubmit.addEventListener('click', onPwdSubmit);
  if ($pwdCancel) $pwdCancel.addEventListener('click', function () { $pwdModal.style.display = 'none'; });
  if (document.getElementById('btn-change-pwd')) {
    document.getElementById('btn-change-pwd').addEventListener('click', function () { $pwdModal.style.display = 'flex'; });
  }

  function onReplaySliderInput() { replay.currentTick = parseInt($replaySlider.value, 10); renderReplayFrame(); }
  function onReplayToggleClick() { if (replay.playing) stopReplayTimer(); else startReplayTimer(); }
  function onReplayStepPrev() { replay.currentTick = Math.max(0, replay.currentTick - 1); renderReplayFrame(); }
  function onReplayStepNext() { replay.currentTick = Math.min(replay.maxTick, replay.currentTick + 1); renderReplayFrame(); }

  // ══════════════ 启动
  resetCanvas();
  if (window.Auth) {
    Auth.checkAuth().then(function (user) {
      if (!user || !user.success) return;
      currentOperator = user.username || user.displayName || 'unknown';
      userRole = user.role;
      Auth.renderNavBar(user);
      Auth.applyPermissions(user.role);
      loadReplayRunList();
    });
  }
  window.addEventListener('resize', function () {
    if (!liveData) return;
    canvasReady = false; finalizeCanvas();
    if (canvasReady) { mapLayerDirty = true; renderMapLayer(); renderCarsLayer(); }
    if (window.UnityView && UnityView.is3D()) UnityView.syncSize();
  });
  if (window.UnityView) {
    UnityView.init({
      getLiveData: function () { return liveData; },
      getReplayData: function () { return replayData; },
      isCanvasReady: function () { return canvasReady; },
      requestFinalizeCanvas: function () { finalizeCanvas(); }
    });
  }
  setInterval(function () {
    var token = localStorage.getItem('auth_token');
    if (token) {
      fetch('/api/auth/me', { headers: { 'Authorization': 'Bearer ' + token } });
    }
  }, 600000);
  connectWebSocket();
})();
