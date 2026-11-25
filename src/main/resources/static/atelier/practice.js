import { bindBlueprint } from './interview-blueprint.js';
import { request } from './api.js';
import { filterDemo, dataKindLabel } from './auth.js';
import { $, $$, esc, date, options, empty, link, task, notify } from './ui.js';

const answerDrafts = new Map();
const advisoryFeedback = value => String(value || '')
  .replace(/(?:本轮)?(?:得分|评分)\s*[：:]?\s*\d+(?:\.\d+)?\s*分?[。；;，,\s]*/gi, '')
  .trim();


function scoreVersionLabel(item) {
  if (!item || item.status !== 'COMPLETED') return '';
  if (!item.scoreComparable) return `旧评分规则 · ${item.scoreBand || '不可跨版本比较'}`;
  if (item.legacyOverallScore != null) {
    return `新规则重算 · 原 ${item.legacyOverallScore} → 现 ${item.overallScore} 分`;
  }
  return `当前评分规则 · ${item.overallScore} 分`;
}


const guideRoleOptions = [
  ['主导并独立实现核心模块','我负责主要模块和关键实现',true],
  ['参与部分模块','我实现其中一部分，其他人也参与'],
  ['联调、测试或文档为主','我主要负责配合与交付验证'],
  ['课程或个人项目','主要由我独立完成']
];
const guideBoundaryOptions = ['前端页面','支付/退款','部署运维','测试验收','数据库设计','需求沟通'];
const guideFocusOptions = [
  ['职责与业务链路','职责与业务链路',true],
  ['接口与数据实现','接口与数据实现',true],
  ['故障与方案取舍','故障与方案取舍',true],
  ['结果验证与证据','结果验证与证据',false]
];
const guideProofOptions = ['项目文档','接口截图','测试记录','部署记录','暂时没有'];
const interviewModeOptions = [
  ['综合面','综合面','项目、动机、协作和反思','校招/实习最常用，覆盖项目、动机、团队协作和学习反思。'],
  ['技术面','技术面','项目深挖、故障和技术取舍','更适合技术面试，但仍会保留背景和候选人反问。'],
  ['HR面','HR/招聘面','动机、稳定性、规划和沟通','重点验证求职动机、城市/到岗意愿、沟通和职业规划。'],
  ['主管面','主管面','业务判断、协作和成长','更关注项目负责、业务判断、团队协作和成长潜力。'],
  ['行为面','行为面','冲突、失败、抗压和推动改变','用真实经历验证沟通、冲突处理、失败复盘和抗压能力。']
];

function guideChips(name, items, type='checkbox') {
  return items.map((item,index)=>{
    const [value,label,checked] = Array.isArray(item) ? [item[0],item[1],item[2]] : [item,item,false];
    return `<label class="guide-chip"><input type="${type}" name="${name}" value="${value}" ${checked?'checked':''}><span>${label}</span></label>`;
  }).join('');
}

function projectClaim(project) {
  const description = String(project?.description || '').trim();
  if (!description) return '';
  return description.split(/[。；;\n]/).map(item=>item.trim()).filter(Boolean).slice(0,2).join('。') + '。';
}

function inferInterviewLevel(job) {
  const title = String(job?.title || '').toLowerCase();
  const experience = String(job?.experience || '').toLowerCase();
  if (/架构师|architect|技术专家|principal/.test(title)) return '架构师';
  if (/技术经理|研发经理|技术负责人|总监|cto/.test(title)) return '架构师';
  if (/高级|资深|senior|lead/.test(title)) return '高级';
  if (/初级|实习|应届|校招|junior|intern/.test(title)) return '初级';
  if (/5-10年|5年以上|10年以上/.test(experience)) return '高级';
  if (/3-5年/.test(experience)) return '中级';
  if (/1-3年|1年以内/.test(experience)) return '初级';
  return '中级';
}

export async function interview(root, signal, query) {
  root.innerHTML = `
    <div class="workspace columns interview-layout">
      <div class="panel" id="interview-settings">${empty('正在读取面试资料')}</div>
      <section class="panel transcript" id="interview-chat">${empty('面试尚未开始','选一份简历和一个目标岗位，开启练习。')}</section>
    </div>`;
  const difficulty = query.get('room') === 'meeting' ? '压力' : '标准';
  const settings = $('#interview-settings',root), chat = $('#interview-chat',root);
  if (!query.has('session')) {
    chat.classList.add('cafe-table');
    chat.insertAdjacentHTML('afterbegin',`<img src="/atelier/visuals/cafe-${difficulty==='压力'?'meeting':'window'}-small.webp" alt="" class="cafe-table-art">`);
  }
  const responses = await Promise.allSettled([
    request('/resumes',{signal}), request('/jobs?limit=200',{signal}), request('/interviews',{signal})
  ]);
  if (signal.aborted) return;
  const [resumeRows,jobs,sessionRows]=responses.map(result=>result.status==='fulfilled'?result.value:[]);
  const resumes=filterDemo(resumeRows);
  const sessions=filterDemo(sessionRows);
  const unavailable=responses.some(result=>result.status==='rejected');
  const selectedJob = query.get('job');
  if (selectedJob && !jobs.some(job => String(job.id) === selectedJob)) {
    const selected = await request('/jobs/'+encodeURIComponent(selectedJob),{signal}).catch(()=>null);
    if(selected) jobs.unshift(selected);
  }
  if (signal.aborted) return;
  settings.innerHTML = `<details class="interview-setup" open>
    <summary>安排本场练习</summary>
    <form class="flow" id="interview-start">
      <label>练习用的简历<select name="resumeId" required><option value="">选择简历</option>${options(resumes, '', item => item.ownerName+' · '+item.targetRole)}</select></label>
      <label>目标岗位<select name="jobId" required><option value="">选择岗位</option>${options(jobs, selectedJob, item => item.title+' · '+item.company)}</select></label>
      <label>按什么岗位级别评估<select name="targetLevel"><option>初级</option><option selected>中级</option><option>高级</option><option>架构师</option></select><small>系统会先按岗位推断，你可以手动调整；问题深度会随级别变化。</small></label>
      <fieldset class="interview-mode-fieldset">
        <legend>选择面试类型</legend>
        <div class="interview-mode-grid">${interviewModeOptions.map(([value,label,compact,help],index)=>`
          <label class="interview-mode-card"><input type="radio" name="interviewMode" value="${value}" ${index===0?'checked':''}><span><strong>${label}</strong><small>${compact}</small></span></label>`).join('')}
        </div>
        <small id="interview-mode-help">${interviewModeOptions[0][3]}</small>
      </fieldset>
      <p id="chosen-room" class="chosen-room">${difficulty==='压力'?'里间会议桌 · 压力练习':'窗边圆桌 · 标准练习'}</p>
      ${unavailable?'<p class="muted">部分资料暂时无法加载，可以刷新重试。</p>':''}
      <details class="evidence-guide" id="guided-evidence" open>
        <summary>面试前 60 秒：确认项目线索 <small>不需要填写九模块</small></summary>
        <p class="muted">先点选你已经确认的事实。接口、数据、故障和取舍会在面试中继续追问。</p>
        <label>从简历选择项目<select name="projectIndex"></select></label>
        <p class="guide-project-hint" id="guide-project-hint"></p>
        <fieldset><legend>1. 你在项目中的主要参与方式</legend><div class="guide-chips">${guideChips('guideRole',guideRoleOptions,'radio')}</div></fieldset>
        <fieldset><legend>2. 哪些明确不是你负责的</legend><div class="guide-chips" id="guide-boundary-options"><span class="muted">正在分析项目工作方面…</span></div><small id="guide-clue-summary" class="guide-clue-summary"></small></fieldset>
        <fieldset><legend>3. 这场面试优先验证什么</legend><div class="guide-chips">${guideChips('guideFocus',guideFocusOptions)}</div></fieldset>
        <fieldset><legend>4. 你能提供哪些证据（可选）</legend><div class="guide-chips">${guideChips('guideProof',guideProofOptions)}</div></fieldset>
        <details class="guide-claim"><summary>可选：补充一段最想讲清的项目事实</summary><textarea name="guideClaim" rows="3" placeholder="系统会从简历自动带出建议，你可以修改。"></textarea></details>
      </details>
      <button class="button primary" type="submit">开始引导式面试</button>
      <div id="interview-blueprint"></div>
      ${resumes.length ? '' : link('studio?work=1','先写一份简历 ↗','text-link')}
    </form>
    </details>
    <details class="candidate-stories" hidden>
      <summary>候选人故事库 <small class="story-bank-count"></small></summary>
      <p class="muted">这里保存你在不同面试里讲过的真实故事。下一场面试会自动带入，避免反复使用同一个例子。</p>
      <div class="story-bank-list"></div>
      <button class="quiet story-rebuild" type="button">选择历史面试整理</button>
      <div class="story-rebuild-picker" hidden>
        <p class="muted">只勾选你认可、愿意复用的面试记录；面试表现不好的场次不会混入。</p>
        <div class="story-rebuild-list"></div>
        <button class="button primary story-rebuild-confirm" type="button">整理勾选的面试</button>
      </div>
      <div class="story-danger-actions">
        <button class="quiet danger story-clear" type="button">一键删除全部故事</button>
        <button class="quiet danger clear-all-memory" type="button">一键删除故事和练习记录</button>
      </div>
    </details>
    <details class="interview-history" open hidden>
      <summary>练习记录</summary><div class="stack"></div>
      <button class="quiet danger history-clear" type="button">一键删除全部练习记录</button>
    </details>
    <details class="trash-bin" hidden>
      <summary>回收站 <small class="trash-count"></small></summary>
      <p class="muted">删除的内容会先放在这里，可以恢复，也可以永久删除。</p>
      <section class="trash-section"><h3>已删除故事</h3><div class="trash-story-list"></div></section>
      <section class="trash-section"><h3>已删除练习记录</h3><div class="trash-session-list"></div></section>
      <button class="quiet danger empty-trash" type="button">清空回收站</button>
    </details>
    <p class="session-load-status muted" role="status" hidden></p>`;
  const form = $('#interview-start',settings);
  const setup = $('.interview-setup',settings), historyPanel = $('.interview-history',settings);
  const status = $('.session-load-status',settings);
  const storyPanel = $('.candidate-stories',settings);
  const storyList = $('.story-bank-list',storyPanel);
  const trashPanel = $('.trash-bin',settings);
  const trashStoryList = $('.trash-story-list',trashPanel);
  const trashSessionList = $('.trash-session-list',trashPanel);

  let storyResumeId = 0;
  let storyItems = [];

  function storyExcerpt(label, value) {
    return value ? `<p><strong>${label}</strong>${esc(value)}</p>` : '';
  }

  function storySources(story) {
    const ids = (story.sourceSessionIds && story.sourceSessionIds.length ? story.sourceSessionIds : [story.sourceSessionId])
      .map(Number).filter(Boolean);
    return ids.map(id=>sessions.find(item=>Number(item.id)===id) || {id});
  }

  function renderStories(stories) {
    const items = stories || [];
    storyItems = items;
    $('.story-bank-count',storyPanel).textContent = items.length ? `${items.length} 条` : '暂无';
    storyList.innerHTML = items.length ? items.map(story=>{
      const competencies = (story.competencies || []).map(item=>`<span class="chip">${esc(item)}</span>`).join('');
      const source = [dataKindLabel(story), story.projectName, story.interviewMode, story.usageCount ? `已使用 ${story.usageCount} 次` : '尚未复用'].filter(Boolean).join(' · ');
      const sources = storySources(story);
      const primarySource = sources[0] || {};
      const sourceLabel = primarySource.id ? `来源：${primarySource.jobTitle || '面试记录'} · ${primarySource.updatedAt ? date(primarySource.updatedAt) : '时间未知'} · #${primarySource.id}` : '来源：未知面试记录';
      return `<details class="story-card">
        <summary><strong>${esc(story.title || '面试故事')}</strong><small>${esc(source)}</small></summary>
        <div class="story-card-body">
          <p class="story-source-inline">${esc(sourceLabel)}</p>
          ${storyExcerpt('情境 ',story.situation)}
          ${storyExcerpt('任务 ',story.task)}
          ${storyExcerpt('行动 ',story.action)}
          ${storyExcerpt('结果 ',story.result)}
          ${storyExcerpt('反思 ',story.reflection)}
          ${story.evidence && story.evidence !== story.action ? `<blockquote>${esc(story.evidence)}</blockquote>` : ''}
          ${competencies ? `<div class="chips">${competencies}</div>` : ''}
            ${primarySource.id ? `<button class="quiet story-open" type="button" data-open-source="${esc(primarySource.id)}">打开来源记录</button>` : ''}
          <div class="story-actions">
          ${sources.length > 1 ? `<p class="story-source-list"><strong>全部来源 </strong>${sources.map(item=>`#${esc(item.id)} ${esc(item.jobTitle || '')} ${item.updatedAt ? esc(date(item.updatedAt)) : ''}`).join('；')}</p>` : ''}
          <button class="quiet story-delete" type="button" data-delete-story="${esc(story.id)}">删除这条故事</button>
          </div>
        </div>
      </details>`;
    }).join('') : empty('还没有故事','完成一场面试后会自动提取，也可以从历史面试整理。');
  }

  async function loadStories(resumeId) {
    storyResumeId = Number(resumeId || 0);
    if (!storyResumeId) {
      storyPanel.hidden = true;
      return;
    }
    storyPanel.hidden = false;
    storyList.innerHTML = '<p class="muted">正在读取跨场次故事库…</p>';
    try {
      const stories = filterDemo(await request('/stories?resumeId='+encodeURIComponent(storyResumeId),{signal}));
      if (storyResumeId === Number(resumeId)) renderStories(stories);
    } catch (cause) {
      if (!signal.aborted) storyList.innerHTML = `<p class="muted">${esc(cause.message)}</p>`;
    }
  }


  async function refreshActiveSessions() {
    const active = await request('/interviews',{signal});
    sessions.splice(0,sessions.length,...active);
    updateHistory(chat.dataset.session || '');
  }

  function resetInterviewWorkspace() {
    sessions.splice(0,sessions.length);
    chat.dataset.session = '';
    chat.classList.add('cafe-table');
    chat.innerHTML = empty('??????','????????????????');
    setup.open = true;
    $('summary',setup).textContent = '??????';
    const path = new URLSearchParams(location.hash.split('?')[1] || '');
    path.delete('session');
    path.set('work','1');
    history.replaceState(history.state,'','#interview?'+path);
    updateHistory();
  }

  function renderTrash(stories, sessionsInTrash) {
    const storyItems = stories || [];
    const sessionItems = sessionsInTrash || [];
    $('.trash-count',trashPanel).textContent = (storyItems.length + sessionItems.length) ? `${storyItems.length + sessionItems.length} ?` : '';
    trashPanel.hidden = !(storyItems.length || sessionItems.length);
    trashStoryList.innerHTML = storyItems.length ? storyItems.map(story=>`<div class="trash-item">
      <div><strong>${esc(story.title || '????')}</strong><small>${esc(story.projectName || '')} ? ${story.deletedAt ? esc(date(story.deletedAt))+' ??' : '???'}</small></div>
      <div class="trash-actions"><button class="quiet" type="button" data-trash-story-restore="${esc(story.id)}">??</button><button class="quiet danger" type="button" data-trash-story-delete="${esc(story.id)}">????</button></div>
    </div>`).join('') : '<p class="muted">????????</p>';
    trashSessionList.innerHTML = sessionItems.length ? sessionItems.map(item=>`<div class="trash-item">
      <div><strong>${esc(item.jobTitle)}</strong><small>${esc(item.interviewMode || '???')} ? ${item.deletedAt ? esc(date(item.deletedAt))+' ??' : '???'}</small></div>
      <div class="trash-actions"><button class="quiet" type="button" data-trash-session-restore="${esc(item.id)}">??</button><button class="quiet danger" type="button" data-trash-session-delete="${esc(item.id)}">????</button></div>
    </div>`).join('') : '<p class="muted">??????????</p>';
  }

  async function loadTrash() {
    const storyTrash = storyResumeId ? await request('/stories/trash?resumeId='+encodeURIComponent(storyResumeId),{signal}).catch(()=>[]) : [];
    const sessionTrash = await request('/interviews/trash',{signal}).catch(()=>[]);
    if (!signal.aborted) renderTrash(storyTrash,sessionTrash);
  }

  form.elements.resumeId.addEventListener('change',()=>{loadStories(form.elements.resumeId.value);loadTrash();},{signal});
  storyPanel.addEventListener('click',event=>{
    const open = event.target.closest('[data-open-source]');
    if (open) {
      task(open,async()=>{
        const source = sessions.find(item=>String(item.id)===open.dataset.openSource);
        if (!source) throw new Error('来源面试记录不在当前列表中。');
        showSession(source,true);
      })(event);
      return;
    }
    const remove = event.target.closest('[data-delete-story]');
    if (!remove) return;
    task(remove,async()=>{
      await request('/stories/'+encodeURIComponent(remove.dataset.deleteStory),{method:'DELETE',signal});
      await loadStories(storyResumeId);
      await loadTrash();
      notify('故事已删除。');
    })(event);
  });
  const rebuildPicker = $('.story-rebuild-picker',storyPanel);
  const storySourceList = $('.story-rebuild-list',storyPanel);
  const rebuildConfirm = $('.story-rebuild-confirm',storyPanel);

  function renderRebuildSources() {
    const existing = new Set(storyItems.flatMap(story=>
      story.sourceSessionIds && story.sourceSessionIds.length ? story.sourceSessionIds : [story.sourceSessionId]
    ).map(Number).filter(Boolean));
    const completed = sessions.filter(item=>item.status==='COMPLETED' && String(item.resumeId)===String(storyResumeId));
    storySourceList.innerHTML = completed.length ? completed.map(item=>{
      const done = existing.has(Number(item.id));
      return `<label class="story-source-option"><input type="checkbox" data-source-session="${esc(item.id)}" ${done?'disabled':''}><span><strong>${esc(item.jobTitle)}</strong><small>${esc(item.interviewMode || '综合面')} · ${esc(date(item.updatedAt))}</small></span>${done?'<small>已整理</small>':''}</label>`;
    }).join('') : empty('没有可整理的面试','完成一场面试后再来整理。');
  }

  const rebuildStories = $('.story-rebuild',storyPanel);
  rebuildStories.addEventListener('click',()=>{
    if (!storyResumeId) { notify('请先选择简历。',true); return; }
    rebuildPicker.hidden = false;
    renderRebuildSources();
  });

  rebuildConfirm.addEventListener('click',task(rebuildConfirm,async()=>{
    const ids = [...storySourceList.querySelectorAll('input[data-source-session]:checked')].map(input=>Number(input.dataset.sourceSession));
    if (!ids.length) throw new Error('请至少勾选一场面试。');
    storySourceList.setAttribute('aria-busy','true');
    const stories = await request('/stories/rebuild-selected',{method:'POST',signal,data:{resumeId:storyResumeId,sessionIds:ids}});
    renderStories(stories);
    rebuildPicker.hidden = true;
    notify('已从勾选的面试中整理故事。');
  }));

  const storyClear = $('.story-clear',storyPanel);
  storyClear.addEventListener('click',task(storyClear,async()=>{
    if (!storyResumeId) throw new Error('\u8bf7\u5148\u9009\u62e9\u7b80\u5386\u3002');
    if (!confirm('\u786e\u5b9a\u5c06\u5168\u90e8\u6545\u4e8b\u79fb\u5165\u56de\u6536\u7ad9\u5417\uff1f')) return;
    const count = await request('/stories?resumeId='+encodeURIComponent(storyResumeId),{method:'DELETE',signal});
    await loadStories(storyResumeId);
    await loadTrash();
    notify(`\u5df2\u5c06 ${count} \u6761\u6545\u4e8b\u79fb\u5165\u56de\u6536\u7ad9\u3002`);
  }));

  const clearAllMemory = $('.clear-all-memory',storyPanel);
  clearAllMemory.addEventListener('click',task(clearAllMemory,async()=>{
    if (!confirm('\u786e\u5b9a\u5220\u9664\u6545\u4e8b\u5e93\u548c\u5168\u90e8\u7ec3\u4e60\u8bb0\u5f55\u5417\uff1f')) return;
    if (storyResumeId) await request('/stories?resumeId='+encodeURIComponent(storyResumeId),{method:'DELETE',signal});
    await request('/interviews',{method:'DELETE',signal});
    resetInterviewWorkspace();
    await loadStories(storyResumeId);
    await loadTrash();
    notify('\u6545\u4e8b\u548c\u7ec3\u4e60\u8bb0\u5f55\u5df2\u79fb\u5165\u56de\u6536\u7ad9\u3002');
  }));

  const historyClear = $('.history-clear',historyPanel);
  historyClear.addEventListener('click',task(historyClear,async()=>{
    if (!confirm('\u786e\u5b9a\u5c06\u5168\u90e8\u7ec3\u4e60\u8bb0\u5f55\u79fb\u5165\u56de\u6536\u7ad9\u5417\uff1f')) return;
    const count = await request('/interviews',{method:'DELETE',signal});
    resetInterviewWorkspace();
    await loadTrash();
    notify(`\u5df2\u5c06 ${count} \u6761\u7ec3\u4e60\u8bb0\u5f55\u79fb\u5165\u56de\u6536\u7ad9\u3002`);
  }));

  trashPanel.addEventListener('click',event=>{
    const storyRestore = event.target.closest('[data-trash-story-restore]');
    if (storyRestore) {
      task(storyRestore,async()=>{
        await request('/stories/'+encodeURIComponent(storyRestore.dataset.trashStoryRestore)+'/restore',{method:'POST',signal});
        await loadStories(storyResumeId);
        await loadTrash();
        notify('\u6545\u4e8b\u5df2\u6062\u590d\u3002');
      })(event);
      return;
    }
    const storyRemove = event.target.closest('[data-trash-story-delete]');
    if (storyRemove) {
      task(storyRemove,async()=>{
        if (!confirm('\u786e\u5b9a\u6c38\u4e45\u5220\u9664\u8fd9\u6761\u6545\u4e8b\u5417\uff1f')) return;
        await request('/stories/'+encodeURIComponent(storyRemove.dataset.trashStoryDelete)+'/permanent',{method:'DELETE',signal});
        await loadTrash();
        notify('\u6545\u4e8b\u5df2\u6c38\u4e45\u5220\u9664\u3002');
      })(event);
      return;
    }
    const sessionRestore = event.target.closest('[data-trash-session-restore]');
    if (sessionRestore) {
      task(sessionRestore,async()=>{
        await request('/interviews/'+encodeURIComponent(sessionRestore.dataset.trashSessionRestore)+'/restore',{method:'POST',signal});
        await refreshActiveSessions();
        await loadTrash();
        notify('\u7ec3\u4e60\u8bb0\u5f55\u5df2\u6062\u590d\u3002');
      })(event);
      return;
    }
    const sessionRemove = event.target.closest('[data-trash-session-delete]');
    if (sessionRemove) {
      task(sessionRemove,async()=>{
        if (!confirm('\u786e\u5b9a\u6c38\u4e45\u5220\u9664\u8fd9\u6761\u7ec3\u4e60\u8bb0\u5f55\u5417\uff1f')) return;
        await request('/interviews/'+encodeURIComponent(sessionRemove.dataset.trashSessionDelete)+'/permanent',{method:'DELETE',signal});
        await loadTrash();
        notify('\u7ec3\u4e60\u8bb0\u5f55\u5df2\u6c38\u4e45\u5220\u9664\u3002');
      })(event);
    }
  });

  const emptyTrash = $('.empty-trash',trashPanel);
  emptyTrash.addEventListener('click',task(emptyTrash,async()=>{
    if (!confirm('\u786e\u5b9a\u6c38\u4e45\u6e05\u7a7a\u56de\u6536\u7ad9\u5417\uff1f\u8fd9\u4e0d\u80fd\u64a4\u9500\u3002')) return;
    if (storyResumeId) await request('/stories/trash?resumeId='+encodeURIComponent(storyResumeId),{method:'DELETE',signal});
    await request('/interviews/trash',{method:'DELETE',signal});
    await loadTrash();
    notify('\u56de\u6536\u7ad9\u5df2\u6e05\u7a7a\u3002');
  }));

  let viewRevision = 0;
  bindBlueprint(form.elements.jobId,$('#interview-blueprint',form),signal);

  const projectSelect = form.elements.projectIndex;
  const claimInput = form.elements.guideClaim;
  const projectHint = $('#guide-project-hint',form);
  const boundaryOptions = $('#guide-boundary-options',form);
  const clueSummary = $('#guide-clue-summary',form);
  let clueRevision = 0;
  const levelSelect = form.elements.targetLevel;
  let levelTouched = false;
  function syncSuggestedLevel() {
    if (levelTouched) return;
    const job = jobs.find(item => String(item.id) === String(form.elements.jobId.value));
    if (job) levelSelect.value = inferInterviewLevel(job);
  }
  form.elements.jobId.addEventListener('change',syncSuggestedLevel,{signal});
  const modeHelp = $('#interview-mode-help',form);
  form.addEventListener('change',event=>{
    if(event.target.name !== 'interviewMode') return;
    const selected = interviewModeOptions.find(([value])=>value===event.target.value);
    if(selected) modeHelp.textContent = selected[3];
  },{signal});
  levelSelect.addEventListener('change',()=>{ levelTouched = true; },{signal});
  syncSuggestedLevel();
  function selectedResumeProjects() {
    const resumeId = Number(form.elements.resumeId.value || 0);
    return resumes.find(item => Number(item.id) === resumeId)?.projects || [];
  }
  function fillGuideProject() {
    const project = selectedResumeProjects()[Number(projectSelect.value || 0)];
    projectHint.textContent = project
      ? [project.role, project.techStack].filter(Boolean).join(' \u00b7 ') || '\u5df2\u4ece\u7b80\u5386\u5e26\u5165\u9879\u76ee\uff0c\u9762\u8bd5\u4e2d\u4f1a\u7ee7\u7eed\u8ffd\u95ee'
      : '\u672c\u7b80\u5386\u6682\u65e0\u9879\u76ee\uff0c\u9762\u8bd5\u5b98\u4f1a\u5148\u8be2\u95ee\u9879\u76ee\u80cc\u666f';
    claimInput.value = projectClaim(project);
    loadProjectClues(project);
  }

  async function loadProjectClues(project) {
    const revision = ++clueRevision;
    if (!project) {
      boundaryOptions.innerHTML = '<span class="muted">\u672c\u7b80\u5386\u6682\u65e0\u9879\u76ee\uff0c\u65e0\u6cd5\u751f\u6210\u5de5\u4f5c\u65b9\u9762\u3002</span>';
      clueSummary.textContent = '';
      return;
    }
    boundaryOptions.innerHTML = '<span class="muted">\u6b63\u5728\u5206\u6790\u8fd9\u4e2a\u9879\u76ee\u53ef\u80fd\u6d89\u53ca\u7684\u5de5\u4f5c\u65b9\u9762\u2026</span>';
    clueSummary.textContent = '\u6b63\u5728\u6839\u636e\u9879\u76ee\u63cf\u8ff0\u5f52\u7eb3\uff0c\u4e0d\u4ee3\u8868\u4f60\u5df2\u7ecf\u505a\u8fc7\u3002';
    const resume = resumes.find(item=>Number(item.id)===Number(form.elements.resumeId.value));
    try {
      const analysis = await request('/projects/analyze-clues',{method:'POST',signal,data:{
        projectName:project.name || '', role:project.role || '', techStack:project.techStack || '',
        description:project.description || '', challenge:project.challenge || '',
        highlight:project.highlight || '', targetRole:resume?.targetRole || ''
      }});
      if (signal.aborted || revision !== clueRevision) return;
      const items = (analysis.workstreams || []).map(item=>[item,item,false]);
      boundaryOptions.innerHTML = items.length
        ? guideChips('guideBoundary',items)
        : '<span class="muted">\u6ca1\u6709\u8bc6\u522b\u5230\u660e\u786e\u5de5\u4f5c\u65b9\u9762\uff0c\u53ef\u4ee5\u8865\u5145\u9879\u76ee\u63cf\u8ff0\u540e\u91cd\u8bd5\u3002</span>';
      clueSummary.textContent = analysis.summary || '\u8fd9\u4e9b\u662f\u5019\u9009\u5de5\u4f5c\u65b9\u9762\uff0c\u8bf7\u9009\u62e9\u660e\u786e\u4e0d\u662f\u4f60\u8d1f\u8d23\u7684\u5185\u5bb9\u3002';
    } catch (cause) {
      if (signal.aborted || revision !== clueRevision) return;
      boundaryOptions.innerHTML = '<span class="muted">\u6682\u65f6\u65e0\u6cd5\u5206\u6790\u9879\u76ee\uff0c\u53ef\u4ee5\u5237\u65b0\u91cd\u8bd5\u3002</span>';
      clueSummary.textContent = cause.message;
    }
  }

  function syncGuideProjects() {
    const projects = selectedResumeProjects();
    projectSelect.innerHTML = projects.length
      ? projects.map((project,index)=>`<option value="${index}">${esc(project.name || `项目 ${index+1}`)}</option>`).join('')
      : '<option value="">简历暂无项目</option>';
    fillGuideProject();
  }
  form.elements.resumeId.addEventListener('change',syncGuideProjects,{signal});
  projectSelect.addEventListener('change',fillGuideProject,{signal});
  syncGuideProjects();

  function updateHistory(activeId) {
    historyPanel.hidden = !sessions.length;
    $('.stack',historyPanel).innerHTML = sessions.map(item =>
      `<div class="history-item">
        <button type="button" class="row" data-session="${esc(item.id)}" aria-pressed="${item.id === activeId}">
          <strong>${esc(item.jobTitle)}</strong><small>${esc(dataKindLabel(item))} · ${esc(item.interviewMode || '综合面')} · ${item.status === 'COMPLETED' ? esc(scoreVersionLabel(item) || '已完成') : '继续练习'} · ${date(item.updatedAt)}</small>
        </button>
        <button type="button" class="history-delete" data-delete-session="${esc(item.id)}" aria-label="删除 ${esc(item.jobTitle)} 的历史对话" title="删除历史对话">删除</button>
      </div>`).join('');
  }

  function showSession(session, moveFocus) {
    if (signal.aborted) return;
    const index = sessions.findIndex(item => item.id === session.id);
    if (index >= 0) sessions.splice(index,1);
    sessions.unshift(session);
    if (chat.dataset.session !== String(session.id)) setup.open = false;
    $('summary',setup).textContent = (session.interviewMode || '综合面')+' · '+session.difficulty;    loadStories(session.resumeId);
    loadTrash();

    if (moveFocus) {
      const path = new URLSearchParams(location.hash.split('?')[1]);
      path.set('session',session.id);
      path.set('work','1');
      history.replaceState(history.state,'','#interview?'+path);
    }
    status.hidden = true;
    chat.classList.remove('cafe-table');
    renderChat(chat,session,signal,showSession,moveFocus);
    updateHistory(session.id);
  }

  updateHistory();
  form.addEventListener('submit',task($('[type="submit"]',form),async () => {
    const revision = ++viewRevision;
    const values = new FormData(form);
    const projects = selectedResumeProjects();
    const project = projects[Number(values.get('projectIndex') || 0)] || null;
    const roleChoice = String(values.get('guideRole') || '参与部分模块');
    const boundaries = values.getAll('guideBoundary').map(String);
    const focus = values.getAll('guideFocus').map(String);
    const proofs = values.getAll('guideProof').map(String);
    const claim = String(values.get('guideClaim') || projectClaim(project)).trim();
    const projectEvidence = project ? {
      projectName: project.name || '',
      role: project.role || roleChoice,
      teamSize: '',
      techStack: project.techStack || '',
      confidentiality: '未声明',
      businessFlow: claim || project.description || '',
      implementation: roleChoice,
      challenge: '',
      resultEvidence: proofs.join('、'),
      notes: '',
      evidenceDetails: {
        projectSummary: claim || project.description || '',
        ownedModules: roleChoice,
        notOwnedModules: boundaries.join('、'),
        evidenceAvailable: proofs.join('、'),
        focusAreas: focus.join('、')
      }
    } : null;
    const targetLevel = String(values.get('targetLevel') || '中级');
    const session = await request('/interviews',{method:'POST',signal,data:{
      resumeId:Number(values.get('resumeId')),jobId:Number(values.get('jobId')),
      difficulty:`${targetLevel} · ${difficulty}`,interviewMode:String(values.get('interviewMode') || '综合面'),projectEvidence
    }});
    if (revision === viewRevision) showSession(session,true);
  }));
  historyPanel.addEventListener('click',event => {
    const remove = event.target.closest('[data-delete-session]');
    if (remove) {
      const id = String(remove.dataset.deleteSession);
      const item = sessions.find(session => String(session.id) === id);
      if (!item || !confirm(`删除「${item.jobTitle}」的这条历史对话？`)) return;
      task(remove,async () => {
        await request('/interviews/'+encodeURIComponent(id),{method:'DELETE',signal});
        const index = sessions.findIndex(session => String(session.id) === id);
        if (index >= 0) sessions.splice(index,1);
        if (chat.dataset.session === id) {
          chat.dataset.session = '';
          chat.classList.add('cafe-table');
          chat.innerHTML = empty('面试尚未开始','选一份简历和目标岗位，开启练习。');
          setup.open = true;
          $('summary',setup).textContent = '安排本场练习';
          const path = new URLSearchParams(location.hash.split('?')[1] || '');
          path.delete('session');
          path.set('work','1');
          history.replaceState(history.state,'','#interview?'+path);
        }
        updateHistory(chat.dataset.session || '');
        await loadTrash();
        notify('历史对话已删除。');
      })();
      return;
    }
    const button = event.target.closest('[data-session]');
    if (!button) return;
    task(button,async () => {
      const revision = ++viewRevision;
      const session = await request('/interviews/'+button.dataset.session,{signal});
      if (revision === viewRevision) showSession(session,true);
    })();
  });

  const explicit = query.get('session');
  if (explicit) {
    try {
      const revision = ++viewRevision;
      const session = sessions.find(item => String(item.id) === explicit)
        || await request('/interviews/'+encodeURIComponent(explicit),{signal});
      if (revision === viewRevision) showSession(session,false);
    } catch {
      if (signal.aborted || chat.dataset.session) return;
      status.textContent = '暂时无法打开这场面试。可以从练习记录重新选择。';
      status.hidden = false;
    }
  } else if (!selectedJob && !query.has('room')) {
    const recent = sessions.find(item => item.status === 'IN_PROGRESS');
    if (recent) showSession(recent,false);
  }
}

function renderChat(chat, session, signal, onUpdate, moveFocus) {
  chat.dataset.session = session.id;
  const finished = session.status === 'COMPLETED';
  const mode = session.interviewMode || '综合面';
  const draftKey = session.id+':'+session.currentRound;
  const messages = session.messages || [];
  const latestFeedback = advisoryFeedback(messages.findLast(message => message.role === 'user' && message.feedback)?.feedback);
  const latestPrompt = messages.at(-1)?.role === 'assistant' ? messages.at(-1).content : '';
  const hasAnswered = messages.some(message => message.role === 'user');
  const earlier = messages.filter((message,index) => {
    if (message.role !== 'assistant') return true;
    return index !== messages.length - 1 && messages[index-1]?.feedback !== message.content;
  });
  chat.innerHTML = `<header class="interview-round">
      <div><p class="session-mode">${esc(mode)} · ${esc(session.difficulty)}${finished ? '' : ' · 自适应追问'}</p><h2>${esc(session.jobTitle)}</h2></div>
      <div class="interview-round-tools">
        <span class="chip">${finished ? esc(scoreVersionLabel(session) || '已完成') : `第 ${esc(session.currentRound)} / ${esc(session.totalRounds)} 轮`}</span>
        ${hasAnswered ? '<button class="quiet undo-answer" type="button">← 撤回上一题</button>' : ''}
      </div>
    </header>
    <div class="interview-current" aria-live="polite">
      <h3>${finished ? '练习总结' : '当前问题'}</h3><p class="pre-line">${esc(latestPrompt)}</p>
    </div>
    ${finished ? link('growth?work=1&session='+encodeURIComponent(session.id)+'&resume='+encodeURIComponent(session.resumeId)+'&job='+encodeURIComponent(session.jobId),'去成长花园复盘 ↗','button primary')
      : `<form class="flow" id="answer-form"><label>你的回答<textarea name="answer" required rows="3" placeholder="讲讲你做了什么、为什么这么做，以及结果如何。">${esc(answerDrafts.get(draftKey))}</textarea></label><button class="button primary" type="submit">发送回答 ↑</button></form>`}
    ${latestFeedback ? `<details class="interview-feedback" open>
      <summary>${finished?'最后一轮反馈':'上一轮反馈'}</summary><p class="pre-line">${esc(latestFeedback)}</p>
    </details>` : ''}
    ${finished?`<section class="interview-diagnosis"><button class="quiet" id="diagnose-session" type="button">查看本场简历诊断</button><div class="diagnosis-inline" aria-live="polite"></div></section>`:''}
    ${earlier.length ? `<details class="interview-past">
      <summary>查看之前的问答</summary>
      <div class="chat-scroll">${earlier.map(message=>`<article class="bubble ${message.role==='user'?'own':''}">
        <strong>${message.role==='user'?'我的回答':'陪练'}</strong><p>${esc(message.content)}</p>
        ${message.feedback?`<small>${esc(message.feedback)}</small>`:''}
      </article>`).join('')}</div>
    </details>` : ''}`;
  const diagnose = $('#diagnose-session',chat);
  diagnose?.addEventListener('click',task(diagnose,async()=>{
    const output = $('.diagnosis-inline',chat);
    output.textContent = '正在整理本场面试证据…';
    try {
      const report = await request('/diagnoses',{method:'POST',signal,data:{resumeId:session.resumeId,jobId:session.jobId}});
      if (output.isConnected) renderReport(output,report);
    } catch (cause) {
      if (output.isConnected) output.textContent = cause.message;
    }
  }));
  const undo = $('.undo-answer',chat);
  undo?.addEventListener('click',task(undo,async()=>{
    const warning = finished
      ? '撤回最后一次回答会重新打开本场面试，并移除本场生成的成长故事。继续吗？'
      : '撤回最后一次回答，返回上一题重新回答？';
    if (!confirm(warning)) return;
    const updated = await request('/interviews/'+session.id+'/undo',{method:'POST',data:{},signal});
    for (const key of [...answerDrafts.keys()]) {
      if (key.startsWith(session.id+':')) answerDrafts.delete(key);
    }
    if (chat.dataset.session === String(session.id)) onUpdate(updated,true);
  }));
  const form = $('#answer-form',chat);
  if (moveFocus) {
    const focusTarget = form?.elements.answer || $('.button.primary',chat);
    focusTarget?.focus({preventScroll:true});
    focusTarget?.scrollIntoView({block:'nearest'});
  }
  form?.addEventListener('input',()=>answerDrafts.set(draftKey,form.elements.answer.value));
  form?.addEventListener('submit',task($('button',form),async () => {
    const draft = form.elements.answer.value;
    const answer = draft.trim();
    if (!answer) throw new Error('请先写下你的回答。');
    const updated = await request('/interviews/'+session.id+'/answers',{method:'POST',data:{answer},signal});
    if (answerDrafts.get(draftKey) === draft || updated.currentRound !== session.currentRound || updated.status === 'COMPLETED') {
      answerDrafts.delete(draftKey);
    }
    if (chat.dataset.session === String(session.id)) onUpdate(updated,true);
  }));
}

export async function growth(root, signal, query) {
  root.innerHTML = `<div class="workspace columns growth-layout">
    <section class="panel" id="growth-options"><h2>练习手记</h2><p class="muted" role="status">正在读取档案索引…</p></section>
    <section class="panel" id="growth-report" aria-label="记录详情" aria-live="polite">${empty('正在读取记录详情')}</section>
  </div>`;
  const choices = $('#growth-options',root), target = $('#growth-report',root);
  const [reportRows,sessionRows] = await Promise.all([request('/diagnoses',{signal}),request('/interviews',{signal})]);
  const reports = filterDemo(reportRows);
  const sessions = filterDemo(sessionRows);
  if (signal.aborted) return;
  const completed = sessions.filter(item=>item.status==='COMPLETED');
  const events = [
    ...completed.map(item=>({type:'session', item, time:item.updatedAt, title:item.jobTitle, label:`${dataKindLabel(item)}面试${item.scoreComparable ? '' : ' · 旧评分规则'}`})),
    ...reports.map(item=>({type:'report', item, time:item.createdAt, title:item.targetRole, label:`${dataKindLabel(item)}简历诊断`}))
  ].sort(byRecent);
  const session = completed.find(item=>String(item.id)===query.get('session'))
    || completed.find(item=>String(item.resumeId)===query.get('resume') && String(item.jobId)===query.get('job'));
  const requested = session
    ? events.find(entry=>entry.type==='session' && entry.item===session)
    : events.find(entry=>entry.type==='report' && String(entry.item.id)===query.get('report'));
  if (!events.length) {
    choices.innerHTML = `<h2>练习手记</h2><p>完成一场面试，这里就会留下可回看的记录。</p>${link('interview','去面试咖啡馆 ↗')}`;
    target.innerHTML = empty('还没有复盘记录','完成练习后，可以回看每一轮反馈。');
    return;
  }

  choices.innerHTML = `<h2>练习手记</h2><ol class="growth-timeline" aria-label="按时间排列的面试和诊断"></ol>`;
  const timeline = $('.growth-timeline',choices);
  timeline.insertAdjacentHTML('beforebegin',`<label class="growth-ruler">翻阅档案 <output></output>
    <input type="range" min="0" max="${events.length-1}" aria-label="按日期翻阅档案">
    <span><small>最早</small><small>最近</small></span></label>`);
  const ruler = $('input',choices), rulerDate = $('output',choices);
  ruler.oninput = ()=>show(events[events.length-1-Number(ruler.value)],true);
  if (completed.length > 1) {
    timeline.insertAdjacentHTML('beforebegin','<button type="button" class="quiet compare-answers">并排回看两次回答</button>');
    $('.compare-answers',choices).onclick = ()=>{
      const first = active?.type==='session' ? active.item : completed[0];
      const previous = active;
      comparePractice(target,completed,first,()=>{
        show(previous || {type:'session',item:first,time:first.updatedAt,title:first.jobTitle,label:`${dataKindLabel(first)}面试${first.scoreComparable ? '' : ' · 旧评分规则'}`},true);
      });
    };
  }
  let active;

  function byRecent(a,b) {
    return String(b.time || '').localeCompare(String(a.time || ''));
  }

  function renderTimeline() {
    timeline.innerHTML = events.map((entry,i)=>`
      <li data-kind="${entry.type}"><button class="row" type="button" data-record="${i}" aria-pressed="${entry===active}">
        <small>${entry.label} · <time>${date(entry.time)}</time></small><strong>${esc(entry.title)}</strong>
      </button></li>`).join('');
  }

  function show(entry, remember = false) {
    active = entry;
    ruler.max = events.length-1;
    ruler.value = events.length-1-events.indexOf(entry);
    ruler.disabled = events.length<2;
    rulerDate.textContent = date(entry.time);
    ruler.setAttribute('aria-valuetext',date(entry.time)+' · '+entry.title);
    for (const button of $$('[data-record]',timeline)) {
      button.setAttribute('aria-pressed',String(events[Number(button.dataset.record)] === entry));
    }
    if (remember) {
      window.history.replaceState(window.history.state,'','#growth?work=1&'+entry.type+'='+encodeURIComponent(entry.item.id));
    }
    if (entry.type === 'report') renderReport(target,entry.item);
    else renderPractice(entry.item);
    if (!document.body.classList.contains('no-motion') && !matchMedia('(prefers-reduced-motion: reduce)').matches) {
      target.getAnimations().forEach(animation=>animation.cancel());
      target.animate([{clipPath:'inset(0 0 12% 0)'},{clipPath:'inset(0)'}],{duration:180,easing:'ease-out'});
    }
  }

  function renderPractice(item) {
    const messages = item.messages || [];
    const turns = messages.flatMap((message,index)=>message.role === 'user' ? [{
      question: messages[index-1]?.role === 'assistant' ? messages[index-1].content : '',
      answer: message.content,
      feedback: advisoryFeedback(message.feedback)
    }] : []);
    const latestFeedback = turns.at(-1)?.feedback || '';
    const versionLabel = scoreVersionLabel(item);
    target.innerHTML = `<p class="record-type">${esc(item.interviewMode || '综合面')}复盘 · ${esc(dataKindLabel(item))}${versionLabel ? ` · ${esc(versionLabel)}` : ''}</p><h2>${esc(item.jobTitle)}</h2>
      ${item.skillEvidence?.length ? `<details class="disclosure"><summary>面试技能证据</summary>
        ${item.skillEvidence.map(skill=>`<article class="note"><strong>${esc(skill.skill)} · ${skill.verified?'已有回答证据':'待补充证据'}</strong>
          <ul>${(skill.evidence || []).map(item=>`<li>${esc(item)}</li>`).join('')}</ul></article>`).join('')}</details>`:''}
      ${latestFeedback ? `<section class="journal-action"><h3>最近一轮建议反馈</h3><p class="pre-line">${esc(latestFeedback)}</p></section>` : ''}
      <form class="growth-action">
        <input type="hidden" name="session" value="${esc(item.id)}">
        <button class="button primary" type="submit">生成简历诊断</button>
        <p class="muted">结合这份简历、目标岗位和已有的面试记录，整理接下来可以改的地方。</p>
      </form>
      ${turns.length ? `<details class="disclosure"><summary>查看这场练习的问题、回答与反馈</summary>
        ${turns.map((turn,index)=>`<article class="note round-record"><strong>第 ${index+1} 轮</strong>
          <h4>问题</h4><p class="pre-line">${esc(turn.question || '这轮未保存问题')}</p>
          <h4>你的回答</h4><p class="pre-line">${esc(turn.answer)}</p>
          <h4>建议反馈</h4><p class="pre-line">${esc(turn.feedback || '这轮暂无建议反馈')}</p>
        </article>`).join('')}
      </details>` : ''}`;
    $('form',target).addEventListener('submit',task($('button',target),async()=>{
      const report = await request('/diagnoses',{method:'POST',signal,data:{resumeId:item.resumeId,jobId:item.jobId}});
      if (signal.aborted) return;
      const index = events.findIndex(entry=>entry.type==='report' && entry.item.id===report.id);
      if (index >= 0) events.splice(index,1);
      const record = {type:'report',item:report,time:report.createdAt,title:report.targetRole,label:`${dataKindLabel(report)}简历诊断`};
      events.push(record);
      events.sort(byRecent);
      const stillReviewing = active.item === item;
      renderTimeline();
      if (stillReviewing) {
        show(record,true);
        $('h2',target).focus({preventScroll:true});
      }
    }));
  }

  timeline.addEventListener('click',event=>{
    const button = event.target.closest('[data-record]');
    if (button) show(events[Number(button.dataset.record)],true);
  });
  renderTimeline();
  show(requested || events[0]);
}

function comparePractice(target, sessions, first, onBack) {
  target.innerHTML = `<div class="compare-toolbar"><button type="button" class="quiet compare-back">← 返回上一步</button><h2 tabindex="-1">并排回看回答</h2></div><div class="answer-spread"></div>`;
  $('.compare-back',target).addEventListener('click',()=>onBack?.());
  const spread = $('.answer-spread',target);
  for (const [index,initial] of [first,sessions.find(item=>item!==first)].entries()) {
    spread.insertAdjacentHTML('beforeend',`<section class="answer-leaf">
      <label>${index?'右侧练习':'左侧练习'}<select data-session-choice>${options(sessions,initial.id,item=>item.jobTitle+' · '+date(item.updatedAt))}</select></label>
      <label>问题<select data-round-choice></select></label><div class="answer-entry"></div></section>`);
    const column = spread.lastElementChild, session = $('[data-session-choice]',column);
    const round = $('[data-round-choice]',column), entry = $('.answer-entry',column);
    let turns;
    function answer() {
      const item = turns[Number(round.value)];
      entry.innerHTML = item ? `<h3>当时的问题</h3><p>${esc(item.question || '这轮未保存问题')}</p>
        <h3>你的回答</h3><p class="pre-line">${esc(item.answer)}</p>
        <h3>建议反馈</h3><p class="pre-line">${esc(item.feedback || '这轮未保存反馈')}</p>` : empty('这次练习没有保存回答');
    }
    function load() {
      const messages = sessions.find(item=>String(item.id)===session.value)?.messages || [];
      turns = messages.flatMap((item,i)=>item.role==='user' ? [{
        answer:item.content,feedback:advisoryFeedback(item.feedback),question:messages[i-1]?.role==='assistant'?messages[i-1].content:''
      }] : []);
      round.closest('label').hidden = !turns.length;
      round.innerHTML = turns.map((item,i)=>`<option value="${i}">${i+1}. ${esc(item.question?.slice(0,60) || '回答')}</option>`).join('');
      answer();
    }
    session.onchange = load;
    round.onchange = answer;
    load();
  }
  $('.compare-toolbar h2',target).focus({preventScroll:true});
  $('.compare-toolbar h2',target).scrollIntoView({block:'nearest'});
}

export function renderReport(target, report){
  target.innerHTML = `<p class="record-type">简历诊断</p><h2 tabindex="-1">${esc(report.targetRole)}</h2>
    ${report.suggestions?.length ? `<section class="report-actions"><h3>下一步怎么改</h3>
      ${report.suggestions.map((item,i)=>`<article class="note" style="--reveal-delay:${Math.min(i,5)*65}ms">
        ${i===0?'<small class="record-type">可以先改这一处</small>':''}
        <p class="suggestion-action">${esc(item.action || item.issue)}</p>
        ${item.example?`<blockquote><small>参考写法</small><p>${esc(item.example)}</p></blockquote>`:''}
        ${item.action && item.issue ? `<details class="suggestion-evidence"><summary>查看这条建议的依据</summary><p>${esc(item.issue)}</p></details>` : ''}
      </article>`).join('')}
    </section>` : ''}
    ${report.strengths?.length ? `<details class="disclosure"><summary>这次已经做好的地方</summary><ul class="report-strengths">${report.strengths.map(item=>`<li>${esc(item)}</li>`).join('')}</ul></details>` : ''}
    <details class="disclosure report-details"><summary>查看诊断依据与评分</summary>
      <p>${report.summary ? esc(report.summary) : `本次简历诊断 · ${esc(report.overallScore)} 分`}</p>
      ${(report.dimensions || []).map(item=>`<div class="metric"><span>${esc(item.name)}</span><meter aria-label="${esc(item.name)}" min="0" max="100" value="${Number(item.score)||0}"></meter><strong>${esc(item.score)}</strong><small>${esc(item.comment)}</small></div>`).join('')}
    </details>
    ${report.jobMatchBreakdown?.length ? `<details class="disclosure"><summary>岗位匹配依据</summary>
      <ul>${report.jobMatchBreakdown.map(item=>`<li>${esc(item.label || item.key)} · ${esc(item.detail || item.contribution)}</li>`).join('')}</ul></details>`:''}
    ${report.agentTrace?.length ? `<details class="disclosure"><summary>查看分析记录</summary><ol>${report.agentTrace.map(item=>`<li>${esc(item)}</li>`).join('')}</ol></details>`:''}
    ${report.learningPlan ? `<details class="disclosure"><summary>查看学习路线</summary>${learning(report.learningPlan)}</details>` : ''}`;
}

export function learning(plan) {
  return `<p>${esc(plan?.summary)}</p><div class="stack">${(plan?.modules || []).map(item=>`<div class="note"><strong>${esc(item.title)} · ${esc(item.duration)}</strong><p>${esc(item.objective)}</p><p>${esc(item.deliverable)}</p></div>`).join('')}</div>`;
}
