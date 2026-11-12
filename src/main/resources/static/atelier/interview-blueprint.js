import { request } from './api.js';
import { esc, chips } from './ui.js';

export function bindBlueprint(select,target,signal) {
  let revision = 0;
  async function refresh() {
    const id = select.value, stamp = ++revision;
    if (!id) { target.innerHTML = ''; return; }
    target.innerHTML = '<p class="muted" role="status">正在读取岗位蓝图…</p>';
    try {
      const data = await request('/interviews/blueprint?jobId='+encodeURIComponent(id),{signal});
      if (signal.aborted || stamp!==revision) return;
      target.innerHTML = `<details class="blueprint-sheet" open><summary>岗位面试蓝图</summary>
        <p>${[data.roleDirection,data.seniority,data.domain].filter(Boolean).map(esc).join(' · ')}</p>
        <div class="chips">${chips(data.mustHaveSkills)}</div>
        ${[['岗位职责',data.coreResponsibilities],['面试覆盖',data.coveragePlan],['面试场景',data.interviewScenarios],['岗位限制',data.hardConstraints],['加分技能',data.niceToHaveSkills]].filter(([,items])=>items?.length).map(([label,items])=>`<details><summary>${label}</summary><ul>${items.map(item=>`<li>${esc(item)}</li>`).join('')}</ul></details>`).join('')}
        <small>${esc(data.sourceSummary)}</small></details>`;
    } catch (cause) {
      if (!signal.aborted && stamp===revision) target.textContent = cause.message;
    }
  }
  select.addEventListener('change',refresh,{signal});
  refresh();
}
