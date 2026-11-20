import { request, params } from './api.js';
import { $, $$, esc, chips, options, link, empty, error, showDialog, external, task } from './ui.js';
import { getJobSector } from './job-sectors.js';
import { postingPath, starPath, starTexture } from './job-sky-layout.js';
import { careerMap } from './career-map.js';

function sectorHeader(id, sector, selected){
  const path = 'jobs?work=1&sector='+encodeURIComponent(id);
  return `<section class="sector-header" data-sector="${esc(id)}" aria-labelledby="sector-title" style="--sector-light:${sector.color}">
    <div class="sector-orbit" aria-hidden="true"><span class="planet-surface" style="--planet-image:url('/atelier/visuals/${sector.planet}')"></span></div>
    <div class="sector-brief">
      <nav class="sector-nav" aria-label="岗位导航">
        ${link('jobs?sector='+encodeURIComponent(id),'← 返回星图','sector-back')}
        ${link('jobs?work=1','全部岗位 ↗','sector-back')}
      </nav>
      <h1 id="sector-title">${selected.name}</h1>
      <nav class="sector-roles" aria-label="该方向的岗位类型">
        ${Object.entries(sector.roles).map(([key,role])=>`<a href="#${path}&role=${key}"${role===selected?' aria-current="page"':''}>${role.name}</a>`).join('')}
      </nav>
      <details class="sector-role-detail">
        <summary>${sector.name} · 职责与常见技能</summary>
        <p>${selected.duties}</p>
        <div class="chips" aria-label="常见技能">${chips(selected.skills)}</div>
      </details>
    </div>
  </section>`;
}

export function jobs(root, signal, query){
  if (query.has('posting')) return jobPosting(root,signal,query);
  const sectorId = query.get('sector');
  const sector = getJobSector(sectorId);
  const roleId = query.get('role');
  const role = sector && (Object.hasOwn(sector.roles,roleId) ? sector.roles[roleId] : Object.values(sector.roles)[0]);
  root.classList.toggle('sector-work',Boolean(sector));
  root.innerHTML = `
  ${sector ? sectorHeader(sectorId,sector,role) : ''}
  ${sector ? '' : '<section id="career-map"></section>'}
  <div class="workspace">
    <form class="panel search" id="job-search" data-mode="match" aria-label="筛选招聘岗位">
      <div class="search-mode-switch" role="group" aria-label="选择岗位筛选方式">
        <button type="button" class="search-mode-option" data-search-mode="match"><span>按简历智能匹配</span><small>根据技能、方向、经验和城市推荐岗位</small></button>
        <button type="button" class="search-mode-option" data-search-mode="browse"><span>自由搜索岗位</span><small>按关键词、城市或职业方向查找</small></button>
      </div>
      <p class="search-mode-help" id="search-mode-help"><strong>先选择一份简历</strong>，系统会计算岗位适配度并优先显示更匹配的机会。</p>
      <label class="browse-only">搜索岗位<input type="search" name="keyword" placeholder="岗位、公司或技能"></label>
      <label>城市<select name="city"><option value="">所有城市</option></select></label>
      <label class="match-only search-resume-strong">选择用于匹配的简历<select name="resumeId"><option value="">选择简历</option></select></label>
      <div class="job-search-actions"><button class="button primary" id="job-search-submit" type="submit">开始智能匹配</button><button class="quiet" type="button" id="refresh-jobs">同步岗位源</button></div>
      <details class="disclosure search-more"><summary>更多筛选</summary><div class="fields">
        <label>学历<select name="education"><option value="">不限</option><option>大专</option><option>本科</option><option>硕士</option><option>博士</option></select></label>
        <label>经验<select name="experience"><option value="">不限</option><option>经验不限</option><option>1-3年</option><option>3-5年</option><option>5-10年</option></select></label>
        <label>最低月薪（K）<input name="salaryMin" type="number" min="0" max="999"></label>
        <label>岗位分类<input name="category"></label>
        <label>行业<input name="industry"></label>
        <label>城市结果排序<select name="sort"><option value="latest">最新发布</option><option value="match">匹配优先</option></select></label>
        <label>检索方式<select name="mode"><option value="">关键词</option><option value="hybrid">混合检索</option></select></label>
        <label>数据年份（混合检索）<input name="year" type="number" min="2000" max="2100"></label>
        <label>排除城市（简历匹配）<input name="excludedCities" placeholder="使用逗号分隔"></label>
      </div></details>
    </form>
    <div class="job-tools"><button type="button" class="quiet match-only" id="job-agent">Agent 深度推荐</button>
      <details class="job-weights"><summary>查看岗位匹配权重</summary><div></div></details></div>
    <section id="job-agent-result" aria-live="polite" hidden></section>
    <p id="job-search-status" class="muted" role="status" hidden></p>
    <div id="job-results" aria-live="polite"></div>
  </div>`;
  const form=$('#job-search',root), output=$('#job-results',root);
  const searchButton=$('button[type="submit"]',form);
  const refresh=$('#refresh-jobs',form);
  const modeHelp=$('#search-mode-help',root);
  let searchMode = query.get('resumeId') ? 'match' : (query.get('q') || query.get('careerRole') ? 'browse' : 'match');
  let careerRole = query.get('careerRole') || '';
  let careerRoleName = '';
  function setSearchMode(mode, execute=false){
    searchMode = mode === 'browse' ? 'browse' : 'match';
    form.dataset.mode = searchMode;
    $$('[data-search-mode]',form).forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.searchMode===searchMode)));
    form.elements.keyword.disabled = searchMode !== 'browse';
    form.elements.resumeId.disabled = searchMode !== 'match';
    searchButton.textContent = searchMode === 'match' ? '开始智能匹配' : '搜索岗位';
    const agentAction = $('#job-agent',root);
    if(agentAction) agentAction.hidden = searchMode !== 'match';
    modeHelp.innerHTML = searchMode === 'match'
      ? '<strong>先选择一份简历</strong>，系统会计算岗位适配度并优先显示更匹配的机会。'
      : '<strong>按岗位条件自由查找</strong>，不会叠加简历匹配排序。';
    if(searchMode === 'match') form.elements.keyword.value = '';
    else form.elements.resumeId.value = '';
    if(execute) run(0,true);
  }
  $$('[data-search-mode]',form).forEach(button=>button.addEventListener('click',()=>{ careerRole=''; careerRoleName=''; setSearchMode(button.dataset.searchMode,true); },{signal}));
  const filterNames=['city','resumeId','education','experience','salaryMin','excludedCities','category','industry','sort','mode','year'];
  let current;
  let resultList=[];
  let lastQuery;
  form.elements.keyword.value = query.get('q') ?? (careerRole ? '' : role?.keyword ?? '');
  searchButton.disabled = refresh.disabled = true;
  output.innerHTML = empty('正在查找岗位');
  signal.addEventListener('abort',()=>current?.abort(),{once:true});

  async function run(page=0, keepQuery=false){
    current?.abort();
    const controller = new AbortController();
    current = controller;
    let { keyword, city, resumeId, mode, year, ...filters } = Object.fromEntries(new FormData(form));
    keyword = keyword || ''; city = city || ''; resumeId = resumeId || '';
    lastQuery={keyword,city,resumeId,mode,year,...filters};
    if (keepQuery) {
      try { localStorage.setItem('careernav-excluded-cities',JSON.stringify(filters.excludedCities.split(/[,，]/).map(city=>city.trim()).filter(Boolean))); } catch {}
    }
    if (keepQuery) {
      const active = new URLSearchParams(location.hash.split('?')[1]);
      active.set('work','1');
      if (careerRole) {
        active.set('careerRole',careerRole);
        active.delete('q');
      } else {
        active.delete('careerRole');
        active.set('q',keyword);
      }
      for (const name of filterNames) {
        if (lastQuery[name]) active.set(name,lastQuery[name]);
        else active.delete(name);
      }
      if (page) active.set('page',String(page));
      else active.delete('page');
      history.replaceState(history.state,'','#jobs?'+active);
    }
    const status = $('#job-search-status',root);
    status.hidden = true;
    output.innerHTML=empty('正在查找岗位');
    try {
      let matches;
      let total;
      if(careerRole) {
        const data=await request('/career-map/roles/'+encodeURIComponent(careerRole)+'/jobs?'+params({city,page,size:24}),{signal:controller.signal});
        matches=(data.items || []).map(job=>({job}));
        total=data.total;
        careerRoleName=data.roleName || careerRoleName;
      } else if(city && resumeId && mode!=='hybrid') {
        const data=await request('/jobs/city?'+params({keyword,city,resumeId,...filters,page,size:24}),{signal:controller.signal});
        matches=data.items || [];
        total=data.total;
      } else if(resumeId && mode!=='hybrid') {
        matches=await request('/jobs/recommend?'+params({resumeId,limit:100,...filters}),{signal:controller.signal});
        const query=keyword.trim().toLowerCase();
        if(query) matches=matches.filter(({job})=>[job.title,job.company,job.description,...(job.skills||[])].some(value=>String(value||'').toLowerCase().includes(query)));
      } else {
        const keywordSearch = () => request('/jobs?'+params({keyword,city,limit:100}),{signal:controller.signal});
        let rows;
        if (mode==='hybrid') {
          try {
            rows=await request('/jobs/hybrid?'+params({query:keyword,city,year,limit:100}),{signal:controller.signal});
          } catch (cause) {
            if (controller.signal.aborted) throw cause;
            status.textContent = year ? '混合检索暂时不可用，无法按该年份筛选。' : '混合检索暂时不可用，已显示关键词结果。';
            status.hidden = false;
            if (year) { output.innerHTML = empty('暂时无法按年份检索','清除年份后可以继续搜索。'); return; }
            rows=await keywordSearch();
          }
        } else rows=await keywordSearch();
        const excluded = filters.excludedCities.split(/[,，]/).map(value=>value.trim()).filter(Boolean);
        matches=rows.filter(job=>{
          const salary=String(job.salary||'').match(/(\d+(?:\.\d+)?)\s*k/i);
          return (!resumeId || !excluded.includes(job.city))
            && (!filters.category || String(job.category||'').includes(filters.category))
            && (!filters.industry || String(job.industry||'').includes(filters.industry))
            && (!filters.education || String(job.education||'').includes(filters.education))
            && (!filters.experience || String(job.experience||'').includes(filters.experience))
            && (!Number(filters.salaryMin) || (salary && Number(salary[1]) >= Number(filters.salaryMin)));
        }).map(job=>({job}));
      }
      if(controller.signal.aborted) return;
      resultList=matches;
      render(total,page);
    } catch(cause){ if(cause.name!=='AbortError') output.innerHTML=error(cause); }
  }

  function render(total,page){
    const matched = resultList.some(item=>item.matchScore != null);
    output.innerHTML = `<div class="split results-title"><h2>${careerRole ? esc(careerRoleName || '职业方向')+'岗位' : (matched?'简历匹配':'岗位列表')}</h2>
      <span class="muted">${total==null?`当前 ${resultList.length} 个岗位`:`共 ${esc(total)} 个岗位`}</span>
      ${matched?`<button class="quiet" type="button" id="rerank">Agent 细化分析</button>`:''}</div>`+
      (resultList.length ? `<div class="job-grid">${resultList.map((item,i)=>card(item,i)).join('')}</div>` : empty('没有找到符合条件的岗位','试试其他关键词或清除部分筛选条件。')) +
      (total>24 ? `<nav class="pager" aria-label="岗位翻页"><button class="quiet" type="button" data-page="${page-1}" ${page===0?'disabled':''}>上一页</button><span>第 ${page+1} 页</span><button class="quiet" type="button" data-page="${page+1}" ${(page+1)*24>=total?'disabled':''}>下一页</button></nav>`:'');
    $$('[data-job]',output).forEach(button=>button.addEventListener('click',task(button,()=>jobDetail(resultList[Number(button.dataset.job)],signal))));
    $$('[data-page]',output).forEach(button=>button.addEventListener('click',()=>run(Number(button.dataset.page),true)));
    const rerank=$('#rerank',output);
    rerank?.addEventListener('click',task(rerank,async()=>{
      const list=resultList;
      const response=await request('/jobs/recommend/agent',{method:'POST',data:{resumeId:Number(lastQuery.resumeId),jobIds:list.slice(0,12).map(item=>item.job.id)}});
      if (resultList!==list || !output.isConnected) return;
      if(!response.available) throw new Error('Agent 当前不可用，仍可查看已有匹配分析。');
      const byId=new Map(response.items.map(item=>[item.jobId,item]));
      resultList=list.map(item=>({...item,agent:byId.get(item.job.id)}));
      render(total,page);
    }));
  }
  const agentButton = $('#job-agent',root), agentResult = $('#job-agent-result',root);
  agentButton.addEventListener('click',task(agentButton,async()=>{
    const {resumeId,city,excludedCities} = Object.fromEntries(new FormData(form));
    if (!resumeId) { form.elements.resumeId.focus(); throw new Error('先选择用于匹配的简历。'); }
    agentResult.hidden = false;
    agentResult.innerHTML = '<p class="panel" role="status">正在检索岗位并核对技能要求…</p>';
    try {
      const report = await request('/diagnoses/agent?'+params({resumeId,city,excludedCities,limit:8}),{method:'POST',data:{},signal});
      if (signal.aborted) return;
      agentResult.innerHTML = `<section class="panel result-reveal"><h2>Agent 岗位推荐</h2><p>${esc(report.summary)}</p>
        ${report.queries?.length ? `<div class="chips">${chips(report.queries)}</div>`:''}
        ${(report.recommendations || []).map(item=>`<article class="note"><h3>${esc(item.title)}</h3>
          <p>${[item.company,item.city,item.salary,item.contentOrigin==='AI_GENERATED'?'岗位描述含 AI 补全':''].filter(Boolean).map(esc).join(' · ')}</p>
          <div class="chips">${chips(item.matchedSkills)}</div>
          ${item.missingSkills?.length ? `<p>可补充 · ${item.missingSkills.map(esc).join('、')}</p>`:''}
          ${item.jobId ? link(postingPath(item.jobId),'打开岗位 →','quiet'):''}
          ${item.scoreBreakdown?.length ? `<details><summary>匹配依据</summary><ul>${item.scoreBreakdown.map(part=>`<li>${esc(part.label)} · ${esc(part.detail || part.contribution)}</li>`).join('')}</ul></details>`:''}
        </article>`).join('') || empty('本次未召回岗位')}
        ${report.skillGaps?.length ? `<p>常见技能差距 · ${report.skillGaps.map(item=>esc(item.skill)).join('、')}</p>`:''}
        ${report.trace?.length ? `<details><summary>查看检索记录</summary><ol>${report.trace.map(item=>`<li>${esc(item)}</li>`).join('')}</ol></details>`:''}
        </section>`;
    } catch (cause) {
      if (!signal.aborted) agentResult.innerHTML = `<div class="panel">${error(cause)}<p>可以继续使用下方岗位列表与简历规则匹配。</p></div>`;
    }
  }));
  bindWeights($('.job-weights',root),'/jobs/weights/ahp',signal);
  const careerRoot = $('#career-map',root);
  if (careerRoot) careerMap(careerRoot,signal,role=>{
    setSearchMode('browse');
    careerRole = role.id;
    careerRoleName = role.name;
    form.elements.keyword.value = '';
    form.elements.category.value = '';
    run(0,true);
    form.scrollIntoView({block:'start',behavior:'smooth'});
  });
  form.addEventListener('submit',event=>{
    event.preventDefault();
    if (careerRole && form.elements.keyword.value.trim()) { careerRole=''; careerRoleName=''; }
    if (!searchButton.disabled) run(0,true);
  });
  refresh.addEventListener('click',task(refresh,async()=>{
    await request('/jobs/refresh?'+params({keyword:form.elements.keyword.value,city:form.elements.city.value}),{method:'POST'});
    if(!signal.aborted) await run(0,true);
  }));
  Promise.allSettled([request('/jobs/cities',{signal}),request('/resumes',{signal})]).then(([cities,resumes])=>{
    if(signal.aborted)return;
    if(cities.status==='fulfilled') form.elements.city.insertAdjacentHTML('beforeend',cities.value.map(item=>`<option value="${esc(item.city)}">${esc(item.city)} · ${esc(item.count)}</option>`).join(''));
    if(resumes.status==='fulfilled') form.elements.resumeId.insertAdjacentHTML('beforeend',options(resumes.value,'',item=>item.ownerName+' · '+item.targetRole));
    setSearchMode(searchMode);
    if(searchMode==='match' && !form.elements.resumeId.value && resumes.status==='fulfilled' && resumes.value.length) form.elements.resumeId.value = String(resumes.value[0].id);
    for (const name of filterNames) {
      let value = query.get(name);
      if (name==='excludedCities' && value===null) {
        try { value = JSON.parse(localStorage.getItem('careernav-excluded-cities') || '[]').join('，'); } catch {}
      }
      if (value) form.elements[name].value = value;
      if (form.elements[name].selectedIndex < 0) form.elements[name].selectedIndex = 0;
    }
    const page = Number(query.get('page'));
    searchButton.disabled = refresh.disabled = false;
    run(Number.isSafeInteger(page) && page > 0 ? page : 0);
  });
}

function card(item,i){
  const {job,agent}=item;
  const score=agent?.finalScore ?? item.matchScore;
  return `<article class="job panel">
    <div class="split"><span class="chip">${esc(job.city || '地点待确认')}</span><strong>${esc(job.salary)}</strong></div>
    <h3>${esc(job.title)}</h3><p class="muted">${esc(job.company)} · ${esc(job.experience || '经验未注明')}${isSample(job)?' · 示例岗位':''}</p>
    <div class="chips">${chips(job.skills?.slice(0,5))}</div>
    <div class="split"><small class="job-match">${item.missingSkills?.length ? '可查看技能差距' : score!=null ? '可查看匹配依据' : ''}</small><button class="quiet" type="button" data-job="${i}">查看详情 ↗</button></div>
  </article>`;
}

export function isSample(job){
  return job.externalId?.startsWith('seed-') || job.source?.includes('示例');
}

function bindWeights(detail, path, signal) {
  const output = $('div',detail);
  let loaded = false;
  detail.addEventListener('toggle',async()=>{
    if (!detail.open || loaded) return;
    loaded = true;
    output.textContent = '正在读取匹配权重…';
    try {
      const result = await request(path,{signal});
      if (signal.aborted) return;
      output.innerHTML = `<p class="muted">${esc(result.note)} · 一致性比率 ${(Number(result.consistencyRatio || 0)*100).toFixed(1)}%</p>
        <dl class="weights-sheet">${(result.criteria || []).map(item=>`<div><dt>${esc(item.label)}</dt><dd><meter min="0" max="1" value="${Number(item.weight)||0}"></meter> ${(Number(item.weight)*100).toFixed(1)}%</dd></div>`).join('')}</dl>
        ${result.adjustmentReasons?.length ? `<ul>${result.adjustmentReasons.map(reason=>`<li>${esc(reason)}</li>`).join('')}</ul>`:''}`;
    } catch (cause) {
      if (!signal.aborted) { output.textContent = cause.message; loaded = false; }
    }
  });
}

function jobDetails(job) {
  return `<p class="muted job-detail-meta">${[job.company,job.city,job.salary,job.education,job.experience,isSample(job)&&'示例岗位'].filter(Boolean).map(esc).join(' · ')}</p>
    <div class="chips">${chips(job.skills)}</div>
    <h3>岗位职责</h3><p class="pre-line">${esc(job.jobDescription || job.description || (job.responsibilities||[]).join('\n') || '暂无描述')}</p>
    <h3>任职要求</h3><p class="pre-line">${esc(job.jobRequirements || '暂无详情')}</p>
    <details class="disclosure job-weights"><summary>这条岗位的匹配权重</summary><div></div></details>`;
}

function jobActions(job) {
  return `<div class="actions">${link('interview?work=1&job='+encodeURIComponent(job.id),'练习这个岗位的面试 ↗','button primary')}${isSample(job)?'':external(job.sourceUrl,'查看原岗位')}</div>`;
}

async function jobPosting(root, signal, query) {
  const id = query.get('posting'), star = query.get('star');
  root.innerHTML = `<section class="job-posting" data-posting="${esc(id)}" aria-labelledby="job-posting-title">
    <header class="job-posting-header">
      <div class="job-posting-heading">
        <nav aria-label="岗位导航">
          ${link(starPath(id,star),'← 返回星图','sector-back')}
          ${link('jobs?work=1','全部岗位 ↗','sector-back')}
        </nav>
        <h1 id="job-posting-title">岗位详情</h1>
      </div>
      <img class="job-posting-orb planet-surface" src="${starTexture(star)}" alt="">
    </header>
    <div class="workspace job-posting-content" aria-live="polite">${empty('正在读取岗位详情')}</div>
  </section>`;
  const target = $('.job-posting-content',root);
  try {
    const job = await request('/jobs/'+encodeURIComponent(id),{signal});
    if (signal.aborted) return;
    $('#job-posting-title',root).textContent = job.title;
    document.title = job.title+' · 启程';
    target.innerHTML = `<article class="panel">${jobDetails(job)}${jobActions(job)}</article><section class="job-posting-similar"></section>`;
    bindWeights($('.job-weights',target),'/jobs/'+encodeURIComponent(id)+'/weights/ahp',signal);
    request('/jobs/'+encodeURIComponent(id)+'/similar?limit=4',{signal}).then(items=>{
      if (signal.aborted || !items.length) return;
      $('.job-posting-similar',target).innerHTML = `<h2>相似岗位</h2><div class="stack">${items.map(item=>
        `<a class="row" href="#${postingPath(item.id)}"><strong>${esc(item.title)}</strong><small>${[item.company,item.city].filter(Boolean).map(esc).join(' · ')}</small></a>`
      ).join('')}</div>`;
    }).catch(()=>{});
  } catch (cause) {
    if (!signal.aborted) target.innerHTML = error(cause);
  }
}

async function jobDetail(item, signal){
  const job=await request('/jobs/'+item.job.id,{signal});
  if(signal.aborted) return;
  const score = item.agent?.finalScore ?? item.matchScore;
  const dialog=showDialog(job.title,`${jobDetails(job)}
    ${item.reasons?.length?`<h3>匹配依据</h3><ul>${item.reasons.map(reason=>`<li>${esc(reason)}</li>`).join('')}</ul>`:''}
    ${item.missingSkills?.length?`<h3>可补充的技能</h3><div class="chips">${chips(item.missingSkills)}</div>`:''}
    ${score==null?'':`<details class="disclosure job-detail-match"><summary>查看匹配评分</summary><p>系统分析 ${esc(score)} 分，仅用于对照简历与这条岗位要求。</p></details>`}
    ${item.agent?.breakdown?.length?`<details class="disclosure"><summary>Agent 分析细项</summary>${item.agent.breakdown.map(row=>`<p>${esc(row.requirement)} · ${esc(row.status)}<br>${esc(row.evidence)}</p>`).join('')}</details>`:''}
    ${jobActions(job)}
    <div id="similar-jobs"></div>`);
  bindWeights($('.job-weights',dialog),'/jobs/'+encodeURIComponent(job.id)+'/weights/ahp',signal);
  $('a[href^="#"]',dialog)?.addEventListener('click',()=>$('#dialog').close());
  request('/jobs/'+job.id+'/similar?limit=4',{signal}).then(items=>{
    const target=$('#similar-jobs',dialog);
    if(signal.aborted || !target || !items.length) return;
    target.innerHTML=`<h3>相似岗位</h3><div class="stack">${items.map((entry,i)=>`<button type="button" class="row" data-similar="${i}"><strong>${esc(entry.title)}</strong><small>${esc(entry.company)} · ${esc(entry.city)}</small></button>`).join('')}</div>`;
    $$('[data-similar]',target).forEach(button=>button.addEventListener('click',task(button,()=>jobDetail({job:items[Number(button.dataset.similar)]},signal))));
  }).catch(()=>{});
}
