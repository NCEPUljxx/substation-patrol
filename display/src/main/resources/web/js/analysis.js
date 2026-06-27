/**
 * 统计分析 — 服务端 sim_records（Person D SQL Server）
 * 步数有效率 / 均衡条 / 筛选与勾选对比
 */
(function () {
  'use strict';

  var MAX_STORED_RECORDS = 50;
  var EXPORT_VERSION = 1;
  var EFF_GREEN = '#10B981';
  var WASTED_ORANGE = '#F97316';
  var MAX_COMPARE = 5;

  var records = [];
  var filterAlgo = '';
  var selectMode = false;
  var selected = {};
  var loadFailed = false;
  var $wrap, $toolbar, $fsd, $fsdBody, $fsdTitle, $compareModal, $comparePanel, $importFile;

  function authHeaders() {
    var token = window.Auth ? Auth.getToken() : localStorage.getItem('auth_token');
    var headers = { 'Content-Type': 'application/json' };
    if (token) { headers.Authorization = 'Bearer ' + token; }
    return headers;
  }

  async function init() {
    var user = await Auth.checkAuth();
    if (!user) { return; }
    Auth.renderNavBar(user);
    $wrap = document.getElementById('list-wrap');
    $toolbar = document.getElementById('list-toolbar');
    $fsd = document.getElementById('fsd');
    $fsdBody = document.getElementById('fsd-body');
    $fsdTitle = document.getElementById('fsd-title');
    $compareModal = document.getElementById('compare-modal');
    $comparePanel = document.getElementById('compare-panel');
    $importFile = document.getElementById('import-file');
    document.getElementById('fsd-close').addEventListener('click', closeDetail);
    $compareModal.addEventListener('click', function (e) {
      if (e.target === $compareModal) { closeCompareModal(); }
    });
    await loadList();
  }

  async function loadRecords() {
    loadFailed = false;
    try {
      var resp = await fetch('/api/analysis/records?page=1&size=' + MAX_STORED_RECORDS, {
        headers: authHeaders()
      });
      if (resp.status === 401) {
        loadFailed = true;
        records = [];
        return;
      }
      var body = await resp.json();
      records = (body && body.success && Array.isArray(body.records)) ? body.records : [];
    } catch (e) {
      loadFailed = true;
      records = [];
    }
  }

  function fmtDate(ts) {
    var d = new Date(ts);
    return d.getFullYear() + '-' + (d.getMonth() + 1) + '-' + d.getDate() + ' '
      + d.getHours() + ':' + String(d.getMinutes()).padStart(2, '0') + ':'
      + String(d.getSeconds()).padStart(2, '0');
  }

  function getEfficiency(r) {
    if (r.efficiencyPercent != null) { return r.efficiencyPercent; }
    if (r.totalSteps > 0 && r.totalEffectiveSteps != null) {
      return Math.round(r.totalEffectiveSteps / r.totalSteps * 100);
    }
    return null;
  }

  function getWasted(r) {
    if (r.wastedSteps != null) { return r.wastedSteps; }
    var effective = r.totalEffectiveSteps != null ? r.totalEffectiveSteps : 0;
    return (r.totalSteps || 0) - effective;
  }

  function effColor(pct) {
    if (pct == null) { return '#94A3B8'; }
    if (pct > 60) { return EFF_GREEN; }
    if (pct >= 40) { return '#F59E0B'; }
    return '#EF4444';
  }

  function recordPassesFilter(r) {
    if (!filterAlgo) { return true; }
    return (r.algorithm || '').toUpperCase() === filterAlgo.toUpperCase();
  }

  function filteredIndices() {
    var out = [];
    for (var i = 0; i < records.length; i++) {
      if (recordPassesFilter(records[i])) { out.push(i); }
    }
    return out;
  }

  function selectedCount() {
    var n = 0;
    for (var k in selected) { if (selected[k]) { n++; } }
    return n;
  }

  function recordLabel(r, index) {
    var runId = r.runId != null ? r.runId : r.id;
    return runId != null ? ('场次 #' + runId) : ('#' + (index + 1));
  }

  function recordRunId(r) {
    return r.runId != null ? r.runId : r.id;
  }

  async function loadList() {
    await loadRecords();
    pruneSelection();
    renderToolbar();
    if (loadFailed) {
      $wrap.innerHTML = '<div class="empty"><div class="icon">⚠️</div><p>无法加载仿真记录</p>'
        + '<p style="font-size:12px;margin-top:8px">请确认 Display 已启动且 SQL Server 可用</p></div>';
      return;
    }
    if (!records.length) {
      selectMode = false;
      selected = {};
      $wrap.innerHTML = '<div class="empty"><div class="icon">📋</div><p>暂无仿真记录</p>'
        + '<p style="font-size:12px;margin-top:8px">完成探索并保存统计后，记录会与历史场次编号关联</p></div>';
      return;
    }
    renderCardGrid();
  }

  async function saveRecordToServer(rec) {
    var resp = await fetch('/api/analysis/records', {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify(rec)
    });
    var body = await resp.json();
    if (!body || !body.success) {
      throw new Error((body && body.error) || '保存失败');
    }
    return body.record || rec;
  }

  async function deleteRecordOnServer(runId) {
    var resp = await fetch('/api/analysis/records/' + runId, {
      method: 'DELETE',
      headers: authHeaders()
    });
    var body = await resp.json();
    if (!body || !body.success) {
      throw new Error((body && body.error) || '删除失败');
    }
  }

  function recordKey(r) {
    var runId = recordRunId(r);
    if (runId != null) { return 'run:' + runId; }
    if (r.timestamp != null) { return 'ts:' + r.timestamp; }
    return 'date:' + (r.date || '') + ':' + (r.totalSteps || 0);
  }

  function isValidRecord(item) {
    return item != null && typeof item === 'object'
      && recordRunId(item) != null
      && (item.totalSteps != null || item.explorationRate != null);
  }

  function parseImportPayload(text) {
    var parsed = JSON.parse(text);
    if (Array.isArray(parsed)) { return parsed; }
    if (parsed && Array.isArray(parsed.records)) { return parsed.records; }
    throw new Error('JSON 格式无效：需要数组或含 records 字段的对象');
  }

  function mergeImported(incoming) {
    var keySet = {};
    for (var i = 0; i < records.length; i++) {
      keySet[recordKey(records[i])] = true;
    }
    var toUpload = [];
    var skipped = 0;
    for (var j = 0; j < incoming.length; j++) {
      var item = incoming[j];
      if (!isValidRecord(item)) { skipped++; continue; }
      var key = recordKey(item);
      if (keySet[key]) { skipped++; continue; }
      keySet[key] = true;
      toUpload.push(item);
    }
    return { toUpload: toUpload, skipped: skipped };
  }

  function exportRecordsJson() {
    var payload = {
      version: EXPORT_VERSION,
      exportedAt: new Date().toISOString(),
      records: records
    };
    var blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
    var url = URL.createObjectURL(blob);
    var stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-');
    var link = document.createElement('a');
    link.href = url;
    link.download = 'sim_records_' + stamp + '.json';
    link.click();
    URL.revokeObjectURL(url);
  }

  function triggerImport() {
    $importFile.value = '';
    $importFile.click();
  }

  function handleImportFileChange() {
    var file = $importFile.files && $importFile.files[0];
    if (!file) { return; }
    var reader = new FileReader();
    reader.onload = async function () {
      try {
        var incoming = parseImportPayload(String(reader.result || ''));
        var result = mergeImported(incoming);
        var added = 0;
        for (var i = 0; i < result.toUpload.length; i++) {
          await saveRecordToServer(result.toUpload[i]);
          added++;
        }
        await loadList();
        alert('导入完成：新增 ' + added + ' 条，跳过重复/无效 ' + result.skipped + ' 条');
      } catch (e) {
        alert('导入失败：' + (e.message || '无法解析 JSON 文件'));
      }
    };
    reader.onerror = function () { alert('导入失败：无法读取文件'); };
    reader.readAsText(file, 'UTF-8');
  }

  function pruneSelection() {
    var next = {};
    for (var k in selected) {
      var idx = parseInt(k, 10);
      if (selected[k] && idx < records.length && recordPassesFilter(records[idx])) {
        next[k] = true;
      }
    }
    selected = next;
  }

  function renderToolbar() {
    var indices = filteredIndices();
    $toolbar.hidden = false;
    var stats = computeSummary(indices);
    var algos = collectAlgorithms();
    var algoOpts = '<option value="">全部算法</option>';
    for (var a = 0; a < algos.length; a++) {
      var al = algos[a];
      algoOpts += '<option value="' + al + '"' + (filterAlgo === al ? ' selected' : '') + '>' + al + '</option>';
    }
    $toolbar.innerHTML =
      '<div class="summary-row">'
      + '共 <strong>' + indices.length + '</strong> 条'
      + (stats.avg != null ? ' · 平均步数有效率 <strong>' + stats.avg + '%</strong>' : '')
      + (stats.best != null ? ' · 最佳 <span class="summary-best">#' + (stats.bestIdx + 1) + ' ' + stats.best + '%</span>' : '')
      + (stats.worst != null ? ' · 最低 <span class="summary-worst">#' + (stats.worstIdx + 1) + ' ' + stats.worst + '%</span>' : '')
      + '</div>'
      + '<div class="filter-row">'
      + '<label>筛选</label><select id="algo-filter">' + algoOpts + '</select>'
      + '<button type="button" class="btn-select-mode' + (selectMode ? ' active' : '') + '" id="btn-select-mode">'
      + (selectMode ? '取消选择' : '选择对比') + '</button>'
      + (selectMode
        ? '<button type="button" class="btn-compare" id="btn-compare"' + (selectedCount() < 2 ? ' disabled' : '')
          + '>对比已选（' + selectedCount() + '）</button>'
          + '<span style="font-size:11px;color:#94A3B8">请勾选 2～' + MAX_COMPARE + ' 条记录</span>'
        : '<span style="font-size:11px;color:#94A3B8">点击「选择对比」后可勾选记录进行并排对比</span>')
      + '</div>'
      + '<div class="io-row">'
      + '<button type="button" class="btn-io" id="btn-export-json">导出 JSON</button>'
      + '<button type="button" class="btn-io" id="btn-import-json">导入 JSON</button>'
      + '<span style="font-size:11px;color:#94A3B8">统计记录与历史场次编号一一对应；导入 JSON 需含 runId</span>'
      + '</div>';
    document.getElementById('algo-filter').addEventListener('change', function (e) {
      filterAlgo = e.target.value;
      loadList();
    });
    document.getElementById('btn-select-mode').addEventListener('click', toggleSelectMode);
    if (selectMode) {
      document.getElementById('btn-compare').addEventListener('click', openCompareModal);
    }
    document.getElementById('btn-export-json').addEventListener('click', exportRecordsJson);
    document.getElementById('btn-import-json').addEventListener('click', triggerImport);
    $importFile.onchange = handleImportFileChange;
  }

  function toggleSelectMode() {
    selectMode = !selectMode;
    if (!selectMode) { selected = {}; }
    loadList();
  }

  function collectAlgorithms() {
    var set = {};
    for (var i = 0; i < records.length; i++) {
      var al = records[i].algorithm;
      if (al) { set[al.toUpperCase()] = true; }
    }
    return Object.keys(set).sort();
  }

  function computeSummary(indices) {
    var sum = 0, count = 0, best = null, worst = null, bestIdx = -1, worstIdx = -1;
    for (var i = 0; i < indices.length; i++) {
      var eff = getEfficiency(records[indices[i]]);
      if (eff == null) { continue; }
      sum += eff;
      count++;
      if (best == null || eff > best) { best = eff; bestIdx = indices[i]; }
      if (worst == null || eff < worst) { worst = eff; worstIdx = indices[i]; }
    }
    return {
      avg: count ? Math.round(sum / count) : null,
      best: best,
      bestIdx: bestIdx,
      worst: worst,
      worstIdx: worstIdx
    };
  }

  function renderCardGrid() {
    var indices = filteredIndices();
    if (!indices.length) {
      $wrap.innerHTML = '<div class="empty"><div class="icon">🔍</div><p>当前筛选下无记录</p></div>';
      return;
    }
    var html = '<div class="a-grid">';
    for (var n = 0; n < indices.length; n++) {
      var i = indices[n];
      var r = records[i];
      var eff = getEfficiency(r);
      var effDisplay = eff != null ? eff + '%' : '--';
      var cov = r.explorationRate != null ? r.explorationRate + '%' : '--';
      var algo = r.algorithm || '--';
      var obsRatio = r.obstacleRatio != null ? Math.round(r.obstacleRatio * 100) + '%' : '--';
      var checked = selected[i] ? ' checked' : '';
      var selCls = selected[i] ? ' selected' : '';
      var modeCls = selectMode ? ' select-mode' : '';
      var checkHtml = selectMode
        ? '<input type="checkbox" class="a-check"' + checked + ' aria-label="选择记录">'
        : '';
      html += '<div class="a-card' + selCls + modeCls + '" data-idx="' + i + '">'
        + checkHtml
        + '<button class="a-del" type="button">✕</button>'
        + '<h4>仿真记录 ' + recordLabel(r, i) + '</h4>'
        + '<p>' + (r.date || fmtDate(r.timestamp)) + (r.savedBy ? ' · ' + r.savedBy : '') + '</p>'
        + '<div class="a-rate" style="color:' + effColor(eff) + '">' + effDisplay + '</div>'
        + '<div class="a-rate-label">步数有效率</div>'
        + '<div class="a-coverage">探索覆盖率 ' + cov + '</div>'
        + '<p>' + (r.carCount || 0) + ' 车 · ' + (r.duration || 0) + 's · ' + algo + ' · 障碍 ' + obsRatio + '</p>'
        + '</div>';
    }
    html += '</div>';
    $wrap.innerHTML = html;
    bindCardEvents();
  }

  function bindCardEvents() {
    var cards = $wrap.querySelectorAll('.a-card');
    for (var c = 0; c < cards.length; c++) {
      (function (card) {
        var idx = parseInt(card.getAttribute('data-idx'), 10);
        var check = card.querySelector('.a-check');
        if (check) {
          check.addEventListener('click', function (e) {
            e.stopPropagation();
            toggleSelect(idx, card);
          });
        }
        card.querySelector('.a-del').addEventListener('click', function (e) {
          e.stopPropagation();
          delRecord(idx);
        });
        card.addEventListener('click', function () {
          if (selectMode) {
            toggleSelect(idx, card);
            if (check) { check.checked = !!selected[idx]; }
          } else {
            showDetail(idx);
          }
        });
      })(cards[c]);
    }
  }

  function toggleSelect(idx, card) {
    if (!selectMode) { return; }
    if (selected[idx]) {
      delete selected[idx];
      card.classList.remove('selected');
    } else {
      if (selectedCount() >= MAX_COMPARE) {
        alert('最多勾选 ' + MAX_COMPARE + ' 条记录对比');
        var cb = card.querySelector('.a-check');
        if (cb) { cb.checked = false; }
        return;
      }
      selected[idx] = true;
      card.classList.add('selected');
    }
    updateCompareButton();
  }

  function updateCompareButton() {
    var btn = document.getElementById('btn-compare');
    if (!btn) { return; }
    btn.disabled = selectedCount() < 2;
    btn.textContent = '对比已选（' + selectedCount() + '）';
  }

  function openCompareModal() {
    var indices = [];
    for (var k in selected) { if (selected[k]) { indices.push(parseInt(k, 10)); } }
    indices.sort(function (a, b) { return a - b; });
    if (indices.length < 2) { return; }
    var headers = indices.map(function (i) { return recordLabel(records[i], i); });
    var rows = [
      { label: '步数有效率', fn: function (r) { var e = getEfficiency(r); return e != null ? e + '%' : '--'; } },
      { label: '探索覆盖率', fn: function (r) { return (r.explorationRate != null ? r.explorationRate : '--') + (r.explorationRate != null ? '%' : ''); } },
      { label: '总步数', fn: function (r) { return r.totalSteps || 0; } },
      { label: '有效步数', fn: function (r) { return r.totalEffectiveSteps != null ? r.totalEffectiveSteps : '--'; } },
      { label: '耗时', fn: function (r) { return (r.duration || 0) + 's'; } },
      { label: '节拍 tick', fn: function (r) { return r.tick || 0; } },
      { label: '算法', fn: function (r) { return r.algorithm || '--'; } },
      { label: '障碍率', fn: function (r) {
        return r.obstacleRatio != null ? Math.round(r.obstacleRatio * 100) + '%' : '--';
      }},
      { label: '均衡指数', fn: function (r) {
        return r.balanceScore != null ? Math.round(r.balanceScore * 100) + '%' : '--';
      }}
    ];
    var table = '<h3>勾选记录对比</h3><table class="compare-table"><thead><tr><th>指标</th>';
    for (var h = 0; h < headers.length; h++) { table += '<th>' + headers[h] + '</th>'; }
    table += '</tr></thead><tbody>';
    for (var ri = 0; ri < rows.length; ri++) {
      table += '<tr><th>' + rows[ri].label + '</th>';
      for (var ci = 0; ci < indices.length; ci++) {
        table += '<td>' + rows[ri].fn(records[indices[ci]]) + '</td>';
      }
      table += '</tr>';
    }
    table += '</tbody></table><button type="button" class="compare-close" id="compare-close">关闭</button>';
    $comparePanel.innerHTML = table;
    $compareModal.classList.add('active');
    document.getElementById('compare-close').addEventListener('click', closeCompareModal);
  }

  function closeCompareModal() { $compareModal.classList.remove('active'); }

  async function delRecord(i) {
    var r = records[i];
    if (!r) { return; }
    if (!confirm('确定要删除该仿真记录吗？此操作不可恢复。')) { return; }
    var runId = recordRunId(r);
    if (runId == null) {
      records.splice(i, 1);
      loadList();
      closeDetail();
      return;
    }
    try {
      await deleteRecordOnServer(runId);
      await loadList();
      closeDetail();
    } catch (e) {
      alert('删除失败：' + (e.message || '未知错误'));
    }
  }

  function closeDetail() { $fsd.classList.remove('active'); }

  function showDetail(i) {
    var r = records[i];
    if (!r) { return; }
    $fsdTitle.textContent = '仿真记录 ' + recordLabel(r, i) + ' - ' + (r.date || fmtDate(r.timestamp));
    var eff = getEfficiency(r);
    var effDisplay = eff != null ? eff + '%' : '--';
    var cov = r.explorationRate != null ? r.explorationRate + '%' : '--';
    var wasted = getWasted(r);
    var effective = r.totalEffectiveSteps != null ? r.totalEffectiveSteps : '--';
    var algo = r.algorithm || '--';
    var obsRatio = r.obstacleRatio != null ? Math.round(r.obstacleRatio * 100) + '%' : '--';
    var balancePct = r.balanceScore != null ? Math.round(r.balanceScore * 100) + '%' : '--';
    var carData = getCarData(r);

    $fsdBody.innerHTML =
      '<div class="krow">'
      + kpi('步数有效率', effDisplay, effColor(eff))
      + kpi('探索覆盖率', cov, '#3B82F6')
      + kpi('总步数', r.totalSteps || 0, '#3B82F6')
      + kpi('有效步数', effective, EFF_GREEN)
      + kpi('无效步数', wasted, WASTED_ORANGE)
      + kpi('耗时', (r.duration || 0) + 's', '#8B5CF6')
      + '</div>'
      + '<div class="info-row">'
      + '<span>场次: #' + (recordRunId(r) || '--') + '</span>'
      + '<span>节拍: ' + (r.tick || 0) + '</span>'
      + '<span>车辆: ' + (r.carCount || 0) + '</span>'
      + '<span>算法: ' + algo + '</span>'
      + '<span>障碍率: ' + obsRatio + '</span>'
      + '</div>'
      + buildBalanceBarsHtml(carData, balancePct)
      + '<div class="krow"><div class="cbox" style="flex:1;min-height:320px" id="d1"></div>'
      + '<div class="cbox" style="flex:1;min-height:320px" id="d2"></div></div>';
    $fsd.classList.add('active');
    setTimeout(function () { renderDetailCharts(r, carData); }, 300);
  }

  function kpi(title, value, color) {
    return '<div class="kcard"><h5>' + title + '</h5><div class="v" style="color:' + color + '">' + value + '</div></div>';
  }

  function buildBalanceBarsHtml(carData, balancePct) {
    var maxEff = 1;
    for (var i = 0; i < carData.effective.length; i++) {
      maxEff = Math.max(maxEff, carData.effective[i] || 0);
    }
    var rows = '';
    for (var j = 0; j < carData.labels.length; j++) {
      var val = carData.effective[j] || 0;
      var pct = Math.round(val / maxEff * 100);
      rows += '<div class="balance-row"><span class="lbl">' + carData.labels[j] + '</span>'
        + '<div class="track"><div class="fill" style="width:' + pct + '%"></div></div>'
        + '<span class="val">' + val + '</span></div>';
    }
    return '<div class="balance-box"><h5>各车有效步数均衡条</h5>' + rows
      + '<p class="balance-hint">均衡指数 ' + balancePct + '（越接近 100% 表示各车贡献越均匀）</p></div>';
  }

  function getCarData(r) {
    var cars = r.cars || [];
    if (!cars.length) {
      var count = r.carCount || 3;
      var labels = [], effective = [], wasted = [];
      var perCarSteps = Math.floor((r.totalSteps || 0) / count);
      var perCarEff = r.totalEffectiveSteps != null ? Math.floor(r.totalEffectiveSteps / count) : 0;
      for (var i = 0; i < count; i++) {
        labels.push('Car' + String(i + 1).padStart(3, '0'));
        effective.push(perCarEff);
        wasted.push(Math.max(0, perCarSteps - perCarEff));
      }
      return { labels: labels, effective: effective, wasted: wasted };
    }
    var labels2 = [], eff2 = [], wst2 = [];
    for (var j = 0; j < cars.length; j++) {
      labels2.push(cars[j].carId || ('Car' + (j + 1)));
      var carEff = cars[j].effectiveSteps || 0;
      var carSteps = cars[j].steps || 0;
      eff2.push(carEff);
      wst2.push(Math.max(0, carSteps - carEff));
    }
    return { labels: labels2, effective: eff2, wasted: wst2 };
  }

  function renderDetailCharts(r, carData) {
    var effective = r.totalEffectiveSteps != null ? r.totalEffectiveSteps : 0;
    donutC('d1', '有效 vs 无效步数', effective, getWasted(r));
    stackedBarC('d2', '各车有效 / 无效步数', carData.labels, carData.effective, carData.wasted);
  }

  function donutC(id, title, effective, wasted) {
    var b = document.getElementById(id);
    if (!b) { return; }
    var w = b.clientWidth - 20, h = b.clientHeight - 12;
    if (w < 80 || h < 80) { return; }
    var c = ensureCanvas(b, w, h);
    var ctx = c.getContext('2d');
    var total = effective + wasted;
    var pct = total > 0 ? Math.round(effective / total * 100) : 0;
    var clr = effColor(pct);
    var cx = w / 2, cy = h / 2 + 6;
    var outerR = Math.min(w, h) / 2 - 34;
    var innerR = outerR * 0.62;
    ctx.fillStyle = '#1E293B'; ctx.font = 'bold 13px sans-serif'; ctx.textAlign = 'left';
    ctx.fillText(title, 10, 18);
    ctx.beginPath();
    ctx.arc(cx, cy, outerR, 0, Math.PI * 2);
    ctx.arc(cx, cy, innerR, Math.PI * 2, 0, true);
    ctx.fillStyle = '#F1F5F9'; ctx.fill();
    if (total > 0) {
      var sweep = (effective / total) * Math.PI * 2;
      ctx.beginPath();
      ctx.arc(cx, cy, outerR, -Math.PI / 2, -Math.PI / 2 + sweep);
      ctx.arc(cx, cy, innerR, -Math.PI / 2 + sweep, -Math.PI / 2, true);
      ctx.fillStyle = clr; ctx.fill();
    }
    ctx.fillStyle = '#1E293B'; ctx.font = 'bold 38px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.fillText(pct + '%', cx, cy - 2);
    ctx.fillStyle = '#64748B'; ctx.font = '12px sans-serif';
    ctx.fillText('步数有效率', cx, cy + 24);
  }

  function stackedBarC(id, title, labels, effectiveVals, wastedVals) {
    var b = document.getElementById(id);
    if (!b) { return; }
    var w = b.clientWidth - 20, h = b.clientHeight - 12;
    if (w < 80 || h < 80) { return; }
    var c = ensureCanvas(b, w, h);
    var ctx = c.getContext('2d');
    var pad = { top: 36, right: 24, bottom: 36, left: 52 };
    var pw = w - pad.left - pad.right, ph = h - pad.top - pad.bottom;
    var n = labels.length;
    var maxTotal = 5;
    for (var i = 0; i < n; i++) {
      maxTotal = Math.max(maxTotal, (effectiveVals[i] || 0) + (wastedVals[i] || 0));
    }
    var bw = Math.min(40, pw / n * 0.55);
    var gap = pw / n;
    ctx.fillStyle = '#1E293B'; ctx.font = 'bold 13px sans-serif'; ctx.textAlign = 'left';
    ctx.fillText(title, pad.left, 18);
    for (var j = 0; j < n; j++) {
      var eff = effectiveVals[j] || 0;
      var wst = wastedVals[j] || 0;
      var total = eff + wst;
      var totalH = total / maxTotal * ph;
      var effH = total > 0 ? (eff / total) * totalH : 0;
      var x = pad.left + j * gap + gap / 2 - bw / 2;
      var bottomY = h - pad.bottom;
      ctx.fillStyle = WASTED_ORANGE;
      ctx.fillRect(x, bottomY - totalH, bw, totalH - effH);
      ctx.fillStyle = EFF_GREEN;
      ctx.fillRect(x, bottomY - effH, bw, effH);
      ctx.fillStyle = '#475569'; ctx.font = '11px sans-serif'; ctx.textAlign = 'center';
      ctx.fillText(total, x + bw / 2, bottomY - totalH - 4);
      ctx.fillStyle = '#64748B'; ctx.font = '10px sans-serif'; ctx.textBaseline = 'top';
      ctx.fillText(labels[j], x + bw / 2, bottomY + 6);
    }
  }

  function ensureCanvas(parent, w, h) {
    var c = parent.querySelector('canvas');
    if (!c) { c = document.createElement('canvas'); parent.appendChild(c); }
    c.width = w; c.height = h;
    c.style.width = '100%'; c.style.height = '100%';
    return c;
  }

  init();
})();
