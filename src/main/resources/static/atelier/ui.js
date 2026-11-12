export const routes = {
  home: ['城市首页','✺'],
  studio: ['简历工作室','▤'],
  jobs: ['职业天文馆','✧'],
  interview: ['面试咖啡馆','◒'],
  growth: ['成长花园','❋'],
  project: ['项目观察室','⌘'],
  workflow: ['求职准备','']
};
export const placeMark = route =>
  `<span class="scene-place-mark" data-place-mark="${route}" aria-hidden="true">${routes[route][1]}</span>`;
export const $ = (query, root = document) => root.querySelector(query);
export const $$ = (query, root = document) => [...root.querySelectorAll(query)];
export const esc = (value = '') => String(value ?? '').replace(/[&<>"']/g, char =>
  ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
export const text = (value, fallback = '—') => esc(value || fallback);
export const date = value => value ? esc(String(value).replace('T',' ').slice(0,16)) : '—';
export const link = (route, label, cls = 'button') => `<a class="${cls}" href="#${route}">${esc(label)}</a>`;
export const options = (list, selected, label) => list.map(item =>
  `<option value="${esc(item.id)}" ${String(selected) === String(item.id) ? 'selected' : ''}>${esc(label(item))}</option>`).join('');
export const chips = list => (list || []).map(item => `<span class="chip">${esc(item)}</span>`).join('');
export const empty = (heading, detail = '') => `<div class="empty"><strong>${esc(heading)}</strong><p>${esc(detail)}</p></div>`;
export const error = cause => empty('暂时无法读取', cause.message);
export function external(url, label) {
  try {
    const parsed = new URL(url);
    if (['https:','http:'].includes(parsed.protocol)) return `<a href="${esc(parsed.href)}" target="_blank" rel="noopener noreferrer">${esc(label)} ↗</a>`;
  } catch {}
  return '';
}
export function showDialog(title, body) {
  $('#dialog-content').innerHTML = `<h2 id="dialog-title">${esc(title)}</h2>${body}`;
  const dialog = $('#dialog');
  if (!dialog.open) dialog.showModal();
  return $('#dialog-content');
}

let noticeTimer;
export function notify(message, bad = false) {
  const notice = $('#notice');
  notice.textContent = message;
  notice.dataset.visible = 'true';
  notice.dataset.bad = String(bad);
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => { notice.dataset.visible = 'false'; }, 4500);
}

export function task(button, fn, failure = cause => notify(cause.message, true)) {
  return async event => {
    event?.preventDefault();
    if (button.disabled) return;
    button.disabled = true;
    button.setAttribute('aria-busy','true');
    try { await fn(event); }
    catch (cause) { if (cause.name !== 'AbortError') failure(cause); }
    finally {
      button.disabled = false;
      button.removeAttribute('aria-busy');
    }
  };
}
