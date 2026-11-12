import { bindBlueprint } from './interview-blueprint.js';
import { request } from './api.js';
import { $, $$, esc, date, options, empty, link, task } from './ui.js';

const answerDrafts = new Map();

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
  const [resumes,jobs,sessions]=responses.map(result=>result.status==='fulfilled'?result.value:[]);
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
      <p id="chosen-room" class="chosen-room">${difficulty==='压力'?'里间会议桌 · 压力练习':'窗边圆桌 · 标准练习'}</p>
      ${unavailable?'<p class="muted">部分资料暂时无法加载，可以刷新重试。</p>':''}
      <button class="button primary" type="submit">开始面试</button>
      <div id="interview-blueprint"></div>
      ${resumes.length ? '' : link('studio?work=1','先写一份简历 ↗','text-link')}
    </form>
    </details>
    <details class="interview-history" open hidden>
      <summary>练习记录</summary><div class="stack"></div>
    </details>
    <p class="session-load-status muted" role="status" hidden></p>`;
  const form = $('#interview-start',settings);
  const setup = $('.interview-setup',settings), historyPanel = $('.interview-history',settings);
  const status = $('.session-load-status',settings);
  let viewRevision = 0;
  bindBlueprint(form.elements.jobId,$('#interview-blueprint',form),signal);

  function updateHistory(activeId) {
    historyPanel.hidden = !sessions.length;
    $('.stack',historyPanel).innerHTML = sessions.map(item =>
      `<button type="button" class="row" data-session="${esc(item.id)}" aria-pressed="${item.id === activeId}">
        <strong>${esc(item.jobTitle)}</strong><small>${item.status === 'COMPLETED' ? '已完成' : '继续练习'} · ${date(item.updatedAt)}</small>
      </button>`).join('');
  }

  function showSession(session, moveFocus) {
    if (signal.aborted) return;
    const index = sessions.findIndex(item => item.id === session.id);
    if (index >= 0) sessions.splice(index,1);
    sessions.unshift(session);
    if (chat.dataset.session !== String(session.id)) setup.open = false;
    $('summary',setup).textContent = '练习设置 · '+session.difficulty;
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
    const session = await request('/interviews',{method:'POST',signal,data:{
      resumeId:Number(values.get('resumeId')),jobId:Number(values.get('jobId')),difficulty
    }});
    if (revision === viewRevision) showSession(session,true);
  }));
  historyPanel.addEventListener('click',event => {
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
  const draftKey = session.id+':'+session.currentRound;
  const messages = session.messages || [];
  const latestFeedback = messages.findLast(message => message.role === 'user' && message.feedback)?.feedback;
  const latestPrompt = messages.at(-1)?.role === 'assistant' ? messages.at(-1).content : '';
  const earlier = messages.filter((message,index) => {
    if (message.role !== 'assistant') return true;
    return index !== messages.length - 1 && messages[index-1]?.feedback !== message.content;
  });
  chat.innerHTML = `<header class="interview-round">
      <div><p class="session-mode">${esc(session.difficulty)}练习</p><h2>${esc(session.jobTitle)}</h2></div>
      <span class="chip">${finished ? '已完成' : `第 ${esc(session.currentRound)} / ${esc(session.totalRounds)} 轮`}</span>
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
  const [reports,sessions] = await Promise.all([request('/diagnoses',{signal}),request('/interviews',{signal})]);
  if (signal.aborted) return;
  const completed = sessions.filter(item=>item.status==='COMPLETED');
  const events = [
    ...completed.map(item=>({type:'session', item, time:item.updatedAt, title:item.jobTitle, label:'面试练习'})),
    ...reports.map(item=>({type:'report', item, time:item.createdAt, title:item.targetRole, label:'简历诊断'}))
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
      comparePractice(target,completed,first);
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
    const feedback = (item.messages || []).filter(message=>message.role === 'user' && message.feedback);
    target.innerHTML = `<p class="record-type">面试复盘</p><h2>${esc(item.jobTitle)}</h2>
      ${item.skillEvidence?.length ? `<details class="disclosure"><summary>面试技能证据</summary>
        ${item.skillEvidence.map(skill=>`<article class="note"><strong>${esc(skill.skill)} · ${skill.verified?'已有回答证据':'待补充证据'}</strong>
          <ul>${(skill.evidence || []).map(item=>`<li>${esc(item)}</li>`).join('')}</ul></article>`).join('')}</details>`:''}
      ${feedback.length ? `<section class="journal-action"><h3>最近一轮反馈</h3><p class="pre-line">${esc(feedback.at(-1).feedback)}</p></section>` : ''}
      <form class="growth-action">
        <input type="hidden" name="session" value="${esc(item.id)}">
        <button class="button primary" type="submit">生成简历诊断</button>
        <p class="muted">结合这份简历、目标岗位和已有的面试记录，整理接下来可以改的地方。</p>
      </form>
      ${feedback.length ? `<details class="disclosure"><summary>查看这场练习的逐轮反馈</summary>
        ${feedback.map((message,index)=>`<div class="note"><strong>第 ${index+1} 轮</strong><p class="pre-line">${esc(message.feedback)}</p></div>`).join('')}
      </details>` : ''}`;
    $('form',target).addEventListener('submit',task($('button',target),async()=>{
      const report = await request('/diagnoses',{method:'POST',signal,data:{resumeId:item.resumeId,jobId:item.jobId}});
      if (signal.aborted) return;
      const index = events.findIndex(entry=>entry.type==='report' && entry.item.id===report.id);
      if (index >= 0) events.splice(index,1);
      const record = {type:'report',item:report,time:report.createdAt,title:report.targetRole,label:'简历诊断'};
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

function comparePractice(target, sessions, first) {
  target.innerHTML = '<h2 tabindex="-1">并排回看回答</h2><div class="answer-spread"></div>';
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
        <h3>当时的反馈</h3><p class="pre-line">${esc(item.feedback || '这轮未保存反馈')}</p>` : empty('这次练习没有保存回答');
    }
    function load() {
      const messages = sessions.find(item=>String(item.id)===session.value)?.messages || [];
      turns = messages.flatMap((item,i)=>item.role==='user' ? [{
        answer:item.content,feedback:item.feedback,question:messages[i-1]?.role==='assistant'?messages[i-1].content:''
      }] : []);
      round.closest('label').hidden = !turns.length;
      round.innerHTML = turns.map((item,i)=>`<option value="${i}">${i+1}. ${esc(item.question?.slice(0,60) || '回答')}</option>`).join('');
      answer();
    }
    session.onchange = load;
    round.onchange = answer;
    load();
  }
  $('h2',target).focus({preventScroll:true});
  $('h2',target).scrollIntoView({block:'nearest'});
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
