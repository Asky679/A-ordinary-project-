import { request } from './api.js';
import { $, $$, esc, chips, empty, task } from './ui.js';
import { postingPath } from './job-sky-layout.js';

export async function careerMap(root, signal, onRole) {
  root.innerHTML = '<section class="panel career-atlas-loading" role="status">正在整理岗位职业分类…</section>';
  let data;
  try { data = await request('/career-map',{signal}); }
  catch (cause) { if (!signal.aborted) root.innerHTML = `<section class="panel">${esc(cause.message)}</section>`; return; }
  if (signal.aborted) return;

  const familyColors = {
    development:'#8db9ff', data_ai:'#c8b6ff', quality:'#9bdcc8',
    infra_security:'#ffba8d', product_solution:'#e2cf93', architecture_mgmt:'#d6a3a3', other:'#a8b2b0'
  };
  let activeFamily = data.families.find(family=>family.roles.length)?.id || '';
  let activeRole = null;
  const familyName = id => data.families.find(family=>family.id===id)?.name || id;
  const roleName = id => {
    for (const family of data.families) {
      const role = family.roles.find(item=>item.id===id);
      if (role) return role.name;
    }
    return id;
  };

  root.innerHTML = `<section class="career-atlas" aria-labelledby="career-atlas-title">
    <header class="career-atlas-head">
      <div><span class="record-type">CAREER ATLAS</span><h2 id="career-atlas-title">职业星图</h2>
      <p>先理解职业方向和成长路径，再查看数据库中的真实岗位。</p></div>
      <div class="career-atlas-stats"><strong>${data.totalJobs}</strong><span>岗位</span><strong>${data.classifiedJobs}</strong><span>已归类</span></div>
    </header>
    <div class="career-map-orbit" aria-label="职业族地图"></div>
    <div class="career-family-detail">
      <div class="career-role-cloud" aria-label="职业方向"></div>
      <aside class="career-role-detail" aria-live="polite"></aside>
    </div>
    <p class="career-map-note">分类依据岗位标题、技能和描述自动估算，仅用于职业探索，不代表招聘方官方职级。</p>
  </section>`;

  const orbit = $('.career-map-orbit',root), cloud = $('.career-role-cloud',root), detail = $('.career-role-detail',root);

  function familyCount(family) { return family.roles.reduce((sum,role)=>sum+role.jobCount,0); }
  function renderFamilies() {
    orbit.innerHTML = data.families.filter(family=>family.roles.length).map(family=>`
      <button type="button" class="career-family-node" data-family="${esc(family.id)}"
        aria-pressed="${family.id===activeFamily}" style="--family-light:${familyColors[family.id]||'#d7d7d7'}">
        <span>${esc(family.name)}</span><strong>${familyCount(family)}</strong><small>个岗位 · ${family.roles.length} 个方向</small>
      </button>`).join('');
    $$('[data-family]',orbit).forEach(button=>button.addEventListener('click',()=>{
      activeFamily = button.dataset.family;
      activeRole = null;
      renderFamilies();
      renderRoles();
    },{signal}));
  }

  function renderRoles() {
    const family = data.families.find(item=>item.id===activeFamily);
    if (!family) { cloud.innerHTML = ''; detail.innerHTML = empty('暂无职业方向'); return; }
    cloud.innerHTML = `<header><span class="record-type">${esc(family.name)}</span><h3>${esc(family.summary)}</h3></header>
      <div class="career-role-grid">${family.roles.map(role=>`
        <button type="button" class="career-role-node" data-role="${esc(role.id)}" aria-pressed="${role.id===activeRole?.id}">
          <span>${esc(role.name)}</span><strong>${role.jobCount}</strong><small>个真实岗位</small>
        </button>`).join('')}</div>`;
    $$('[data-role]',cloud).forEach(button=>button.addEventListener('click',()=>{
      activeRole = family.roles.find(role=>role.id===button.dataset.role) || null;
      renderRoles();
      renderRole();
    },{signal}));
    renderRole();
  }

  function renderRole() {
    const role = activeRole;
    if (!role) {
      detail.innerHTML = `<div class="career-detail-empty"><span class="record-type">选择一个职业方向</span><h3>查看成长路径和代表岗位</h3><p>点击左侧方向，查看职级分布、相邻职业和数据库中的岗位样本。</p></div>`;
      return;
    }
    const levels = role.levels.filter(item=>item.count>0).map(item=>`<span>${esc(item.level)} <b>${item.count}</b></span>`).join('');
    const next = (role.nextRoles || []).map(id=>`<button type="button" class="chip career-next" data-next-role="${esc(id)}">${esc(roleName(id))}</button>`).join('');
    const samples = role.sampleJobs?.length ? role.sampleJobs.map(job=>`
      <a class="career-sample" href="#${postingPath(job.id)}">
        <strong>${esc(job.title)}</strong><span>${[job.company,job.city,job.salary].filter(Boolean).map(esc).join(' · ')}</span>
      </a>`).join('') : '<p class="muted">暂无样本岗位</p>';
    detail.innerHTML = `<span class="record-type">${esc(role.familyName)} · 职业方向</span>
      <h3>${esc(role.name)}</h3>
      <p><strong>${role.jobCount}</strong> 个岗位 · 常见技能</p>
      <div class="chips">${chips(role.skills)}</div>
      <div class="career-levels"><span>当前岗位库职级分布</span><div>${levels || '<span>职级未知</span>'}</div></div>
      <div class="career-growth"><span>常见发展路径</span><div class="career-level-path"><b>初级</b><i>→</i><b>中级</b><i>→</i><b>高级</b><i>→</i><b>架构师 / 技术管理</b></div>
      ${next ? `<small>可横向迁移或继续发展：${next}</small>` : ''}</div>
      <h4>代表岗位</h4><div class="career-samples">${samples}</div>
      <button type="button" class="button primary" id="career-search-role">查看这个方向的全部岗位 ↓</button>`;
    $('#career-search-role',detail)?.addEventListener('click',()=>onRole?.(role),{signal});
    $$('[data-next-role]',detail).forEach(button=>button.addEventListener('click',()=>{
      for (const family of data.families) {
        const nextRole = family.roles.find(item=>item.id===button.dataset.nextRole);
        if (nextRole) { activeFamily = family.id; activeRole = nextRole; renderFamilies(); renderRoles(); return; }
      }
    },{signal}));
  }

  renderFamilies();
  renderRoles();
}
