import { request } from './api.js';
import { showDialog, task, notify, esc } from './ui.js';

const TOKEN_KEY = 'careernav-auth-token';
const gate = () => document.getElementById('auth-gate');
const errorBox = () => document.getElementById('auth-error');
const userBox = () => document.getElementById('auth-user');
const logoutButton = () => document.getElementById('logout');
const demoAdminButton = () => document.getElementById('demo-admin');
let onAuthenticated = null;
let currentUserValue = null;

function token() { return localStorage.getItem(TOKEN_KEY) || ''; }

async function authFetch(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  const value = token();
  if (value) headers.Authorization = 'Bearer ' + value;
  const response = await fetch('/api/auth' + path, {
    ...options, headers, credentials: 'same-origin'
  });
  let payload;
  try { payload = await response.json(); }
  catch { throw new Error('认证服务返回了无法识别的内容。'); }
  if (!response.ok || payload.success === false) throw new Error(payload.message || '操作失败');
  return payload.data;
}

function applyUser(user) {
  const label = userBox();
  const logout = logoutButton();
  const demoToggle = document.getElementById('demo-toggle');
  const demoAdmin = demoAdminButton();
  currentUserValue = user;
  if (!user) {
    label.hidden = true;
    label.textContent = '';
    logout.hidden = true;
    demoToggle.hidden = true;
    if (demoAdmin) demoAdmin.hidden = true;
    return;
  }
  label.hidden = false;
  label.textContent = `${user.displayName || user.username} · ${user.role === 'ADMIN' ? '管理员' : user.role === 'DEMO' ? '演示账号' : '学生'}`;
  logout.hidden = false;
  demoToggle.hidden = user.role !== 'ADMIN';
  demoToggle.textContent = localStorage.getItem('careernav-show-demo-data') === 'true' ? '隐藏演示数据' : '显示演示数据';
  if (demoAdmin) demoAdmin.hidden = user.role !== 'ADMIN';
}

async function openDemoAdmin() {
  const panel = showDialog('演示数据管理', `
    <div id="demo-admin-panel" class="demo-admin-panel">
      <p class="muted">演示账号拥有独立的数据视图。管理员可以创建演示账号、查看测试数据量，并一键清空演示账号下的简历、面试、诊断和故事。</p>
      <section class="note">
        <div class="demo-admin-heading"><h3>当前演示数据</h3><button class="quiet" type="button" data-demo-refresh>刷新</button></div>
        <div class="demo-admin-stats">正在读取…</div>
      </section>
      <form id="demo-account-form" class="fields">
        <h3>创建演示账号</h3>
        <label>用户名<input name="username" value="demo" minlength="3" required></label>
        <label>邮箱<input name="email" type="email" value="demo@example.com" required></label>
        <label>显示名称<input name="displayName" value="答辩演示账号" required></label>
        <label>初始密码<input name="password" type="password" minlength="8" value="Demo123456" required></label>
        <button class="button primary" type="submit">创建演示账号</button>
      </form>
      <section class="note demo-admin-danger">
        <h3>测试数据清理</h3>
        <p>只删除 DEMO 账号下的数据，不影响真实学生账号。清空后演示账号仍可继续使用。</p>
        <button class="quiet danger" type="button" data-demo-clear>一键清空演示数据</button>
      </section>
    </div>`);
  const statsBox = panel.querySelector('.demo-admin-stats');
  async function load() {
    statsBox.textContent = '正在读取…';
    try {
      const [stats, users] = await Promise.all([
        request('/admin/demo-data/stats'),
        request('/admin/users')
      ]);
      const demoUsers = (users || []).filter(item => item.role === 'DEMO');
      const cards = [
        ['演示账号', stats.demoUsers || 0],
        ['简历', stats.resumes || 0],
        ['面试', stats.interviews || 0],
        ['诊断', stats.reports || 0],
        ['故事', stats.stories || 0]
      ];
      statsBox.innerHTML = `<dl class="demo-stat-grid">${cards.map(([name, value]) =>
        `<div><dt>${esc(name)}</dt><dd>${esc(value)}</dd></div>`).join('')}</dl>
        <p class="muted">${demoUsers.length
          ? '演示账号：' + demoUsers.map(item => esc(item.displayName || item.username)).join('、')
          : '尚未创建演示账号。创建后，旧的未归属测试数据会自动归入该账号。'}</p>`;
    } catch (cause) {
      statsBox.textContent = cause.message;
    }
  }
  panel.querySelector('[data-demo-refresh]').addEventListener('click', load);
  const form = panel.querySelector('#demo-account-form');
  form.addEventListener('submit', task(form.querySelector('button'), async () => {
    const values = Object.fromEntries(new FormData(form));
    await request('/admin/demo-account', { method: 'POST', data: values });
    form.querySelector('[name="password"]').value = '';
    notify('演示账号已创建，旧的未归属测试数据已归入演示视图。');
    await load();
  }));
  const clearButton = panel.querySelector('[data-demo-clear]');
  clearButton.addEventListener('click', task(clearButton, async () => {
    if (!confirm('确定清空全部演示账号下的简历、面试、诊断和成长故事吗？真实学生数据不会受影响。')) return;
    const result = await request('/admin/demo-data', { method: 'DELETE' });
    notify(`演示数据已清空：简历 ${result.resumes || 0} 份，面试 ${result.interviews || 0} 场。`);
    await load();
    window.dispatchEvent(new CustomEvent('careernav:demo-filter-changed'));
  }));
  await load();
}

function showGate() {
  const panel = gate();
  panel.hidden = false;
  document.body.classList.add('auth-locked');
  document.getElementById('content').hidden = true;
  applyUser(null);
  errorBox().textContent = '';
  document.querySelector('[data-auth-tab="login"]')?.focus();
}

function hideGate(user) {
  gate().hidden = true;
  document.body.classList.remove('auth-locked');
  document.getElementById('content').hidden = false;
  applyUser(user);
}

function selectTab(name) {
  errorBox().textContent = '';
  for (const button of document.querySelectorAll('[data-auth-tab]')) {
    button.classList.toggle('active', button.dataset.authTab === name);
  }
  document.getElementById('login-form').hidden = name !== 'login';
  document.getElementById('register-form').hidden = name !== 'register';
}

async function submit(path, data) {
  errorBox().textContent = '';
  try {
    const result = await authFetch(path, { method: 'POST', body: JSON.stringify(data) });
    localStorage.setItem(TOKEN_KEY, result.token);
    hideGate(result.user);
    onAuthenticated?.();
  } catch (error) {
    errorBox().textContent = error.message;
  }
}

export function currentUser() { return currentUserValue; }

export function hideDemoData() {
  return currentUserValue?.role === 'ADMIN' && localStorage.getItem('careernav-show-demo-data') !== 'true';
}

export function filterDemo(items) {
  const values = Array.isArray(items) ? items : [];
  return hideDemoData() ? values.filter(item => item?.dataKind !== 'DEMO') : values;
}

export function dataKindLabel(item) {
  return item?.dataKind === 'DEMO' ? '演示数据' : item?.dataKind === 'REAL' ? '真实数据' : '历史数据';
}

export async function initAuth(authenticatedCallback) {
  onAuthenticated = authenticatedCallback;
  document.querySelectorAll('[data-auth-tab]').forEach(button => {
    button.addEventListener('click', () => selectTab(button.dataset.authTab));
  });
  document.getElementById('login-form').addEventListener('submit', event => {
    event.preventDefault();
    const values = Object.fromEntries(new FormData(event.currentTarget));
    submit('/login', { account: values.account, password: values.password });
  });
  document.getElementById('register-form').addEventListener('submit', event => {
    event.preventDefault();
    const values = Object.fromEntries(new FormData(event.currentTarget));
    if (values.password !== values.confirmPassword) {
      errorBox().textContent = '两次输入的密码不一致';
      return;
    }
    submit('/register', {
      username: values.username, email: values.email,
      password: values.password, displayName: values.displayName
    });
  });
  demoAdminButton()?.addEventListener('click', openDemoAdmin);
  document.getElementById('demo-toggle').addEventListener('click', () => {
    const showing = localStorage.getItem('careernav-show-demo-data') === 'true';
    localStorage.setItem('careernav-show-demo-data', String(!showing));
    applyUser(currentUserValue);
    window.dispatchEvent(new CustomEvent('careernav:demo-filter-changed'));
  });
  logoutButton().addEventListener('click', async () => {
    try { await authFetch('/logout', { method: 'POST', body: '{}' }); } catch {}
    localStorage.removeItem(TOKEN_KEY);
    showGate();
  });
  window.addEventListener('careernav:unauthorized', showGate);
  if (!token()) {
    showGate();
    return false;
  }
  try {
    const user = await authFetch('/me');
    hideGate(user);
    return true;
  } catch {
    localStorage.removeItem(TOKEN_KEY);
    showGate();
    return false;
  }
}
