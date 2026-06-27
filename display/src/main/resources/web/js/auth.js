/**
 * 变电站巡检仿真系统 — 认证模块。
 *
 * 单用户单会话：新登录挤掉旧会话；同浏览器单窗口（loginSeq + Tab 互斥）。
 */

(function () {
  'use strict';

  var TOKEN_KEY = 'auth_token';
  var LEADER_KEY = 'auth_active_tab';
  var LOGIN_SEQ_KEY = 'auth_login_seq';
  var KICK_MSG_KEY = 'auth_kick_message';
  var FRESH_LOGIN_KEY = 'auth_fresh_login';
  var LOGIN_URL = '/login.html';
  var SESSION_POLL_MS = 3000;
  var TAB_HEARTBEAT_MS = 2000;
  var TAB_STALE_MS = 5000;
  var KICKED_MSG = '您的账号已在其他设备或窗口登录，当前会话已结束';
  var OTHER_TAB_MSG = '当前账号已在其他窗口打开，本窗口已退出';

  var tabId = sessionStorage.getItem('auth_tab_id');
  if (!tabId) {
    tabId = 'tab_' + Date.now() + '_' + Math.random().toString(36).slice(2);
    sessionStorage.setItem('auth_tab_id', tabId);
  }

  var forcedLogoutHandled = false;
  var sessionWatchStarted = false;

  function getToken() {
    return localStorage.getItem(TOKEN_KEY);
  }

  function setToken(token) {
    localStorage.setItem(TOKEN_KEY, token);
  }

  function clearToken() {
    localStorage.removeItem(TOKEN_KEY);
  }

  function getGlobalLoginSeq() {
    return localStorage.getItem(LOGIN_SEQ_KEY);
  }

  function getTabLoginSeq() {
    return sessionStorage.getItem(LOGIN_SEQ_KEY);
  }

  function isStaleTab() {
    var globalSeq = getGlobalLoginSeq();
    var tabSeq = getTabLoginSeq();
    return !!(globalSeq && tabSeq && globalSeq !== tabSeq);
  }

  function readLeader() {
    try {
      return JSON.parse(localStorage.getItem(LEADER_KEY));
    } catch (e) {
      return null;
    }
  }

  function claimTabLeadership() {
    localStorage.setItem(LEADER_KEY, JSON.stringify({
      tabId: tabId,
      ts: Date.now(),
      seq: getTabLoginSeq()
    }));
  }

  function isOtherTabActive() {
    if (isStaleTab()) {
      return true;
    }
    var leader = readLeader();
    if (!leader || leader.tabId === tabId) {
      return false;
    }
    if (leader.seq && getTabLoginSeq() && leader.seq !== getTabLoginSeq()) {
      return false;
    }
    return (Date.now() - leader.ts) < TAB_STALE_MS;
  }

  function storeKickMessage(message) {
    try {
      sessionStorage.setItem(KICK_MSG_KEY, message);
    } catch (e) { /* ignore */ }
  }

  function showKickMessageIfAny() {
    var message = sessionStorage.getItem(KICK_MSG_KEY);
    if (!message) {
      return;
    }
    sessionStorage.removeItem(KICK_MSG_KEY);
    alert(message);
  }

  /** @param {boolean} wipeSharedSession 是否清除 localStorage 中的 token（仅本 tab 有效或主动登出时为 true） */
  function redirectToLogin(message, wipeSharedSession) {
    if (forcedLogoutHandled) {
      return;
    }
    forcedLogoutHandled = true;
    if (message) {
      storeKickMessage(message);
    }
    if (wipeSharedSession) {
      clearToken();
      localStorage.removeItem(LOGIN_SEQ_KEY);
      localStorage.removeItem(LEADER_KEY);
    }
    if (window.location.pathname.indexOf('login.html') === -1) {
      window.location.href = LOGIN_URL;
    }
  }

  function handleServerKicked(message) {
    redirectToLogin(message || KICKED_MSG, true);
  }

  function handleDisplacedByNewLogin(message) {
    redirectToLogin(message || KICKED_MSG, false);
  }

  function handleOtherTabTaken(message) {
    redirectToLogin(message || OTHER_TAB_MSG, false);
  }

  async function parseAuthResponse(resp) {
    try {
      return await resp.json();
    } catch (e) {
      return {};
    }
  }

  async function apiCall(method, path, body) {
    var headers = { 'Content-Type': 'application/json' };
    var token = getToken();
    if (token) {
      headers.Authorization = 'Bearer ' + token;
    }
    var opts = { method: method, headers: headers };
    if (body) {
      opts.body = JSON.stringify(body);
    }
    var resp = await fetch(path, opts);
    if (resp.status === 401) {
      var data = await parseAuthResponse(resp);
      if (data.kicked) {
        handleServerKicked(data.error || KICKED_MSG);
      } else {
        redirectToLogin('登录已失效，请重新登录', true);
      }
      return null;
    }
    return resp.json();
  }

  async function login(username, password) {
    var resp = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: username, password: password })
    });
    var data = await resp.json();
    if (data && data.success) {
      onLoginSuccess(data.token);
    }
    return data;
  }

  function onLoginSuccess(token) {
    var seq = String(Date.now());
    localStorage.setItem(LOGIN_SEQ_KEY, seq);
    sessionStorage.setItem(LOGIN_SEQ_KEY, seq);
    sessionStorage.setItem(FRESH_LOGIN_KEY, '1');
    forcedLogoutHandled = false;
    setToken(token);
    claimTabLeadership();
  }

  async function logout() {
    await apiCall('POST', '/api/auth/logout');
    sessionStorage.removeItem(LOGIN_SEQ_KEY);
    sessionStorage.removeItem(FRESH_LOGIN_KEY);
    clearToken();
    localStorage.removeItem(LOGIN_SEQ_KEY);
    localStorage.removeItem(LEADER_KEY);
    window.location.href = LOGIN_URL;
  }

  async function register(username, password, displayName, role) {
    return apiCall('POST', '/api/auth/register', {
      username: username,
      password: password,
      displayName: displayName,
      role: role
    });
  }

  async function changePassword(oldPw, newPw) {
    return apiCall('POST', '/api/auth/change-password', {
      oldPassword: oldPw,
      newPassword: newPw
    });
  }

  async function getCurrentUser() {
    return apiCall('GET', '/api/auth/me');
  }

  function shouldBlockThisTab() {
    if (sessionStorage.getItem(FRESH_LOGIN_KEY)) {
      return false;
    }
    if (isStaleTab()) {
      return true;
    }
    return isOtherTabActive();
  }

  async function pollSession() {
    if (!getToken() || window.location.pathname.indexOf('login.html') !== -1) {
      return;
    }
    if (isStaleTab()) {
      handleDisplacedByNewLogin(KICKED_MSG);
      return;
    }
    if (isOtherTabActive()) {
      handleOtherTabTaken(OTHER_TAB_MSG);
      return;
    }
    var token = getToken();
    var resp = await fetch('/api/auth/me', {
      headers: { Authorization: 'Bearer ' + token }
    });
    if (resp.status !== 401) {
      return;
    }
    var data = await parseAuthResponse(resp);
    if (data.kicked) {
      handleServerKicked(data.error || KICKED_MSG);
    }
  }

  function startSessionWatch() {
    if (sessionWatchStarted) {
      return;
    }
    sessionWatchStarted = true;
    claimTabLeadership();
    setInterval(function () {
      if (!getToken() || window.location.pathname.indexOf('login.html') !== -1) {
        return;
      }
      if (isStaleTab()) {
        handleDisplacedByNewLogin(KICKED_MSG);
        return;
      }
      if (isOtherTabActive()) {
        handleOtherTabTaken(OTHER_TAB_MSG);
        return;
      }
      claimTabLeadership();
    }, TAB_HEARTBEAT_MS);
    setInterval(pollSession, SESSION_POLL_MS);
    window.addEventListener('storage', function (e) {
      if (e.key === LOGIN_SEQ_KEY || e.key === TOKEN_KEY) {
        if (isStaleTab()) {
          handleDisplacedByNewLogin(KICKED_MSG);
        }
        return;
      }
      if (e.key === LEADER_KEY && isOtherTabActive()) {
        handleOtherTabTaken(OTHER_TAB_MSG);
      }
    });
  }

  async function checkAuth() {
    showKickMessageIfAny();
    if (window.location.pathname.indexOf('login.html') !== -1) {
      if (getToken() && !isStaleTab()) {
        window.location.href = '/dashboard.html';
      }
      return null;
    }
    if (!getToken()) {
      window.location.href = LOGIN_URL;
      return null;
    }
    if (sessionStorage.getItem(FRESH_LOGIN_KEY)) {
      sessionStorage.removeItem(FRESH_LOGIN_KEY);
      claimTabLeadership();
    } else if (shouldBlockThisTab()) {
      if (isStaleTab()) {
        handleDisplacedByNewLogin(KICKED_MSG);
      } else {
        handleOtherTabTaken(OTHER_TAB_MSG);
      }
      return null;
    }
    try {
      var user = await getCurrentUser();
      if (!user || !user.success) {
        return null;
      }
      startSessionWatch();
      return user;
    } catch (e) {
      return null;
    }
  }

  var ROLE_NAMES = { admin: '管理员', simulator: '仿真员', analyst: '统计分析员' };

  function applyPermissions(role) {
    if (role === 'admin') {
      return;
    }
    if (role === 'analyst') {
      ['btn-start', 'btn-pause', 'btn-reset', 'btn-addcar'].forEach(function (id) {
        var el = document.getElementById(id);
        if (el) {
          el.style.display = 'none';
        }
      });
      ['cfg-obstacleRatio', 'cfg-algorithm', 'cfg-tickInterval'].forEach(function (id) {
        var el = document.getElementById(id);
        if (el) {
          el.disabled = true;
        }
      });
    }
    if (role === 'simulator') {
      var navLink = document.querySelector('.nav-link[href="analysis.html"]');
      if (navLink) {
        navLink.style.display = 'none';
      }
    }
  }

  function renderNavBar(user) {
    var userEl = document.getElementById('info-user');
    if (userEl && user) {
      var label = user.displayName || ROLE_NAMES[user.role] || user.username || '用户';
      userEl.textContent = '\uD83D\uDC64 ' + label;
    }
    var logoutBtn = document.getElementById('btn-logout');
    if (logoutBtn) {
      logoutBtn.addEventListener('click', logout);
    }
    var cpwBtn = document.getElementById('btn-changepw');
    var cpwModal = document.getElementById('changepw-modal');
    if (cpwBtn && cpwModal) {
      cpwBtn.addEventListener('click', function (e) {
        e.preventDefault();
        cpwModal.style.display = 'flex';
      });
      document.getElementById('cpw-cancel').addEventListener('click', function () {
        cpwModal.style.display = 'none';
      });
      document.getElementById('cpw-submit').addEventListener('click', async function () {
        var oldPw = document.getElementById('cpw-old').value;
        var newPw = document.getElementById('cpw-new').value;
        var cfmPw = document.getElementById('cpw-confirm').value;
        var errEl = document.getElementById('cpw-err');
        if (!oldPw || !newPw || !cfmPw) {
          errEl.textContent = '请填写所有字段';
          return;
        }
        if (newPw.length < 6) {
          errEl.textContent = '新密码至少6位';
          return;
        }
        if (newPw !== cfmPw) {
          errEl.textContent = '两次新密码不一致';
          return;
        }
        try {
          var resp = await changePassword(oldPw, newPw);
          if (resp && resp.success) {
            alert('密码修改成功');
            cpwModal.style.display = 'none';
          } else {
            errEl.textContent = (resp && resp.error) || '修改失败';
          }
        } catch (e) {
          errEl.textContent = '网络错误';
        }
      });
    }
  }

  window.Auth = {
    login: login,
    logout: logout,
    register: register,
    changePassword: changePassword,
    checkAuth: checkAuth,
    getCurrentUser: getCurrentUser,
    getToken: getToken,
    onLoginSuccess: onLoginSuccess,
    showKickMessageIfAny: showKickMessageIfAny,
    applyPermissions: applyPermissions,
    renderNavBar: renderNavBar
  };
})();
