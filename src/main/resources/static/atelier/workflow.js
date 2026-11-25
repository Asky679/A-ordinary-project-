
import { request } from './api.js';
import { $, $$, esc, options, empty, error, link, task, chips } from './ui.js';
import { learning, renderReport } from './practice.js';
import { postingPath } from './job-sky-layout.js';
import { filterDemo } from './auth.js';

const stages = ['读取简历','匹配岗位','创建练习','整理建议','生成学习路线'];

export async function workflow(root,signal) {
  root.innerHTML = `<section class="workspace workflow-desk">
    <form class="panel flow workflow-form">
      <h2>安排求职准备</h2>
      <p class="muted">用一份简历生成岗位建议、面试练习和学习路线。</p>
      <label>选择简历<select name="resumeId" required></select></label>
      <label>目标岗位<select name="jobId"><option value="">由系统推荐</option></select></label>
      <button type="submit" class="button primary" disabled>运行准备流程</button>
      <p class="workflow-status muted" role="status">正在读取服务状态…</p>
      <button class="quiet" type="button" data-jump-skills>查看技能图谱与学习路线 ↓</button>
    </form>
    <section class="panel workflow-journal" aria-label="准备流程记录">
      <ol class="workflow-stages">${stages.map(label=>`<li>${label}</li>`).join('')}</ol>
      <div id="workflow-result" aria-live="polite">${empty('准备好后，可以开始运行')}</div>
    </section>
    <section class="panel workflow-skills">
      <h2 tabindex="-1">技能图谱与学习路线</h2>
      <form class="fields">
        <label>目标方向<select name="role"><option>Java 后端开发</option><option>前端开发</option><option>AI 大模型应用开发</option><option>数据分析</option></select></label>
        <label>对照简历<select name="resumeId"><option value="">查看通用图谱</option></select></label>
        <button type="submit" class="button">查看技能图谱</button><button type="button" class="quiet" data-learn>生成学习路线</button>
      </form>
      <div class="skill-graph-output" aria-live="polite"></div>
    </section>
  </section>`;
  const form = $('form',root), button = $('button',form), output = $('#workflow-result',root);
  const [resumes,jobs,status] = await Promise.allSettled([
    request('/resumes',{signal}),request('/jobs?limit=200',{signal}),request('/agent/status',{signal})
  ]);
  if (signal.aborted) return;
  const items = resumes.status==='fulfilled' ? filterDemo(resumes.value) : [];
  form.elements.resumeId.innerHTML = options(items,'',item=>item.ownerName+' · '+item.targetRole);
  if (jobs.status==='fulfilled') form.elements.jobId.insertAdjacentHTML('beforeend',options(jobs.value,'',item=>item.title+' · '+item.company));
  $('.workflow-status',form).textContent = status.status==='fulfilled' ?
    (status.value.mode==='LANGCHAIN4J_ONLINE' ? '在线模型' : '本地推理')+' · '+(status.value.mcpEnabled?'已配置 MCP 岗位源':'使用项目岗位源')
    : '服务状态暂时不可读取';
  const skills = $('.workflow-skills',root);
  bindSkills(skills,items,signal);
  $('[data-jump-skills]',form).onclick = ()=>{
    $('h2',skills).focus({preventScroll:true});
    skills.scrollIntoView({block:'start'});
  };
  if (!items.length) {
    output.innerHTML = resumes.status==='rejected' ? error(resumes.reason) : empty('先保存一份简历')+link('studio?work=1','打开简历编辑台 →');
    return;
  }
  button.disabled = false;
  form.addEventListener('submit',task(button,async()=>{
    const payload = {resumeId:Number(form.elements.resumeId.value),jobId:Number(form.elements.jobId.value)||null,intent:'full-workflow'};
    const journal = $('.workflow-journal',root);
    journal.dataset.phase = 'running';
    output.innerHTML = '<p class="workflow-pending" role="status">正在运行准备流程，完成后会显示实际执行记录。</p>';
    try {
      const result = await request('/agent/run',{method:'POST',data:payload,signal});
      if (signal.aborted) return;
      journal.dataset.phase = 'ready';
      $('.workflow-stages',root).innerHTML = (result.steps || []).map(step=>`<li><strong>${esc(step.title)}</strong><small>${esc(step.durationMs)} ms</small><p>${esc(step.detail)}</p></li>`).join('');
      output.innerHTML = `<div class="workflow-summary"><h2>准备建议</h2><p>${esc(result.summary)}</p>
        ${result.interview ? link('interview?work=1&session='+encodeURIComponent(result.interview.id),'打开已创建的面试 →','button primary'):''}
        ${result.recommendations?.length ? `<h3>可探索的岗位</h3><div class="stack">${result.recommendations.map(({job})=>link(postingPath(job.id),job.title+' · '+job.company,'row')).join('')}</div>`:''}
      </div>
      ${result.diagnosis ? '<details class="disclosure"><summary>查看本次简历分析</summary><div id="workflow-diagnosis"></div></details>' : learning(result.learningPath)}`;
      if (result.diagnosis) renderReport($('#workflow-diagnosis',output),result.diagnosis);
    } catch (cause) {
      if (signal.aborted) return;
      journal.dataset.phase = 'error';
      output.innerHTML = error(cause);
    }
  }));
}

function bindSkills(panel,resumes,signal) {
  const form = $('form',panel), target = $('.skill-graph-output',panel);
  const resume = form.elements.resumeId;
  resume.insertAdjacentHTML('beforeend',options(resumes,'',item=>item.ownerName+' · '+item.targetRole));
  let revision = 0;
  form.onsubmit = task($('[type="submit"]',form),async()=>{
    const stamp = ++revision;
    const query = new URLSearchParams({role:form.elements.role.value});
    if (resume.value) query.set('resumeId',resume.value);
    target.textContent = '正在读取技能关系…';
    try {
      const graph = await request('/skills/graph?'+query,{signal});
      if (signal.aborted || stamp!==revision) return;
      const nodes = graph.nodes || [];
      const categories = [...new Set(nodes.map(item=>item.category))];
      target.innerHTML = `<div class="graph-map">${categories.map(category=>`<section><h3>${esc(category)}</h3>
        ${nodes.filter(node=>node.category===category).map(node=>`<button class="quiet" type="button" data-node="${esc(node.id)}">${esc(node.label)}${node.owned?' · 已在简历':''}</button>`).join('')}
      </section>`).join('')}</div><div class="graph-node"></div>`;
      const details = $('.graph-node',target);
      for (const button of $$('[data-node]',target)) button.onclick = ()=>{
        const item = nodes.find(node=>String(node.id)===button.dataset.node);
        const relations = graph.links || [];
        const connected = ids=>ids.map(id=>nodes.find(node=>node.id===id)?.label).filter(Boolean);
        for (const candidate of $$('[data-node]',target)) candidate.setAttribute('aria-pressed',String(candidate===button));
        details.innerHTML = `<h3>${esc(item.label)}</h3><p>${esc(item.level)} · 岗位需求 ${esc(item.demand)}</p>
          <p>前置技能</p><div class="chips">${chips(connected(relations.filter(link=>link.target===item.id).map(link=>link.source))) || '图谱暂无记录'}</div>
          <p>后续技能</p><div class="chips">${chips(connected(relations.filter(link=>link.source===item.id).map(link=>link.target))) || '图谱暂无记录'}</div>`;
      };
    } catch (cause) {
      if (!signal.aborted && stamp===revision) target.innerHTML = error(cause);
    }
  });
  const button = $('[data-learn]',form);
  button.disabled = !resumes.length;
  button.onclick = task(button,async()=>{
    if (!resume.value) { resume.focus(); throw new Error('选择一份简历后可以生成个性化路线。'); }
    const stamp = ++revision;
    target.textContent = '正在整理学习阶段…';
    try {
      const result = await request('/learning-paths',{method:'POST',signal,data:{resumeId:Number(resume.value),targetRole:form.elements.role.value}});
      if (!signal.aborted && stamp===revision) target.innerHTML = learning(result);
    } catch (cause) {
      if (!signal.aborted && stamp===revision) target.innerHTML = error(cause);
    }
  });
}
