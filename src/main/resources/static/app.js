const EXCLUDED_CITY_KEY = 'careernav-excluded-cities';
const state = {
  resumes: [], jobs: [], recommendations: [], dashboard: null,
  activeResumeId: null, activeJobId: null, interview: null,
  diagnosis: null, jobAgent: null, learningPath: null, skillGraph: null, directions: [], atsReport: null,
  projects: [], difficulty: '标准', view: 'dashboard',
  excludedCities: [], recommendationRequestId: 0, agentMatches: new Map(),
  agentLoading: false, agentError: ''
};
const pageTitles = { dashboard:'成长总览', resume:'简历工坊', jobs:'岗位雷达', interview:'模拟面试', diagnosis:'简历诊断' };
const $ = (selector, root=document) => root.querySelector(selector);
const $$ = (selector, root=document) => [...root.querySelectorAll(selector)];

async function api(path, options={}) {
  const config = { headers:{ 'Content-Type':'application/json' }, ...options };
  const response = await fetch(path, config);
  let payload;
  try { payload = await response.json(); } catch { throw new Error(`服务返回异常 (${response.status})`); }
  if (!response.ok || payload.success === false) throw new Error(payload.message || `请求失败 (${response.status})`);
  return payload.data;
}
function post(path, body) { return api(path, { method:'POST', body:JSON.stringify(body) }); }
function put(path, body) { return api(path, { method:'PUT', body:JSON.stringify(body) }); }
function escapeHtml(value='') { return String(value).replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char])); }
function compactText(value, limit=110) { const text=String(value||'').replace(/\s+/g,' ').trim(); return text.length>limit?text.slice(0,limit)+'…':text; }
function sleep(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }
function toast(title, message, error=false) {
  const node = document.createElement('div'); node.className = `toast${error?' error':''}`;
  node.innerHTML = `<strong>${escapeHtml(title)}</strong><span>${escapeHtml(message)}</span>`;
  $('#toast-stack').appendChild(node); setTimeout(() => node.remove(), 3600);
}
function setOptions(select, items, valueKey, labelFn, placeholder) {
  const previous = select.value;
  select.innerHTML = placeholder ? `<option value="">${placeholder}</option>` : '';
  items.forEach(item => select.insertAdjacentHTML('beforeend', `<option value="${valueKey(item)}">${escapeHtml(labelFn(item))}</option>`));
  if ([...select.options].some(option => option.value === previous)) select.value = previous;
}
function primaryResume() { return state.resumes.find(item => item.id === state.activeResumeId) || state.resumes[0] || null; }
function jobById(id) { return state.jobs.find(item => item.id === Number(id)) || null; }

function navigate(view) {
  if (!pageTitles[view]) return;
  state.view = view;
  $$('.nav-item').forEach(item => item.classList.toggle('active', item.dataset.view === view));
  $$('.view').forEach(item => item.classList.toggle('active', item.id === `view-${view}`));
  $('#page-title').textContent = pageTitles[view];
  if (view === 'resume') { const resume = primaryResume(); if (resume && !$('#resume-id').value) fillResumeForm(resume); }
  if (view === 'jobs') renderJobs();
  if (view === 'interview') populateInterviewOptions();
  if (view === 'diagnosis') populateDiagnosisOptions();
  window.scrollTo({ top:0, behavior:'smooth' });
}

async function boot() {
  try {
    const [resumes, jobs, dashboard, agentStatus, directions] = await Promise.all([
      api('/api/resumes'), api('/api/jobs'), api('/api/dashboard'), api('/api/agent/status'),
      api('/api/resume-guidance/directions').catch(() => [])
    ]);
    state.resumes = resumes; state.jobs = jobs; state.dashboard = dashboard; state.directions = directions || [];
    state.activeResumeId = resumes[0]?.id || null;
    state.activeJobId = dashboard.topJobs?.[0]?.job?.id || jobs[0]?.id || null;
    restoreMatchPreferences(); updateMatchPolicyPanel(); populateAllSelects();
    if (resumes[0]) fillResumeForm(resumes[0]);
    renderDashboard(dashboard);
    if (state.view === 'jobs') renderJobs();
    updateAgentStatus(agentStatus);
    if (dashboard.latestDiagnosis) { state.diagnosis = dashboard.latestDiagnosis; renderDiagnosis(dashboard.latestDiagnosis); }
    $('#sidebar-agent-mode').textContent = agentStatus.mode === 'LANGCHAIN4J_ONLINE' ? '在线大模型工具链' : '本地推理工具链';
    $('#mcp-status').textContent = agentStatus.mcpEnabled ? 'REMOTE ONLINE' : 'LOCAL FALLBACK';
    // 支持 ?view=resume 这类深链接，便于分享与演示直达某个页面
    const initialView = new URLSearchParams(location.search).get('view');
    if (initialView && pageTitles[initialView]) navigate(initialView);
    if (new URLSearchParams(location.search).get('guidance') === '1') setTimeout(openGuidance, 600);
    if (new URLSearchParams(location.search).get('ats') === '1') setTimeout(openAts, 600);
    const jobParam = new URLSearchParams(location.search).get('job');
    if (jobParam) setTimeout(() => openJobModal(Number(jobParam)), 800);
  } catch (error) {
    toast('初始化失败', error.message, true);
    $('#sidebar-agent-mode').textContent = '服务未连接';
  }
}
function updateAgentStatus(status) {
  const label = status.mode === 'LANGCHAIN4J_ONLINE' ? '在线模型' : '本地推理';
  $('#agent-mode-top').textContent = label;
  $('#dashboard-agent-log').innerHTML = (status.tools || []).slice(0,4).map((tool,index) => `<div><span>0${index+1}</span><p><b>${escapeHtml(tool)}</b> 已注册</p></div>`).join('');
}
function populateAllSelects() {
  setOptions($('#interview-resume'), state.resumes, item => item.id, item => `${item.ownerName} · ${item.targetRole}`);
  setOptions($('#diagnosis-resume'), state.resumes, item => item.id, item => `${item.ownerName} · ${item.targetRole}`);
  setOptions($('#interview-job'), state.jobs, item => item.id, item => `${item.title} · ${item.company}`);
  setOptions($('#diagnosis-job'), state.jobs, item => item.id, item => `${item.title} · ${item.company}`, '不指定岗位');
  const cities = [...new Set(state.jobs.map(item => item.city).filter(Boolean))].sort((a,b) => a.localeCompare(b, 'zh-CN'));
  setOptions($('#job-city-filter'), cities, city => city, city => city, '全部城市');
  setOptions($('#job-category-filter'), state.directions || [], d => d, d => d, '全部方向');
  if (state.activeResumeId) { $('#interview-resume').value = state.activeResumeId; $('#diagnosis-resume').value = state.activeResumeId; }
  if (state.activeJobId) { $('#interview-job').value = state.activeJobId; $('#diagnosis-job').value = state.activeJobId; }
}
function populateInterviewOptions() { populateAllSelects(); if (state.interview) renderInterview(state.interview); }
function populateDiagnosisOptions() { populateAllSelects(); if (state.diagnosis) renderDiagnosis(state.diagnosis); if (state.jobAgent) renderJobAgent(state.jobAgent); }
function restoreMatchPreferences() {
  try {
    const storedCities = JSON.parse(localStorage.getItem(EXCLUDED_CITY_KEY) || '[]');
    if (Array.isArray(storedCities)) state.excludedCities = [...new Set(storedCities.map(item => String(item).trim()).filter(Boolean))];
  } catch { state.excludedCities = []; }
}
function saveMatchPreferences() {
  try {
    localStorage.setItem(EXCLUDED_CITY_KEY, JSON.stringify(state.excludedCities));
  } catch { /* local preference only */ }
}
function updateMatchPolicyPanel() {
  const chips = $('#excluded-city-chips');
  if (chips) chips.innerHTML = state.excludedCities.length
    ? state.excludedCities.map((city,index) => `<span class="excluded-city-chip">${escapeHtml(city)}<button type="button" data-remove-excluded-city="${index}" title="移除">×</button></span>`).join('')
    : '<span class="excluded-city-empty">暂未排除城市</span>';
  if ($('#excluded-city-count')) $('#excluded-city-count').textContent = `${state.excludedCities.length} 个`;
  if ($('#match-policy-summary')) $('#match-policy-summary').textContent = 'Agent 精排 · 规则兜底';
  updateAgentMatchNote();
}
async function applyMatchSettings(showToast=true) {
  saveMatchPreferences(); updateMatchPolicyPanel();
  try {
    await loadRecommendations(); renderJobs();
    if (showToast) toast('匹配偏好已更新', state.excludedCities.length ? `已排除 ${state.excludedCities.length} 个城市` : '已恢复全部城市');
  } catch (error) { toast('重新匹配失败', error.message, true); }
}
function addExcludedCity() {
  const input = $('#excluded-city-input');
  const values = input.value.split(/[,，、]/).map(item => item.trim()).filter(Boolean);
  if (!values.length) return;
  state.excludedCities = [...new Set([...state.excludedCities, ...values])];
  input.value = ''; saveMatchPreferences(); updateMatchPolicyPanel(); applyMatchSettings(false);
}
function removeExcludedCity(index) {
  state.excludedCities.splice(index,1); saveMatchPreferences(); updateMatchPolicyPanel(); applyMatchSettings(false);
}
function renderDashboard(data) {
  if (!data) return;
  const stats = data.stats || {};
  $('#stat-resumes').textContent = stats.resumeCount ?? 0;
  $('#stat-jobs').textContent = stats.jobCount ?? 0;
  $('#stat-score').textContent = stats.averageDiagnosisScore ? `${stats.averageDiagnosisScore}` : '--';
  $('#stat-match').textContent = stats.averageMatchScore ? `${stats.averageMatchScore}%` : '--';
  $('#hero-jobs').textContent = stats.jobCount ?? 0;
  $('#hero-score').textContent = stats.averageDiagnosisScore ? `${stats.averageDiagnosisScore}` : '--';
  $('#stat-mcp').textContent = data.mcpOnline ? '远程 MCP 已连接' : 'MCP 本地回退源';
  renderDashboardJobs(data.topJobs || []);
  if (data.latestDiagnosis) renderDashboardDiagnosis(data.latestDiagnosis);
}
function renderDashboardJobs(items) {
  const target = $('#dashboard-jobs');
  if (!items.length) { target.innerHTML = '<div class="empty-mini">暂无推荐岗位</div>'; return; }
  target.innerHTML = items.slice(0,4).map((item,index) => `<div class="compact-job">
    <div class="rank">0${index+1}</div><div><h4>${escapeHtml(item.job.title)}</h4><p>${escapeHtml(item.job.company)} · ${escapeHtml(item.job.city)} · ${escapeHtml(item.job.salary)}</p></div>
    <div class="match-mini"><strong>${item.matchScore}%</strong><span>${escapeHtml(item.matchLevel)}</span></div></div>`).join('');
}
function renderDashboardDiagnosis(report) {
  const bars = report.dimensions.slice(0,3).map(item => `<div class="score-bar"><div><span>${escapeHtml(item.name)}</span><b>${item.score}</b></div><span><i style="width:${item.score}%"></i></span></div>`).join('');
  $('#dashboard-diagnosis').innerHTML = `<div class="score-preview"><div class="score-ring" style="--score:${report.overallScore}"><div><strong>${report.overallScore}</strong><span>${escapeHtml(report.level)}</span></div></div><div class="score-bars">${bars}</div></div>`;
}

/* resume editor */
const RESUME_TEMPLATE_KEY = 'careernav-resume-template';
const RESUME_TEMPLATES = ['minimal', 'formal', 'english'];
function blankProject() { return { name:'', role:'', techStack:'', description:'', highlight:'' }; }
function blankExperience() { return { company:'', position:'', period:'', description:'', achievement:'' }; }
function uniqueSkills(values=[]) {
  return [...new Set(values.map(value => String(value || '').trim()).filter(Boolean))];
}
function initialResumeTemplate() {
  try { const value = localStorage.getItem(RESUME_TEMPLATE_KEY); return RESUME_TEMPLATES.includes(value) ? value : 'minimal'; }
  catch { return 'minimal'; }
}
state.resumeTemplate = initialResumeTemplate();
state.skills = [];
state.experiences = [];

function fillResumeForm(resume) {
  if (!resume) return;
  state.activeResumeId = resume.id;
  $('#resume-id').value = resume.id; $('#owner-name').value = resume.ownerName || '';
  $('#target-role').value = resume.targetRole || ''; $('#city').value = resume.city || '';
  $('#graduation-year').value = resume.graduationYear || ''; $('#phone').value = resume.phone || '';
  $('#email').value = resume.email || ''; $('#education').value = resume.education || '本科';
  $('#school').value = resume.school || ''; $('#major').value = resume.major || '';
  $('#self-evaluation').value = resume.selfEvaluation || '';
  state.skills = uniqueSkills(resume.skills || []);
  state.projects = (resume.projects || []).map(item => ({...item}));
  state.experiences = (resume.experiences || []).map(item => ({...item}));
  renderSkillPicker(); renderProjectEditor(); renderExperienceEditor(); updateResumePreview();
  loadVersions(); loadFactLocks(); resetEducationVerification();
}
function emptyResume() {
  $('#resume-form').reset();
  state.activeResumeId = null; state.skills = [];
  state.projects = [blankProject()]; state.experiences = [];
  $('#skills').value = ''; $('#education').value = '本科';
  renderSkillPicker(); renderProjectEditor(); renderExperienceEditor(); updateResumePreview();
  const vbox = $('#resume-versions'); if (vbox) vbox.innerHTML = '<div class="empty-mini">保存后会在这里生成版本快照</div>';
  $$('#fact-locks input').forEach(box => { box.checked = false; });
  resetEducationVerification();
  $('#owner-name').focus();
}
function renderSkillPicker() {
  const selected = new Set(state.skills);
  $$('#skill-picker [data-skill]').forEach(button => {
    const active = selected.has(button.dataset.skill);
    button.classList.toggle('active', active);
    button.setAttribute('aria-pressed', String(active));
  });
  $('#skills').value = state.skills.join(', ');
  $('#skill-count').textContent = state.skills.length;
  $('#selected-skills').innerHTML = state.skills.length
    ? state.skills.map((skill,index) => `<button type="button" class="selected-skill" data-remove-skill="${index}">${escapeHtml(skill)}<span>×</span></button>`).join('')
    : '<span class="selected-skill-empty">还未选择技能，可从上方技术分类中点选。</span>';
}
function toggleSkill(skill) {
  const value = String(skill || '').trim(); if (!value) return;
  const index = state.skills.findIndex(item => item.toLowerCase() === value.toLowerCase());
  if (index >= 0) state.skills.splice(index,1); else state.skills.push(value);
  renderSkillPicker(); updateResumePreview();
}
function addCustomSkill() {
  const input = $('#custom-skill-input');
  const values = uniqueSkills(input.value.split(/[,，、]/));
  if (!values.length) { toast('请输入技能','例如 Unity、Flutter 或 Kubernetes',true); return; }
  values.forEach(value => { if (!state.skills.some(item => item.toLowerCase() === value.toLowerCase())) state.skills.push(value); });
  input.value = ''; renderSkillPicker(); updateResumePreview(); input.focus();
}
function renderProjectEditor() {
  const box = $('#project-editor');
  if (!state.projects.length) state.projects.push(blankProject());
  box.innerHTML = state.projects.map((project,index) => `<div class="project-card" data-index="${index}">
    <div class="project-card-head"><div><strong>项目 ${index+1}</strong><small>STAR / 量化结果</small></div><button type="button" class="remove-row" data-remove-project="${index}" title="删除">×</button></div>
    <div class="project-fields">
      <label class="project-field"><span>项目名称</span><input class="project-name" value="${escapeHtml(project.name)}" placeholder="校园二手交易平台"></label>
      <label class="project-field"><span>担任角色</span><input class="project-role" value="${escapeHtml(project.role)}" placeholder="后端负责人"></label>
      <label class="project-field wide"><span>技术栈</span><input class="project-tech" value="${escapeHtml(project.techStack)}" placeholder="Spring Boot / MySQL / Redis"></label>
      <label class="project-field wide"><span>项目描述与个人职责</span><textarea class="project-description" rows="2" placeholder="负责订单、鉴权与缓存模块，完成接口设计与性能优化。">${escapeHtml(project.description)}</textarea></label>
      <label class="project-field wide"><span>成果亮点（尽量量化）</span><input class="project-highlight" value="${escapeHtml(project.highlight)}" placeholder="响应时间降低 60%，支撑 3000+ 用户"></label>
    </div></div>`).join('');
}
function readProjectDrafts() {
  return $$('#project-editor .project-card').map(card => ({
    name: $('.project-name', card).value.trim(), role: $('.project-role', card).value.trim(),
    techStack: $('.project-tech', card).value.trim(), description: $('.project-description', card).value.trim(),
    highlight: $('.project-highlight', card).value.trim()
  }));
}
function collectProjects() { return readProjectDrafts().filter(item => item.name); }
function renderExperienceEditor() {
  const box = $('#experience-editor');
  if (!state.experiences.length) state.experiences.push(blankExperience());
  box.innerHTML = state.experiences.map((experience,index) => `<div class="project-card experience-card" data-index="${index}">
    <div class="project-card-head"><div><strong>经历 ${index+1}</strong><small>实习 / 工作 / 校园实践</small></div><button type="button" class="remove-row" data-remove-experience="${index}" title="删除">×</button></div>
    <div class="project-fields">
      <label class="project-field"><span>公司 / 组织</span><input class="experience-company" value="${escapeHtml(experience.company)}" placeholder="科技有限公司"></label>
      <label class="project-field"><span>职位</span><input class="experience-position" value="${escapeHtml(experience.position)}" placeholder="后端开发实习生"></label>
      <label class="project-field wide"><span>时间</span><input class="experience-period" value="${escapeHtml(experience.period)}" placeholder="2025.03 - 2025.08"></label>
      <label class="project-field wide"><span>工作内容</span><textarea class="experience-description" rows="2" placeholder="参与...负责...协作...">${escapeHtml(experience.description)}</textarea></label>
      <label class="project-field wide"><span>成果亮点</span><input class="experience-achievement" value="${escapeHtml(experience.achievement)}" placeholder="接口平均响应时间从 800ms 降至 220ms"></label>
    </div></div>`).join('');
}
function readExperienceDrafts() {
  return $$('#experience-editor .experience-card').map(card => ({
    company: $('.experience-company', card).value.trim(), position: $('.experience-position', card).value.trim(),
    period: $('.experience-period', card).value.trim(), description: $('.experience-description', card).value.trim(),
    achievement: $('.experience-achievement', card).value.trim()
  }));
}
function collectExperiences() { return readExperienceDrafts().filter(item => item.company || item.position || item.description || item.achievement); }
function buildResumeDraft() {
  return {
    name: $('#owner-name').value.trim(), targetRole: $('#target-role').value.trim(), city: $('#city').value.trim(),
    phone: $('#phone').value.trim(), email: $('#email').value.trim(), education: $('#education').value,
    school: $('#school').value.trim(), major: $('#major').value.trim(),
    graduationYear: Number($('#graduation-year').value) || null, skills: [...state.skills],
    projects: collectProjects(), experiences: collectExperiences(), selfEvaluation: $('#self-evaluation').value.trim()
  };
}
function collectResumePayload() {
  const draft = buildResumeDraft();
  const rawText = [
    draft.name, draft.targetRole, draft.school, draft.major, draft.education, draft.skills.join(' '),
    ...draft.projects.map(item => [item.name,item.role,item.techStack,item.description,item.highlight].filter(Boolean).join(' ')),
    ...draft.experiences.map(item => [item.company,item.position,item.period,item.description,item.achievement].filter(Boolean).join(' ')),
    draft.selfEvaluation
  ].filter(Boolean).join(' / ');
  return {
    ownerName: draft.name, targetRole: draft.targetRole, city: draft.city, phone: draft.phone, email: draft.email,
    education: draft.education, school: draft.school, major: draft.major, graduationYear: draft.graduationYear,
    skills: draft.skills, projects: draft.projects, experiences: draft.experiences,
    selfEvaluation: draft.selfEvaluation, rawText
  };
}
function resumeText(value, fallback='') { return escapeHtml(value || fallback); }
function multilineText(value, fallback='') { return escapeHtml(value || fallback).replace(/\n/g, '<br>'); }
function resumeContacts(draft, compact=false) {
  const items = [
    draft.city ? `期望城市：${escapeHtml(draft.city)}` : '',
    draft.phone ? `电话：${escapeHtml(draft.phone)}` : '',
    draft.email ? `邮箱：${escapeHtml(draft.email)}` : ''
  ].filter(Boolean);
  if (!items.length) return '<span class="resume-placeholder-inline">补充城市、手机和邮箱</span>';
  return items.map(item => `<span>${item}</span>`).join(compact ? '' : '');
}
function renderEducationSection(draft, title='教育背景') {
  return `<section class="resume-section"><h2 class="resume-section-title">${title}</h2><div class="resume-section-content"><div class="resume-entry">
    <div class="resume-entry-head"><div><strong>${resumeText(draft.school,'学校名称待补充')}</strong><p>${resumeText(draft.education,'学历待补充')} · ${resumeText(draft.major,'专业待补充')}</p></div><span class="resume-entry-meta">${draft.graduationYear ? `${draft.graduationYear} 届` : '毕业年份待补充'}</span></div>
  </div></div></section>`;
}
function renderSkillsSection(draft, title='专业技能') {
  const tags = draft.skills.length
    ? draft.skills.map(skill => `<span>${escapeHtml(skill)}</span>`).join('')
    : '<span class="resume-placeholder-tag">待补充技能</span>';
  return `<section class="resume-section"><h2 class="resume-section-title">${title}</h2><div class="resume-skill-list">${tags}</div></section>`;
}
function renderProjectsSection(draft, title='项目经历') {
  const content = draft.projects.length ? draft.projects.map(project => `<div class="resume-entry">
    <div class="resume-entry-head"><div><strong>${resumeText(project.name)}</strong>${project.role ? `<p>${resumeText(project.role)}</p>` : ''}</div>${project.techStack ? `<span class="resume-entry-meta">${resumeText(project.techStack)}</span>` : ''}</div>
    ${project.description ? `<p class="resume-entry-body">${multilineText(project.description)}</p>` : ''}
    ${project.highlight ? `<p class="resume-highlight">${multilineText(project.highlight)}</p>` : ''}
  </div>`).join('') : '<p class="resume-placeholder">补充项目经历，让招聘方看到你的技术落地能力。</p>';
  return `<section class="resume-section"><h2 class="resume-section-title">${title}</h2><div class="resume-section-content">${content}</div></section>`;
}
function renderExperienceSection(draft, title='实习 / 工作经历') {
  const content = draft.experiences.length ? draft.experiences.map(experience => `<div class="resume-entry">
    <div class="resume-entry-head"><div><strong>${resumeText(experience.company,'组织名称')}</strong><p>${resumeText(experience.position,'职位待补充')}</p></div><span class="resume-entry-meta">${resumeText(experience.period,'时间待补充')}</span></div>
    ${experience.description ? `<p class="resume-entry-body">${multilineText(experience.description)}</p>` : ''}
    ${experience.achievement ? `<p class="resume-highlight">${multilineText(experience.achievement)}</p>` : ''}
  </div>`).join('') : '<p class="resume-placeholder">还没有实习或工作经历，可先填写课程实践、校园项目或开源协作。</p>';
  return `<section class="resume-section"><h2 class="resume-section-title">${title}</h2><div class="resume-section-content">${content}</div></section>`;
}
function renderSummarySection(draft, title='个人总结') {
  return `<section class="resume-section"><h2 class="resume-section-title">${title}</h2><div class="resume-section-content"><p class="resume-summary">${multilineText(draft.selfEvaluation,'补充你的方向、优势与职业目标。')}</p></div></section>`;
}
function renderMinimalResume(draft) {
  return `<header class="resume-doc-header"><div><h1>${resumeText(draft.name,'你的姓名')}</h1><p class="resume-doc-role">${resumeText(draft.targetRole,'目标岗位')}</p></div><div class="resume-doc-contacts">${resumeContacts(draft)}</div></header>
    <div class="resume-accent"></div><div class="resume-minimal-columns"><main class="resume-minimal-main">${renderProjectsSection(draft)}${renderExperienceSection(draft)}</main><aside class="resume-minimal-side">${renderEducationSection(draft)}${renderSkillsSection(draft)}${renderSummarySection(draft)}</aside></div>`;
}
function renderFormalResume(draft) {
  return `<div class="resume-formal-frame"><aside class="resume-formal-sidebar"><div class="resume-monogram">${resumeText((draft.name || '简').slice(0,1))}</div><h1>${resumeText(draft.name,'你的姓名')}</h1><p class="resume-doc-role">${resumeText(draft.targetRole,'目标岗位')}</p><div class="resume-contact-stack">${resumeContacts(draft,true)}</div>${renderSkillsSection(draft)}${renderSummarySection(draft)}</aside><main class="resume-formal-main">${renderEducationSection(draft)}${renderProjectsSection(draft)}${renderExperienceSection(draft)}</main></div>`;
}
function renderEnglishResume(draft) {
  return `<header class="resume-doc-header english-header"><div><span class="english-kicker">CURRICULUM VITAE</span><h1>${resumeText(draft.name,'YOUR NAME')}</h1><p class="resume-doc-role">${resumeText(draft.targetRole,'TARGET POSITION')}</p></div><div class="resume-doc-contacts">${resumeContacts(draft)}</div></header><div class="resume-english-rule"></div><div class="resume-english-layout"><main>${renderExperienceSection(draft,'EXPERIENCE / 实习与工作')}${renderProjectsSection(draft,'PROJECTS / 项目经历')}</main><aside>${renderEducationSection(draft,'EDUCATION / 教育背景')}${renderSkillsSection(draft,'SKILLS / 专业技能')}${renderSummarySection(draft,'PROFILE / 个人总结')}</aside></div>`;
}
function renderResumeDocument(draft) {
  if (state.resumeTemplate === 'formal') return renderFormalResume(draft);
  if (state.resumeTemplate === 'english') return renderEnglishResume(draft);
  return renderMinimalResume(draft);
}
function syncResumeTemplateButtons() {
  $$('#resume-template-switcher [data-resume-template]').forEach(button => button.classList.toggle('active', button.dataset.resumeTemplate === state.resumeTemplate));
}
function setResumeTemplate(template) {
  if (!RESUME_TEMPLATES.includes(template)) return;
  state.resumeTemplate = template;
  try { localStorage.setItem(RESUME_TEMPLATE_KEY, template); } catch { /* local preview preference only */ }
  syncResumeTemplateButtons(); updateResumePreview();
}
function updateResumeCompletion(draft) {
  const checks = [draft.name, draft.targetRole, draft.city, draft.phone || draft.email, draft.school, draft.major, draft.skills.length, draft.projects.length, draft.experiences.length, draft.selfEvaluation];
  const percent = Math.round(checks.filter(Boolean).length / checks.length * 100);
  $('#resume-completion-text').textContent = `完成度 ${percent}%`;
  $('#resume-completion-bar').style.width = `${percent}%`;
}
function fitResumePreview() {
  const viewport = $('#resume-preview-viewport'); const paper = $('#resume-preview');
  if (!viewport || !paper) return;
  const availableWidth = Math.max(320, viewport.clientWidth - 2);
  paper.style.transform = 'scale(1)';
  const naturalHeight = paper.scrollHeight;
  const scale = Math.min(1, availableWidth / 794);
  paper.style.transform = `scale(${scale})`;
  viewport.style.height = `${Math.max(520, Math.ceil(naturalHeight * scale))}px`;
}
function updateResumePreview() {
  const draft = buildResumeDraft(); const paper = $('#resume-preview');
  if (!paper) return;
  paper.className = `resume-paper template-${state.resumeTemplate}`;
  paper.innerHTML = renderResumeDocument(draft);
  updateResumeCompletion(draft); syncResumeTemplateButtons();
  requestAnimationFrame(fitResumePreview);
}
async function saveResume() {
  if (!$('#owner-name').value.trim() || !$('#target-role').value.trim()) { toast('信息不完整','姓名和目标岗位不能为空',true); return; }
  const id = $('#resume-id').value; const payload = collectResumePayload();
  try {
    const saved = id ? await put(`/api/resumes/${id}`, payload) : await post('/api/resumes', payload);
    const index = state.resumes.findIndex(item => item.id === saved.id);
    if (index >= 0) state.resumes[index] = saved; else state.resumes.unshift(saved);
    state.activeResumeId = saved.id; fillResumeForm(saved); populateAllSelects(); await refreshDashboard();
    toast('保存成功', '简历已更新，岗位匹配与诊断将使用最新内容');
    return saved;
  } catch (error) { toast('保存失败', error.message, true); return null; }
}
/* ---------- 学历材料核验 ---------- */
function resetEducationVerification() {
  const badge = $('#education-verify-status');
  if (badge) { badge.className = 'edu-verify-badge'; badge.textContent = '未核验'; }
  const result = $('#education-proof-result');
  if (result) result.innerHTML = '';
  const button = $('#upload-education-proof');
  if (button) button.disabled = false;
}
function setEducationVerificationStatus(label, mode='') {
  const badge = $('#education-verify-status');
  if (!badge) return;
  badge.className = `edu-verify-badge ${mode}`.trim();
  badge.textContent = label;
}
async function extractEducationImageText(file) {
  if (!window.Tesseract) {
    throw new Error('图片 OCR 组件加载失败，请检查网络，或直接上传可复制文字的 PDF 报告');
  }
  setEducationVerificationStatus('正在识别图片', 'loading');
  const result = await Tesseract.recognize(file, 'chi_sim+eng', {
    logger: message => {
      if (message.status === 'recognizing text') {
        setEducationVerificationStatus(`图片识别 ${Math.round((message.progress || 0) * 100)}%`, 'loading');
      }
    }
  });
  return (result?.data?.text || '').trim();
}
async function runEducationVerification(file) {
  if (!file) return;
  if (file.size > 5 * 1024 * 1024) { toast('文件过大', '学历证明材料不能超过 5MB', true); return; }
  const button = $('#upload-education-proof');
  const resultBox = $('#education-proof-result');
  try {
    if (button) button.disabled = true;
    const saved = await saveResume();
    if (!saved) return;
    if (button) button.disabled = true;
    const isImage = /\.(png|jpe?g|webp)$/i.test(file.name);
    let ocrText = '';
    setEducationVerificationStatus('正在准备材料', 'loading');
    if (resultBox) resultBox.innerHTML = '<div class="edu-verify-loading"><i></i><span>Agent 正在解析学历材料并逐项比对简历...</span></div>';
    if (isImage) ocrText = await extractEducationImageText(file);
    const form = new FormData();
    form.append('file', file);
    if (ocrText) form.append('ocrText', ocrText);
    const response = await fetch(`/api/resumes/${saved.id}/education-verification`, { method: 'POST', body: form });
    let payload;
    try { payload = await response.json(); } catch { throw new Error(`服务返回异常 (${response.status})`); }
    if (!response.ok || payload.success === false) throw new Error(payload.message || `核验失败 (${response.status})`);
    renderEducationVerification(payload.data);
  } catch (error) {
    setEducationVerificationStatus('核验失败', 'unknown');
    if (resultBox) resultBox.innerHTML = `<div class="edu-verify-error"><strong>未能完成核验</strong><p>${escapeHtml(error.message)}</p></div>`;
    toast('学历核验失败', error.message, true);
  } finally {
    if (button) button.disabled = false;
    const input = $('#education-proof-file'); if (input) input.value = '';
  }
}
function renderEducationVerification(report) {
  const mode = report.status === 'CONSISTENT' ? 'consistent'
    : report.status === 'MISMATCH' ? 'mismatch'
    : report.status === 'NEEDS_REVIEW' ? 'review' : 'unknown';
  setEducationVerificationStatus(report.statusLabel, mode);
  const checks = (report.checks || []).map(item => {
    const cls = item.status === 'MATCH' ? 'ok' : item.status === 'MISMATCH' ? 'bad' : 'unknown';
    const label = item.status === 'MATCH' ? '一致' : item.status === 'MISMATCH' ? '不一致' : '无法判断';
    return `<div class="edu-check-row ${cls}">
      <div class="edu-check-name">${escapeHtml(item.label)}<small>${label}</small></div>
      <div class="edu-check-values"><span><b>简历</b>${escapeHtml(item.resumeValue || '未填写')}</span><span><b>材料</b>${escapeHtml(item.proofValue || '未识别')}</span></div>
      <p>${escapeHtml(item.detail || '')}</p>
    </div>`;
  }).join('');
  const warnings = (report.warnings || []).map(item => `<li>${escapeHtml(item)}</li>`).join('');
  const resultBox = $('#education-proof-result');
  if (!resultBox) return;
  resultBox.innerHTML = `<div class="edu-result-head ${mode}">
      <div class="edu-result-score"><strong>${report.score}</strong><span>一致性</span></div>
      <div class="edu-result-main"><div class="edu-result-title"><h4>${escapeHtml(report.statusLabel)}</h4><span>置信度 ${report.confidence}%</span></div>
        <p>${escapeHtml(report.summary)}</p>
        <div class="edu-result-meta"><span>${escapeHtml(report.reportType || '学历材料')}</span><span>${report.officialReportDetected ? '检测到学信网标识' : '未检测到官方报告标识'}</span><span>${report.extractionMode === 'LLM_ASSISTED' ? 'Agent 辅助抽取' : '规则抽取'}</span><span>凭证 ${escapeHtml(report.proofHash)}</span></div>
      </div>
    </div>
    <div class="edu-check-grid">${checks}</div>
    <details class="edu-warning-list"><summary>核验边界与注意事项</summary><ul>${warnings}</ul></details>`;
}
/* ---------- ATS 体检 ---------- */
function openAts() {
  const modal = $('#ats-modal');
  if (!modal) return;
  modal.classList.add('open'); modal.setAttribute('aria-hidden', 'false');
  const jobs = (state.recommendations || []).slice(0, 40)
    .map(r => `<option value="${r.job.id}">${escapeHtml(r.job.title)}</option>`).join('');
  $('#ats-body').innerHTML = `
    <span class="eyebrow">ATS COMPATIBILITY CHECK</span>
    <h2>ATS 体检</h2>
    <p class="gd-sub">上传 PDF / DOCX 简历，检查机器可读性、结构完整性与岗位关键词覆盖。</p>
    <div class="ats-controls">
      <label class="ats-file"><input type="file" id="ats-file" accept=".pdf,.docx,.txt,.md"><span>选择简历文件（≤5MB）</span></label>
      <select id="ats-job"><option value="">不绑定岗位</option>${jobs}</select>
      <button type="button" class="primary-button" id="ats-run">开始检查</button>
    </div>
    <div class="ats-note">说明：ATS 没有公开的统一算法，本检查依据单栏纯文本、标准区块、关键词覆盖等最佳实践给出风险提示。</div>
    <div id="ats-result"></div>`;
  $('#ats-file').addEventListener('change', event => {
    const f = event.target.files[0];
    const label = event.target.parentElement.querySelector('span');
    if (f) label.textContent = f.name;
  });
  $('#ats-run').addEventListener('click', runAts);
}
async function runAts() {
  const input = $('#ats-file');
  if (!input || !input.files.length) { toast('请选择文件', '支持 PDF / DOCX / TXT', true); return; }
  const form = new FormData();
  form.append('file', input.files[0]);
  const jobId = $('#ats-job').value;
  if (jobId) form.append('jobId', jobId);
  const box = $('#ats-result');
  box.innerHTML = '<div class="loading-card"><i></i>正在解析简历并检查 ATS 兼容性...</div>';
  try {
    const response = await fetch('/api/ats/check', { method: 'POST', body: form });
    const payload = await response.json();
    if (!response.ok || payload.success === false) throw new Error(payload.message || '检查失败');
    renderAts(payload.data);
  } catch (error) {
    box.innerHTML = `<div class="gd-error">${escapeHtml(error.message)}</div>`;
  }
}
function renderAts(report) {
  const b = report.breakdown || {};
  const bar = (label, value, max) => `<div class="gd-bar"><span>${label}</span><div><i style="width:${Math.round(100 * (value || 0) / max)}%"></i></div><b>${value || 0}/${max}</b></div>`;
  const issues = (report.issues || []).map(x => `
    <div class="ats-issue ${String(x.level).toLowerCase()}">
      <span class="ats-level">${escapeHtml(x.level)}</span>
      <div><b>${escapeHtml(x.category)} · ${escapeHtml(x.issue)}</b><p>${escapeHtml(x.action)}</p></div>
    </div>`).join('') || '<div class="gd-ok">没有发现明显问题</div>';
  const p = report.parsed || {};
  const sections = (p.sectionsFound || []).map(s => `<span class="ats-chip ok">${escapeHtml(s)}</span>`).join('');
  const matched = (p.matchedKeywords || []).map(s => `<span class="ats-chip ok">${escapeHtml(s)}</span>`).join('');
  const missing = (p.missingKeywords || []).map(s => `<span class="ats-chip miss">${escapeHtml(s)}</span>`).join('');
  state.atsReport = report;
  $('#ats-result').innerHTML = `
    <div class="ats-result-head">
      <h3>检查结果</h3>
      <button type="button" class="ghost-button small" id="ats-export">导出 PDF 报告</button>
    </div>
    <div class="ats-score-row">
      <div class="ats-ring" style="--score:${report.atsScore}"><div><strong>${report.atsScore}</strong><span>ATS 分</span></div></div>
      <div class="ats-score-main">
        <h3>${escapeHtml(report.level)}</h3>
        <div class="ats-meta">${escapeHtml(p.fileType || '')} · ${p.pages || 0} 页 · ${p.textLength || 0} 字符 · 表格 ${p.tables || 0} · 图片 ${p.pictures || 0}</div>
        ${bar('格式可解析', b.parseability, 30)}
        ${bar('结构完整性', b.structure, 25)}
        ${bar('关键词匹配', b.keywords, 30)}
        ${bar('内容质量', b.content, 15)}
      </div>
    </div>
    <div class="ats-section"><h4>识别到的区块</h4><div class="ats-chips">${sections || '<span class="ats-chip miss">未识别到标准区块</span>'}</div></div>
    ${p.keywordEvaluated ? `<div class="ats-section"><h4>岗位关键词（覆盖 ${p.keywordCoverage || 0}%）</h4><div class="ats-chips">${matched}${missing || '<span class="gd-ok">全部覆盖</span>'}</div></div>` : ''}
    <div class="ats-section"><h4>问题与建议</h4><div class="ats-issues">${issues}</div></div>`;
  $('#ats-export').addEventListener('click', exportAtsPdf);
}
async function exportAtsPdf() {
  if (!state.atsReport) return;
  try {
    const response = await fetch('/api/ats/report', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(state.atsReport)
    });
    if (!response.ok) throw new Error('服务返回 ' + response.status);
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `ATS体检报告-${new Date().toISOString().slice(0, 10)}.pdf`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
    toast('已导出', 'PDF 报告已开始下载');
  } catch (error) {
    toast('导出失败', error.message, true);
  }
}
function closeAts() {
  const modal = $('#ats-modal');
  if (!modal) return;
  modal.classList.remove('open');
  modal.setAttribute('aria-hidden', 'true');
}

/* ---------- 引导式简历编写 ---------- */
function defaultGuidanceRole() {
  return ($('#target-role')?.value || '').trim() || 'Java后端';
}
async function openGuidance() {
  const id = currentResumeId();
  if (!id) { toast('无法进入引导', '请先保存一次简历', true); return; }
  const modal = $('#guidance-modal');
  modal.classList.add('open'); modal.setAttribute('aria-hidden', 'false');
  $('#guidance-body').innerHTML = '<div class="loading-card"><i></i>正在读取方向画像与简历证据...</div>';
  try {
    const role = defaultGuidanceRole();
    const [directions, report] = await Promise.all([
      api('/api/resume-guidance/directions'),
      api(`/api/resume-guidance/${id}?role=${encodeURIComponent(role)}`)
    ]);
    renderGuidance(directions, report);
  } catch (error) {
    $('#guidance-body').innerHTML = `<div class="loading-card">加载失败：${escapeHtml(error.message)}</div>`;
  }
}
function renderGuidance(directions, report) {
  const profile = report.profile || {};
  const matched = new Set(report.matchedSkills || []);
  const roleKey = String(report.role || '').toLowerCase();
  const opts = directions.map(d => `<option value="${escapeHtml(d)}" ${d.toLowerCase() === roleKey ? 'selected' : ''}>${escapeHtml(d)}</option>`).join('');
  const skills = (profile.topSkills || []).map(s => {
    const hit = matched.has(s.skill) ? 'hit' : '';
    return `<span class="gd-skill ${hit}">${escapeHtml(s.skill)}<b>${s.percent}%</b></span>`;
  }).join('');
  const q = report.quality || {};
  const bar = (label, value, max) => `<div class="gd-bar"><span>${label}</span><div><i style="width:${Math.round(100 * (value || 0) / max)}%"></i></div><b>${value || 0}/${max}</b></div>`;
  const noEvidence = (report.evidences || []).filter(e => !e.evidenced);
  const evis = noEvidence.length
    ? noEvidence.map(e => `<span class="gd-evi">${escapeHtml(e.skill)}</span>`).join('')
    : '<span class="gd-ok">所有技能都能在项目或经历中找到证据</span>';
  const questions = (report.questions || []).map(item => `
    <label class="gd-q"><span>${escapeHtml(item.label)}</span>
      <textarea data-guidance-key="${item.key}" rows="2" placeholder="${escapeHtml(item.hint)}"></textarea>
      <small>示例：${escapeHtml(item.example)}</small>
    </label>`).join('');

  $('#guidance-body').innerHTML = `
    <span class="eyebrow">GUIDED RESUME BUILDER</span>
    <h2>引导式编写</h2>
    <p class="gd-sub">先看目标方向真正看重什么，再逐项补上证据和量化结果。</p>

    <div class="gd-section">
      <div class="gd-section-head"><h3>1 · 目标方向画像</h3><select id="guidance-role">${opts}</select></div>
      <div class="gd-meta ${profile.jobCount ? '' : 'warn'}">基于 <b>${profile.jobCount || 0}</b> 条「${escapeHtml(profile.role || '')}」岗位统计${profile.jobCount ? ' · 带底色的技能表示你已覆盖' : ' · 该方向暂无样本，请从右上角下拉中选择一个方向'}</div>
      <div class="gd-skills">${skills}</div>
    </div>

    <div class="gd-section">
      <div class="gd-section-head"><h3>2 · 质量完成度</h3><strong class="gd-total">${q.total || 0}<small>/100</small></strong></div>
      ${bar('字段完整性', q.completeness, 30)}
      ${bar('方向技能覆盖', q.coverage, 30)}
      ${bar('项目证据', q.evidence, 25)}
      ${bar('量化结果', q.quantified, 15)}
      <ul class="gd-suggest">${(q.suggestions || []).map(s => `<li>${escapeHtml(s)}</li>`).join('')}</ul>
    </div>

    <div class="gd-section">
      <div class="gd-section-head"><h3>3 · 技能证据检查</h3><span class="gd-count">${noEvidence.length} 项缺证据</span></div>
      <div class="gd-evis">${evis}</div>
    </div>

    <div class="gd-section">
      <div class="gd-section-head"><h3>4 · 项目经历引导</h3>
        <label class="gd-ai"><input type="checkbox" id="guidance-ai" checked> AI 润色（不新增事实）</label></div>
      <label class="gd-q"><span>项目名称</span><input id="guidance-project-name" placeholder="例如：校园二手交易平台"></label>
      ${questions}
      <div class="gd-actions">
        <button type="button" class="primary-button" id="generate-draft">生成项目描述</button>
        <button type="button" class="ghost-button" id="save-draft-version">保存为草稿版本</button>
      </div>
      <div id="guidance-result"></div>
    </div>`;

  $('#guidance-role').addEventListener('change', event => reloadGuidance(event.target.value));
  $('#generate-draft').addEventListener('click', generateProjectDraft);
  $('#save-draft-version').addEventListener('click', saveDraftVersion);
}
async function reloadGuidance(role) {
  const id = currentResumeId();
  try {
    const report = await api(`/api/resume-guidance/${id}?role=${encodeURIComponent(role)}`);
    const options = [...$('#guidance-role').options].map(o => o.value);
    renderGuidance(options, report);
  } catch (error) { toast('加载失败', error.message, true); }
}
async function generateProjectDraft() {
  const role = $('#guidance-role').value;
  const body = { role, useAi: $('#guidance-ai').checked };
  $$('[data-guidance-key]').forEach(el => { body[el.dataset.guidanceKey] = el.value.trim(); });
  if (!body.background && !body.responsibility) { toast('内容太少', '至少填写「背景」或「职责」', true); return; }
  const box = $('#guidance-result');
  box.innerHTML = '<div class="loading-card"><i></i>正在生成项目描述...</div>';
  try {
    const draft = await post('/api/resume-guidance/draft', body);
    state.guidanceDraft = draft;
    state.guidanceBody = body;
    box.innerHTML = `<div class="gd-result">
      <div class="gd-result-head"><b>${escapeHtml(draft.mode)}</b><span>${(draft.facts || []).length} 条事实</span></div>
      <p>${escapeHtml(draft.description)}</p>
      ${draft.highlight ? `<p class="gd-highlight">亮点：${escapeHtml(draft.highlight)}</p>` : ''}
      <button type="button" class="primary-button small" id="apply-draft">应用到项目经历</button>
    </div>`;
    $('#apply-draft').addEventListener('click', applyProjectDraft);
  } catch (error) {
    box.innerHTML = `<div class="gd-error">${escapeHtml(error.message)}</div>`;
  }
}
function applyProjectDraft() {
  const draft = state.guidanceDraft;
  if (!draft) return;
  const name = ($('#guidance-project-name')?.value || '').trim() || '新增项目';
  state.projects.push({
    name,
    role: '项目负责人',
    techStack: (state.guidanceBody && state.guidanceBody.techStack) || '',
    description: draft.description || '',
    highlight: draft.highlight || ''
  });
  renderProjectEditor();
  updateResumePreview();
  toast('已加入项目经历', '记得点「保存简历」保存，或点「保存为草稿版本」留档');
}
async function saveDraftVersion() {
  const saved = await saveResume();
  if (!saved) return;
  try {
    const version = await post(`/api/resumes/${saved.id}/versions`, {
      label: '草稿', targetJobId: state.activeJobId || null, note: '引导式编写草稿'
    });
    toast('草稿已保存', `v${version.versionNo} · 草稿`);
    await loadVersions();
  } catch (error) { toast('保存草稿失败', error.message, true); }
}
function closeGuidance() {
  const modal = $('#guidance-modal');
  if (!modal) return;
  modal.classList.remove('open');
  modal.setAttribute('aria-hidden', 'true');
}

/* ---------- 简历版本管理 + 事实锁 ---------- */
function currentResumeId() {
  return Number($('#resume-id').value || state.activeResumeId) || null;
}
async function loadVersions() {
  const id = currentResumeId(); const box = $('#resume-versions');
  if (!box) return;
  if (!id) { box.innerHTML = '<div class="empty-mini">保存简历后可创建版本</div>'; return; }
  try {
    const rows = await api(`/api/resumes/${id}/versions`);
    if (!rows.length) { box.innerHTML = '<div class="empty-mini">还没有版本，点右上角「保存版本」创建第一个快照</div>'; return; }
    box.innerHTML = rows.map(v => {
      const score = v.matchScore == null ? '' : `<span class="version-score">${v.matchScore} 分</span>`;
      const job = v.targetJobTitle ? `<span class="version-job">${escapeHtml(v.targetJobTitle)}</span>` : '<span class="version-job muted">未绑定岗位</span>';
      const date = String(v.createdAt || '').replace('T', ' ').slice(0, 16);
      return `<div class="version-item">
        <div class="version-no">v${v.versionNo}</div>
        <div class="version-main">
          <div class="version-title">${escapeHtml(v.label || '未命名')} ${score}</div>
          <div class="version-meta">${date} · ${job}</div>
        </div>
        <button type="button" class="ghost-button small" data-restore-version="${v.id}" data-version-no="${v.versionNo}">恢复</button>
      </div>`;
    }).join('');
  } catch (error) {
    box.innerHTML = `<div class="empty-mini">版本读取失败：${escapeHtml(error.message)}</div>`;
  }
}
async function saveVersion() {
  const id = currentResumeId();
  if (!id) { toast('无法保存版本', '请先保存一次简历', true); return; }
  const label = $('#version-label')?.value || '母版';
  const jobId = Number($('#diagnosis-job')?.value || state.activeJobId) || null;
  try {
    const v = await post(`/api/resumes/${id}/versions`, { label, targetJobId: jobId, note: '' });
    const extra = v.matchScore == null ? '' : ` · 匹配 ${v.matchScore} 分`;
    toast('版本已保存', `v${v.versionNo} · ${v.label}${extra}`);
    await loadVersions();
  } catch (error) { toast('保存版本失败', error.message, true); }
}
async function restoreVersion(versionId, versionNo) {
  const id = currentResumeId();
  if (!id) return;
  if (!window.confirm(`确定恢复到 v${versionNo}？当前未保存的修改会丢失。`)) return;
  try {
    const saved = await post(`/api/resumes/${id}/versions/${versionId}/restore`, {});
    const index = state.resumes.findIndex(item => item.id === saved.id);
    if (index >= 0) state.resumes[index] = saved;
    fillResumeForm(saved);
    toast('已恢复', `当前简历已回到 v${versionNo}`);
  } catch (error) { toast('恢复失败', error.message, true); }
}
async function loadFactLocks() {
  const id = currentResumeId(); const box = $('#fact-locks');
  if (!box) return;
  if (!id) return;
  try {
    const fields = await api(`/api/resumes/${id}/fact-locks`);
    const set = new Set(fields);
    $$('#fact-locks input').forEach(box => { box.checked = set.has(box.value); });
  } catch (error) { /* 事实锁读取失败不阻塞主流程 */ }
}
async function saveFactLocks() {
  const id = currentResumeId();
  if (!id) { toast('无法设置事实锁', '请先保存简历', true); return; }
  const fields = $$('#fact-locks input').filter(box => box.checked).map(box => box.value);
  try {
    await put(`/api/resumes/${id}/fact-locks`, { fields });
    toast('事实锁已更新', fields.length ? `已锁定 ${fields.length} 个字段` : '已全部解锁');
  } catch (error) { toast('设置失败', error.message, true); }
}

async function refreshDashboard() { state.dashboard = await api('/api/dashboard'); renderDashboard(state.dashboard); }/* jobs */
async function loadRecommendations() {
  if (!state.activeResumeId) { state.recommendations = []; return; }
  const params = new URLSearchParams({
    resumeId: state.activeResumeId,
    limit: '20',
    excludedCities: state.excludedCities.join(','),
    category: $('#job-category-filter')?.value || '',
    experience: $('#job-experience-filter')?.value || '',
    education: $('#job-education-filter')?.value || '',
    salaryMin: $('#job-salary-filter')?.value || '0'
  });
  const requestId = ++state.recommendationRequestId;
  const data = await api(`/api/jobs/recommend?${params.toString()}`);
  if (requestId === state.recommendationRequestId) {
    state.recommendations = data;
    state.agentMatches = new Map();
    state.agentLoading = false;
    state.agentError = '';
    updateAgentMatchNote();
    void loadAgentScores(requestId);
  }
}
function recommendationForJob(jobId) {
  return state.recommendations.find(item => Number(item.job?.id) === Number(jobId)) || null;
}
function agentMatchForJob(jobId) {
  return state.agentMatches.get(Number(jobId)) || null;
}
function scoreLevel(score) {
  return score >= 85 ? '高度匹配' : score >= 70 ? '较为匹配' : score >= 55 ? '可以尝试' : '不建议优先';
}
function updateAgentMatchNote() {
  const node = $('#agent-match-note');
  if (!node) return;
  node.classList.toggle('loading', state.agentLoading);
  node.classList.toggle('error', Boolean(state.agentError));
  if (state.agentLoading) {
    node.textContent = 'Agent 正在逐条对照岗位要求精排前 20 个岗位，期间先展示规则分…';
  } else if (state.agentError) {
    node.textContent = state.agentError;
  } else if (state.agentMatches.size) {
    node.textContent = `Agent 已精排 ${state.agentMatches.size} 个岗位 · 最终分 = Agent 70% + 规则 30%`;
  } else {
    node.textContent = '匹配度由 Agent 读取岗位职责和任职要求动态计算；Agent 不可用时自动使用规则分。';
  }
}
async function loadAgentScores(recommendationId=state.recommendationRequestId) {
  const resumeId = Number(state.activeResumeId);
  const jobIds = state.recommendations.slice(0, 20).map(item => Number(item.job?.id)).filter(Boolean);
  if (!resumeId || !jobIds.length) {
    state.agentLoading = false;
    updateAgentMatchNote();
    return;
  }
  state.agentLoading = true;
  state.agentError = '';
  updateAgentMatchNote();
  renderJobs();
  try {
    const result = await post('/api/jobs/recommend/agent', { resumeId, jobIds });
    if (recommendationId !== state.recommendationRequestId) return;
    const items = result?.items || [];
    if (!result?.available || !items.length) {
      state.agentError = 'Agent 暂不可用，当前显示规则分；岗位详情仍可正常查看。';
      return;
    }
    state.agentMatches = new Map(items.map(item => [Number(item.jobId), item]));
  } catch (error) {
    if (recommendationId !== state.recommendationRequestId) return;
    state.agentError = `Agent 精排失败，已自动回退规则分：${error.message}`;
  } finally {
    if (recommendationId === state.recommendationRequestId) {
      state.agentLoading = false;
      updateAgentMatchNote();
      renderJobs();
    }
  }
}
async function reloadJobs() {
  state.recommendations = [];
  try { await loadRecommendations(); }
  catch (error) { toast('岗位筛选失败', error.message, true); }
  await renderJobs();
}
async function renderJobs() {
  const target = $('#job-list');
  if (!target) return;
  if (!state.recommendations.length) {
    try { await loadRecommendations(); }
    catch (error) { toast('岗位匹配失败', error.message, true); }
  }
  const keyword = ($('#job-keyword')?.value || '').toLowerCase();
  const city = $('#job-city-filter')?.value || '';
  const sort = $('#job-sort')?.value || 'match';
  let items = state.recommendations.filter(item => {
    const job = item.job;
    const haystack = `${job.title} ${job.company} ${job.description} ${(job.skills||[]).join(' ')}`.toLowerCase();
    return (!keyword || haystack.includes(keyword)) && (!city || job.city === city);
  });
  const scored = items.map(item => {
    const agent = agentMatchForJob(item.job.id);
    return { item, agent, score: agent?.finalScore ?? item.matchScore };
  });
  if (sort === 'match') scored.sort((a,b) => b.score - a.score);
  else scored.sort((a,b) => new Date(b.item.job.collectedAt) - new Date(a.item.job.collectedAt));
  $('#job-count').textContent = `${scored.length} 个机会`;
  if (!scored.length) {
    target.innerHTML = '<div class="panel loading-card">没有符合条件的岗位，请调整筛选条件。</div>';
    return;
  }
  target.innerHTML = scored.map(({item, agent, score}, index) => {
    const job = item.job;
    const dutyPreview = compactText(job.jobDescription || job.description, 105);
    const reqPreview = compactText(job.jobRequirements, 105);
    const skills = (job.skills || []).slice(0,7).map(skill => {
      const normalized = String(skill).toLowerCase().replace(/\./g,'').replace(/\s/g,'');
      const isMatched = (item.matchedSkills || []).some(value => String(value).toLowerCase().replace(/\./g,'').replace(/\s/g,'') === normalized);
      const isMissing = (item.missingSkills || []).some(value => String(value).toLowerCase().replace(/\./g,'').replace(/\s/g,'') === normalized);
      return `<span class="skill-chip ${isMatched?'matched':isMissing?'missing':''}">${escapeHtml(skill)}</span>`;
    }).join('');
    const agentBreakdown = (agent?.breakdown || []).slice(0,4).map(row => {
      const tone = row.status === '满足' ? 'met' : row.status === '部分满足' ? 'partial' : 'miss';
      return `<span class="agent-requirement ${tone}" title="${escapeHtml(row.evidence || '')}"><b>${escapeHtml(row.importance || '要求')}</b> ${escapeHtml(compactText(row.requirement, 34))}<em>${escapeHtml(row.status || '')}</em></span>`;
    }).join('');
    const ruleBreakdown = (item.scoreBreakdown || []).filter(component => component.weight > 0).map(component =>
      `<span title="${escapeHtml(component.detail || '')}">${escapeHtml(component.label)} <b>${component.contribution}</b></span>`
    ).join('');
    const breakdown = agent
      ? `<span class="score-breakdown-label">Agent 逐条判断</span>${agentBreakdown}`
      : `<span class="score-breakdown-label">规则分构成</span>${ruleBreakdown || '<span>暂无拆解</span>'}`;
    const reasons = agent
      ? [`Agent：${agent.reason || '已完成岗位要求匹配'}`, ...(item.reasons || []).slice(0,2)]
      : (item.reasons || []);
    const level = agent?.verdict || item.matchLevel || scoreLevel(score);
    return `<article class="panel job-card" data-job="${job.id}">
      <div class="job-card-head"><div class="job-company"><div class="company-logo">${escapeHtml(String(job.company || '岗').slice(0,1))}</div><div><h3>${escapeHtml(job.title)}</h3><p>${escapeHtml(job.company)} · ${escapeHtml(job.experience || '经验不限')}</p></div></div><div class="match-badge"><span class="score-source ${agent?'agent':'rule'}">${agent?'AGENT':'RULE'}</span><strong>${score}%</strong><span>${escapeHtml(level)}</span></div></div>
      <div class="job-meta"><span class="salary">${escapeHtml(job.salary || '薪资面议')}</span><span>${escapeHtml(job.city)}</span><span>${escapeHtml(job.education || '学历不限')}</span></div>
      <div class="job-skills">${skills}</div>
      <div class="job-requirement-preview">
        <div><span>岗位职责 ${job.contentOrigin === 'AI_GENERATED' ? '<i class="ai-badge">AI生成</i>' : ''}</span><p>${escapeHtml(dutyPreview || '暂无职责摘要')}</p></div>
        <div><span>任职要求 ${job.contentOrigin === 'AI_GENERATED' ? '<i class="ai-badge">AI生成</i>' : ''}</span><p>${escapeHtml(reqPreview || '暂无要求摘要')}</p></div>
      </div>
      <div class="job-reasons">${reasons.slice(0,4).map(reason => `<span>• ${escapeHtml(reason)}</span>`).join('')}</div>
      <div class="score-breakdown ${agent?'agent-breakdown':''}">${breakdown}</div>
      <span class="source-tag">${escapeHtml(job.source || '本地岗位源')}</span>
      <div class="job-card-foot"><button class="ghost-button" data-job-action="detail" data-job-id="${job.id}">岗位详情</button><button class="primary-button" data-job-action="diagnose" data-job-id="${job.id}">诊断匹配</button><button class="primary-button" data-job-action="interview" data-job-id="${job.id}">模拟面试</button></div>
    </article>`;
  }).join('');
}
async function openJobModal(jobId) {
  const job = jobById(jobId) || await api(`/api/jobs/${jobId}`);
  if (!job) return;
  const rec = recommendationForJob(job.id);
  const agent = agentMatchForJob(job.id);
  const scorePanel = agent
    ? `<section class="agent-score-panel">
        <div class="agent-score-head"><div><span class="eyebrow">AGENT MATCH REVIEW</span><h3>Agent 匹配结论</h3></div><div class="agent-score-numbers"><div><strong>${agent.finalScore}</strong><span>最终分</span></div><div><strong>${agent.agentScore}</strong><span>Agent 分</span></div><div><strong>${agent.ruleScore}</strong><span>规则分</span></div></div></div>
        <p class="agent-verdict">${escapeHtml(agent.verdict || scoreLevel(agent.finalScore))} · ${escapeHtml(agent.reason || '')}</p>
        <div class="agent-breakdown-list">${(agent.breakdown || []).map(row => {
          const tone = row.status === '满足' ? 'met' : row.status === '部分满足' ? 'partial' : 'miss';
          return `<div class="agent-breakdown-item ${tone}"><div><b>${escapeHtml(row.importance || '要求')}</b><span>${escapeHtml(row.requirement || '')}</span><em>${escapeHtml(row.status || '')}</em></div><small>${escapeHtml(row.evidence || '暂无简历证据')}</small></div>`;
        }).join('')}</div>
        <div class="agent-score-formula">最终分 ${agent.finalScore} = Agent ${agent.agentScore} × 70% + 规则 ${agent.ruleScore} × 30%</div>
      </section>`
    : `<section class="agent-score-panel fallback"><div class="agent-score-head"><div><span class="eyebrow">SCORE SOURCE</span><h3>当前使用规则兜底分</h3></div>${rec ? `<div class="agent-score-numbers"><div><strong>${rec.matchScore}</strong><span>规则分</span></div></div>` : ''}</div><p class="agent-verdict">${state.agentLoading ? 'Agent 正在分析该批次岗位，完成后会显示逐条要求与证据。' : 'Agent 暂不可用。规则分仅用于召回和兜底，不代表模型已经完成逐条判断。'}</p></section>`;
  $('#job-modal-content').innerHTML = `<span class="eyebrow">JOB DETAIL · VERIFIED JOB DATA</span><h2>${escapeHtml(job.title)}</h2><div class="modal-company">${escapeHtml(job.company)} · ${escapeHtml(job.city)} · ${escapeHtml(job.source || '')}</div>
    <div class="job-detail-grid"><div><span>薪资范围</span><strong>${escapeHtml(job.salary || '面议')}</strong></div><div><span>学历/经验</span><strong>${escapeHtml(job.education || '不限')} / ${escapeHtml(job.experience || '不限')}</strong></div></div>
    ${scorePanel}
    <h3>技术需求</h3><div class="job-skills">${(job.skills||[]).map(skill => {
      const key = String(skill).toLowerCase().replace(/\./g,'').replace(/\s/g,'');
      const hit = (rec?.matchedSkills || []).some(v => String(v).toLowerCase().replace(/\./g,'').replace(/\s/g,'') === key);
      const miss = (rec?.missingSkills || []).some(v => String(v).toLowerCase().replace(/\./g,'').replace(/\s/g,'') === key);
      return `<span class="skill-chip ${hit?'matched':miss?'missing':''}">${escapeHtml(skill)}</span>`;
    }).join('')}</div>
    <h3 style="margin-top:20px">岗位职责 ${job.contentOrigin === 'AI_GENERATED' ? '<span class="ai-badge">AI生成</span>' : ''}</h3><div class="job-description" style="white-space:pre-wrap">${escapeHtml(job.jobDescription || job.description || '暂无岗位职责')}</div>
    <h3 style="margin-top:20px">任职要求 ${job.contentOrigin === 'AI_GENERATED' ? '<span class="ai-badge">AI生成</span>' : ''}</h3><div class="job-description" style="white-space:pre-wrap">${escapeHtml(job.jobRequirements || '暂无结构化任职要求')}</div>
    <div id="job-similar-box" class="job-similar-box"><h3>相似岗位</h3><div class="empty-mini">正在查找同方向岗位...</div></div>
    <div class="job-card-foot"><button class="primary-button" data-job-action="diagnose" data-job-id="${job.id}">用此岗位诊断</button><button class="primary-button" data-job-action="interview" data-job-id="${job.id}">开始模拟面试</button></div>`;
  $('#job-modal').classList.add('open'); $('#job-modal').setAttribute('aria-hidden','false');
  try {
    const similar = await api(`/api/jobs/${job.id}/similar?limit=4`);
    const box = $('#job-similar-box');
    if (box) {
      box.innerHTML = `<h3>相似岗位</h3>` + (similar.length
        ? `<div class="similar-list">${similar.map(item => `<button type="button" class="similar-item" data-similar-job="${item.id}"><span>${escapeHtml(item.title)}</span><small>${escapeHtml(item.company || '')} · ${escapeHtml(item.city || '')} · ${escapeHtml(item.salary || '面议')}</small></button>`).join('')}</div>`
        : '<div class="empty-mini">暂无同方向岗位</div>');
    }
  } catch (error) { /* 相似岗位失败不影响详情展示 */ }
}
function closeJobModal() { $('#job-modal').classList.remove('open'); $('#job-modal').setAttribute('aria-hidden','true'); }
async function refreshJobs() {
  try {
    await api('/api/jobs/refresh?limit=30', { method:'POST' });
    const [jobs] = await Promise.all([api('/api/jobs'), loadRecommendations()]);
    state.jobs = jobs; populateAllSelects(); renderJobs(); toast('岗位源已刷新','已同步 MCP 岗位并重新计算匹配');
  } catch (error) { toast('刷新失败',error.message,true); }
}

/* interview */
async function startInterview(jobId) {
  const resumeId = Number($('#interview-resume').value || state.activeResumeId);
  const selectedJob = Number(jobId || $('#interview-job').value || state.activeJobId);
  if (!resumeId || !selectedJob) { toast('无法开始','请先准备简历并选择岗位',true); return; }
  try {
    state.activeResumeId = resumeId; state.activeJobId = selectedJob;
    $('#interview-resume').value = resumeId; $('#interview-job').value = selectedJob;
    state.interview = await post('/api/interviews', { resumeId, jobId:selectedJob, difficulty:state.difficulty });
    renderInterview(state.interview); navigate('interview'); toast('Agent 面试已开始','请用 STAR 结构回答，AI 面试官会根据你的回答动态追问');
  } catch (error) { toast('创建失败',error.message,true); }
}
function renderInterview(session) {
  if (!session) return;
  state.interview = session;
  $('#interview-status').textContent = session.status === 'COMPLETED' ? '面试已完成' : `第 ${session.currentRound} 轮进行中`;
  $('#interview-status').className = `session-status ${session.status === 'COMPLETED' ? 'done' : 'live'}`;
  $('#chat-job-title').textContent = session.jobTitle;
  const progress = Math.round((session.status === 'COMPLETED' ? session.totalRounds : session.currentRound - 1) / session.totalRounds * 100);
  $('#round-text').textContent = `${session.status === 'COMPLETED' ? session.totalRounds : session.currentRound} / ${session.totalRounds}`;
  $('#round-bar').style.width = `${progress}%`;
  const messages = $('#chat-messages');
  messages.innerHTML = (session.messages || []).map(message => {
    const isUser = message.role === 'user'; const time = message.timestamp ? new Date(message.timestamp).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'}) : '';
    const score = message.score ? `<span class="message-score">${message.score} 分</span>` : '';
    return `<div class="message ${isUser?'user':''}"><div class="message-avatar">${isUser?'我':'AI'}</div><div class="message-body"><p>${escapeHtml(message.content)}</p><div class="message-meta">${time} ${score}</div></div></div>`;
  }).join('');
  messages.scrollTop = messages.scrollHeight;
  $('#send-answer').disabled = session.status === 'COMPLETED';
  $('#interview-answer').disabled = session.status === 'COMPLETED';
}
async function sendInterviewAnswer() {
  const answer = $('#interview-answer').value.trim(); if (!answer || !state.interview) return;
  const button = $('#send-answer'); button.disabled = true; $('#interview-answer').value = '';
  const messages = $('#chat-messages');
  messages.insertAdjacentHTML('beforeend', `<div class="message user"><div class="message-avatar">我</div><div class="message-body"><p>${escapeHtml(answer)}</p></div></div><div class="message" id="typing-message"><div class="message-avatar">AI</div><div class="message-body typing"><i></i><i></i><i></i></div></div>`);
  messages.scrollTop = messages.scrollHeight;
  try { const updated = await post(`/api/interviews/${state.interview.id}/answers`, { answer }); renderInterview(updated); if (updated.status === 'COMPLETED') toast('面试完成',`综合表达得分 ${updated.overallScore} 分，建议重新运行简历诊断`); }
  catch (error) { toast('发送失败',error.message,true); button.disabled = false; }
}

/* diagnosis */
async function runDiagnosis(jobId) {
  const resumeId = Number($('#diagnosis-resume').value || state.activeResumeId);
  const selectedJob = Number(jobId || $('#diagnosis-job').value || state.activeJobId);
  if (!resumeId) { toast('无法诊断','请先保存一份简历',true); return; }
  const target = $('#diagnosis-content');
  target.innerHTML = '<div class="loading-card"><i></i>Agent 正在分别计算简历质量与岗位匹配，并生成可解释评分...</div>';
  try {
    state.activeResumeId = resumeId; if (selectedJob) state.activeJobId = selectedJob;
    const report = await post('/api/diagnoses', { resumeId, jobId:selectedJob || null });
    state.diagnosis = report; renderDiagnosis(report);
    toast('Agent 诊断完成', `综合 ${report.overallScore} 分 · 简历质量 ${report.resumeQualityScore} 分 · 岗位匹配 ${report.jobMatchScore ?? '--'} 分`);
  } catch (error) { toast('诊断失败',error.message,true); renderDiagnosis(state.diagnosis); }
}
function renderDiagnosis(report) {
  const target = $('#diagnosis-content'); if (!target) return;
  if (!report) { target.className = 'diagnosis-empty panel'; return; }
  target.className = 'panel'; state.diagnosis = report;
  const qualityScore = report.resumeQualityScore ?? report.overallScore;
  const matchScore = report.jobMatchScore ?? null;
  const dimensions = (report.dimensions || []).map(item => `<div class="dimension"><div><span>${escapeHtml(item.name)} <small style="color:#9aaba7">${item.weight}%</small></span><strong>${item.score}</strong></div><div class="dimension-track"><i style="width:${item.score}%"></i></div><p style="font-size:9px;color:#899a96;margin:4px 0 0">${escapeHtml(item.comment)}</p></div>`).join('');
  const suggestions = (report.suggestions || []).map(item => `<div class="suggestion ${item.priority.toLowerCase()}"><div class="priority">${escapeHtml(item.priority)}</div><div><h4>${escapeHtml(item.category)} · ${escapeHtml(item.issue)}</h4><p>${escapeHtml(item.action)}</p><span class="example">示例：${escapeHtml(item.example)}</span></div></div>`).join('');
  const matched = (report.matchedKeywords || []).map(item => `<span>✓ ${escapeHtml(item)}</span>`).join('');
  const missing = (report.missingKeywords || []).map(item => `<span class="missing">! ${escapeHtml(item)}</span>`).join('');
  const formula = matchScore === null ? '未选择具体岗位，综合分等于简历质量分' : `综合 ${report.overallScore} = 岗位匹配 ${matchScore} × 60% + 简历质量 ${qualityScore} × 40%`;
  const qualityFocus = [...(report.dimensions || [])].sort((a,b) => a.score - b.score).slice(0,3).map(item => `<div class="explain-item"><span>${escapeHtml(item.name)}</span><strong>${item.score}</strong><small>${escapeHtml(item.comment)}</small></div>`).join('');
  const matchFocus = (report.jobMatchBreakdown || []).filter(item => item.weight > 0).sort((a,b) => a.factor - b.factor).slice(0,3).map(item => `<div class="explain-item"><span>${escapeHtml(item.label)}</span><strong>${item.contribution}</strong><small>权重 ${Math.round(item.weight)}% · ${escapeHtml(item.detail || '')}</small></div>`).join('');
  const breakdown = (report.jobMatchBreakdown || []).filter(item => item.weight > 0).map(item => `<span title="${escapeHtml(item.detail || '')}">${escapeHtml(item.label)} <b>${item.contribution}</b></span>`).join('');
  target.innerHTML = `<div class="report-head"><div class="diagnosis-score-stack"><div class="big-score-ring" style="--score:${report.overallScore}"><div><strong>${report.overallScore}</strong><span>综合竞争力</span></div></div><div class="score-split"><div><strong>${qualityScore}</strong><span>简历质量</span></div><div><strong>${matchScore ?? '--'}</strong><span>岗位匹配</span><small>${escapeHtml(report.jobMatchLevel || '未选择岗位')}</small></div></div></div><div class="report-overview"><div class="report-title"><h2>${escapeHtml(report.targetRole)}</h2><span class="report-level">${escapeHtml(report.level)}</span></div><p class="report-summary">${escapeHtml(report.summary)}</p><div class="score-formula">${escapeHtml(formula)}</div>${breakdown ? `<div class="score-breakdown">${breakdown}</div>` : ''}<div class="keyword-row">${matched}${missing}</div><div class="strength-row">${(report.strengths || []).map(item => `<span>✦ ${escapeHtml(item)}</span>`).join('')}</div></div></div><div class="score-explain"><div class="explain-head"><span class="eyebrow">WHY THIS SCORE</span><h3>为什么是这个分数</h3></div><div class="explain-grid"><div><h4>简历质量主要失分项</h4>${qualityFocus}</div><div><h4>岗位匹配主要影响因素</h4>${matchFocus || '<p class="explain-empty">未选择岗位或岗位数据不足</p>'}</div></div></div><div class="report-body"><div class="dimension-block"><span class="eyebrow">RESUME QUALITY</span><h3>简历质量维度</h3><div class="dimension-list">${dimensions}</div></div><div class="suggestion-block"><span class="eyebrow">ACTION PRIORITY</span><h3>修改行动清单</h3><div class="suggestion-list">${suggestions}</div></div></div>` + renderLearningPlanBlock(report) + renderAgentTraceBlock(report);
}

/* job recommendation agent */
async function runJobAgent() {
  const resumeId = Number($('#diagnosis-resume').value || state.activeResumeId);
  if (!resumeId) { toast('无法推荐','请先保存一份简历',true); return; }
  const resume = state.resumes.find(item => item.id === resumeId) || {};
  const city = resume.city || '';
  const target = $('#job-agent-content');
  const button = $('#run-job-agent');
  target.innerHTML = '<div class="loading-card"><i></i>Agent 正在理解简历、生成检索语句并召回岗位，必要时会自动换说法重试…</div>';
  if (button) button.disabled = true;
  try {
    const url = `/api/diagnoses/agent?resumeId=${resumeId}&city=${encodeURIComponent(city)}&limit=6`;
    const report = await post(url, {});
    state.jobAgent = report; renderJobAgent(report);
    toast('岗位推荐完成', `推荐 ${(report.recommendations||[]).length} 个岗位 · Agent 自主重试 ${report.attempts||0} 次`);
  } catch (error) {
    toast('岗位推荐失败', error.message, true);
    target.innerHTML = `<div class="job-agent-empty"><div><span class="eyebrow">JOB RECOMMENDATION AGENT</span><h3>推荐未完成</h3></div><p>${escapeHtml(error.message)}</p></div>`;
  } finally {
    if (button) button.disabled = false;
  }
}
function renderJobAgent(report) {
  const target = $('#job-agent-content'); if (!target || !report) return;
  const items = report.recommendations || [];
  const cards = items.map((item, index) => {
    const score = item.matchScore ?? '--';
    const tone = (item.matchScore ?? 0) >= 80 ? 'high' : (item.matchScore ?? 0) >= 70 ? 'mid' : 'low';
    const matched = (item.matchedSkills || []).map(skill => `<span>✓ ${escapeHtml(skill)}</span>`).join('');
    const missing = (item.missingSkills || []).map(skill => `<span class="missing">! ${escapeHtml(skill)}</span>`).join('');
    const breakdown = (item.scoreBreakdown || []).filter(part => part.weight > 0).map(part => `<span title="${escapeHtml(part.detail || '')}">${escapeHtml(part.label)} <b>${part.contribution}</b></span>`).join('');
    const origin = item.contentOrigin === 'AI_GENERATED' ? '<em class="origin ai">JD 为 AI 补全</em>' : '<em class="origin real">原始招聘数据</em>';
    const flag = item.directionMatch === false ? '<em class="origin warn">方向存疑</em>' : '';
    const semantic = item.semanticHit ? '<em class="origin semantic">语义命中</em>' : '';
    return `<article class="job-agent-card ${tone}"><div class="job-agent-rank">0${index + 1}</div><div class="job-agent-main"><div class="job-agent-title"><h4>${escapeHtml(item.title)}</h4><span class="job-agent-score">${score}<small>分</small></span></div><p class="job-agent-meta">${escapeHtml(item.company)} · ${escapeHtml(item.city || '城市未知')} · ${escapeHtml(item.salary || '薪资面议')}</p><div class="job-agent-tags"><em class="level">${escapeHtml(item.matchLevel || '')}</em>${semantic}${origin}${flag}</div>${matched || missing ? `<div class="keyword-row">${matched}${missing}</div>` : ''}${breakdown ? `<div class="score-breakdown">${breakdown}</div>` : ''}</div></article>`;
  }).join('');
  const gaps = (report.skillGaps || []).map(gap => `<span>${escapeHtml(gap.skill)} <b>×${gap.appearsIn}</b></span>`).join('');
  const queries = (report.queries || []).map(query => `<span>${escapeHtml(query)}</span>`).join('');
  const trace = (report.trace || []).map(step => `<li>${escapeHtml(step)}</li>`).join('');
  target.innerHTML = `<div class="job-agent-head"><div><span class="eyebrow">JOB RECOMMENDATION AGENT</span><h3>AI 岗位推荐</h3></div><div class="job-agent-stats"><div><strong>${items.length}</strong><span>推荐岗位</span></div><div><strong>${report.attempts || 0}</strong><span>自主重试</span></div><div><strong>${(report.skillGaps || []).length}</strong><span>技能缺口</span></div></div></div><p class="job-agent-summary">${escapeHtml(report.summary || '')}</p><div class="job-agent-meta-row"><span class="meta-label">检索语句</span><div class="job-agent-queries">${queries || '<span>—</span>'}</div></div><div class="job-agent-cards">${cards || '<p class="explain-empty">本次没有召回岗位，请放宽城市或岗位方向后重试。</p>'}</div>${gaps ? `<div class="job-agent-gaps"><span class="meta-label">技能缺口（按在推荐岗位中出现的频次）</span><div class="keyword-row">${gaps}</div></div>` : ''}<details class="job-agent-trace"><summary>查看 Agent 决策轨迹（${(report.trace || []).length} 步）</summary><ol>${trace}</ol></details>`;
}

function renderLearningPlanBlock(report) {
  const plan = report.learningPlan;
  if (!plan || !Array.isArray(plan.modules) || !plan.modules.length) return '';
  const modules = plan.modules.map(module => `
    <div class="learning-module ${module.status === '进行中' ? 'active' : ''}">
      <div class="module-index">0${module.order}</div>
      <div class="module-content">
        <div class="module-title"><h4>${escapeHtml(module.title)}</h4><span>${escapeHtml(module.duration)} · ${escapeHtml(module.status)}</span></div>
        <p>${escapeHtml(module.objective)}</p>
        <div class="topic-tags">${(module.topics || []).map(topic => `<span>${escapeHtml(topic)}</span>`).join('')}</div>
        <span class="deliverable">交付物：${escapeHtml(module.deliverable)}</span>
      </div>
    </div>`).join('');
  return `<div class="diagnosis-plan"><div class="diagnosis-plan-head"><div><span class="eyebrow">NEXT STEPS</span><h3>下一步学习计划</h3></div><div class="readiness"><span>岗位准备度</span><strong>${plan.readiness}%</strong></div></div><p class="learning-summary">${escapeHtml(plan.summary || '')}</p><div class="learning-modules">${modules}</div></div>`;
}
function renderAgentTraceBlock(report) {
  const trace = report.agentTrace || [];
  if (!trace.length) return '';
  return `<div class="diagnosis-trace"><span class="eyebrow">AGENT TRACE</span><h3>Agent 决策轨迹</h3><p class="trace-hint">以下工具由 Agent 自主选择调用，分数来自规则算法，未由模型修改</p><div class="trace-chips">${trace.map(item => `<span>${escapeHtml(item)}</span>`).join('')}</div></div>`;
}
/* single-agent orchestration */
async function runAgentWorkflow() {
  const resumeId = state.activeResumeId || state.resumes[0]?.id;
  if (!resumeId) { toast('无法运行','请先创建简历',true); return; }
  const overlay = $('#agent-overlay'); overlay.classList.add('open'); overlay.setAttribute('aria-hidden','false');
  const placeholders = [
    ['resume.profile','读取并结构化简历'],['job.match','对齐招聘岗位与技能图谱'],
    ['interview.start','启动岗位模拟面试'],['resume.diagnose','执行六维简历诊断'],['learning.generate','生成个性化学习路线']
  ];
  $('#agent-steps').innerHTML = placeholders.map((item,index) => `<div class="agent-step" data-step="${index}"><div class="agent-step-icon">0${index+1}</div><div><strong>${item[1]}</strong><p>${item[0]}</p></div><time>等待</time></div>`).join('');
  $('#agent-console-status').textContent = '正在准备单智能体上下文与工具清单';
  const request = post('/api/agent/run', { resumeId, jobId:state.activeJobId || null, intent:'full-workflow' });
  for (let i=0;i<placeholders.length;i++) {
    const node = $(`[data-step="${i}"]`, $('#agent-steps')); const started = performance.now();
    node.classList.add('running'); node.querySelector('time').textContent = '运行中';
    $('#agent-console-status').textContent = `正在调用 ${placeholders[i][0]}`;
    await sleep(260); node.classList.remove('running'); node.classList.add('done');
    node.querySelector('time').textContent = `${Math.round(performance.now()-started)}ms`;
  }
  try {
    const result = await request;
    $('#agent-steps').innerHTML = result.steps.map((step,index) => `<div class="agent-step done"><div class="agent-step-icon">0${index+1}</div><div><strong>${escapeHtml(step.title)}</strong><p>${escapeHtml(step.tool)} · ${escapeHtml(step.detail)}</p></div><time>${step.durationMs}ms</time></div>`).join('');
    $('#agent-overlay-summary').textContent = result.summary;
    $('#agent-console-status').textContent = `${result.mode} 执行完成`;
    state.diagnosis = result.diagnosis; state.recommendations = result.recommendations; state.interview = result.interview;
    renderDiagnosis(result.diagnosis); renderDashboardJobs(result.recommendations);
    await refreshDashboard(); renderJobs(); populateAllSelects(); await sleep(1400); closeAgentOverlay(); navigate('dashboard');
    toast('智能体执行完成',`简历 ${result.diagnosis.overallScore} 分，已生成岗位、面试与学习路线`);
  } catch (error) { $('#agent-console-status').textContent = `执行失败：${error.message}`; toast('智能体执行失败',error.message,true); }
}
function closeAgentOverlay() { $('#agent-overlay').classList.remove('open'); $('#agent-overlay').setAttribute('aria-hidden','true'); }

/* events */
document.addEventListener('click', async event => {
  const nav = event.target.closest('[data-view]'); if (nav) { navigate(nav.dataset.view); return; }
  const go = event.target.closest('[data-go]'); if (go) { navigate(go.dataset.go); return; }
  const brand = event.target.closest('[data-view-link]'); if (brand) { event.preventDefault(); navigate(brand.dataset.viewLink); return; }
  const action = event.target.closest('[data-job-action]');
  if (action) {
    event.preventDefault(); const jobId = Number(action.dataset.jobId); state.activeJobId = jobId;
    if (action.dataset.jobAction === 'detail') openJobModal(jobId);
  const similarBtn = event.target.closest('[data-similar-job]');
  if (similarBtn) openJobModal(Number(similarBtn.dataset.similarJob));
    if (action.dataset.jobAction === 'interview') { closeJobModal(); await startInterview(jobId); }
    if (action.dataset.jobAction === 'diagnose') { closeJobModal(); $('#diagnosis-resume').value = state.activeResumeId; $('#diagnosis-job').value = jobId; navigate('diagnosis'); await runDiagnosis(jobId); }
    return;
  }
  const template = event.target.closest('[data-resume-template]');
  if (template) { setResumeTemplate(template.dataset.resumeTemplate); return; }
  const skillOption = event.target.closest('[data-skill]');
  if (skillOption) { toggleSkill(skillOption.dataset.skill); return; }
  const removeSkill = event.target.closest('[data-remove-skill]');
  if (removeSkill) { state.skills.splice(Number(removeSkill.dataset.removeSkill),1); renderSkillPicker(); updateResumePreview(); return; }
  const removeProject = event.target.closest('[data-remove-project]');
  if (removeProject) { state.projects = readProjectDrafts(); state.projects.splice(Number(removeProject.dataset.removeProject),1); if (!state.projects.length) state.projects.push(blankProject()); renderProjectEditor(); updateResumePreview(); return; }
  const removeExperience = event.target.closest('[data-remove-experience]');
  if (removeExperience) { state.experiences = readExperienceDrafts(); state.experiences.splice(Number(removeExperience.dataset.removeExperience),1); if (!state.experiences.length) state.experiences.push(blankExperience()); renderExperienceEditor(); updateResumePreview(); return; }
  if (event.target.closest('[data-close-modal]')) closeJobModal();
  if (event.target.closest('#close-agent-overlay')) closeAgentOverlay();
});
$('#job-modal').addEventListener('click', event => { if (event.target.id === 'job-modal') closeJobModal(); });
$('#agent-overlay').addEventListener('click', event => { if (event.target.id === 'agent-overlay') closeAgentOverlay(); });
$('#add-project').addEventListener('click', () => { state.projects = readProjectDrafts(); state.projects.push(blankProject()); renderProjectEditor(); updateResumePreview(); });
$('#add-experience').addEventListener('click', () => { state.experiences = readExperienceDrafts(); state.experiences.push(blankExperience()); renderExperienceEditor(); updateResumePreview(); });
$('#add-custom-skill').addEventListener('click', addCustomSkill);
$('#custom-skill-input').addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); addCustomSkill(); } });
$('#new-resume').addEventListener('click', emptyResume);
$('#save-resume').addEventListener('click', event => { event.preventDefault(); saveResume(); });
$('#save-version').addEventListener('click', event => { event.preventDefault(); saveVersion(); });
$('#open-guidance').addEventListener('click', event => { event.preventDefault(); openGuidance(); });
$('#open-ats').addEventListener('click', event => { event.preventDefault(); openAts(); });
$('#ats-modal').addEventListener('click', event => { if (event.target.closest('[data-close-ats]') || event.target.id === 'ats-modal') closeAts(); });
$('#guidance-modal').addEventListener('click', event => {
  if (event.target.closest('[data-close-guidance]') || event.target.id === 'guidance-modal') closeGuidance();
});
$('#resume-versions').addEventListener('click', event => {
  const btn = event.target.closest('[data-restore-version]');
  if (btn) restoreVersion(Number(btn.dataset.restoreVersion), btn.dataset.versionNo);
});
$('#fact-locks').addEventListener('change', saveFactLocks);
$('#resume-form').addEventListener('submit', event => { event.preventDefault(); saveResume(); });
$('#resume-form').addEventListener('input', updateResumePreview);
$('#resume-form').addEventListener('change', updateResumePreview);
$('#upload-education-proof').addEventListener('click', () => $('#education-proof-file').click());
$('#education-proof-file').addEventListener('change', event => {
  const file = event.target.files?.[0];
  if (file) runEducationVerification(file);
});
window.addEventListener('resize', () => requestAnimationFrame(fitResumePreview));$('#refresh-btn').addEventListener('click', async () => { try { await refreshDashboard(); renderJobs(); toast('数据已刷新','总览数据已同步'); } catch (error) { toast('刷新失败',error.message,true); } });
$('#refresh-jobs').addEventListener('click', refreshJobs);
$('#job-keyword').addEventListener('input', renderJobs);
$('#job-city-filter').addEventListener('change', renderJobs);
['#job-category-filter','#job-experience-filter','#job-education-filter','#job-salary-filter']
  .forEach(sel => document.querySelector(sel)?.addEventListener('change', reloadJobs));
$('#job-keyword').addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); reloadJobs(); } });
$('#job-sort').addEventListener('change', renderJobs);
$('#add-excluded-city').addEventListener('click', addExcludedCity);
$('#excluded-city-input').addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); addExcludedCity(); } });
$('#excluded-city-chips').addEventListener('click', event => {
  const button = event.target.closest('[data-remove-excluded-city]');
  if (button) removeExcludedCity(Number(button.dataset.removeExcludedCity));
});
$('#start-interview').addEventListener('click', () => startInterview());
$('#send-answer').addEventListener('click', sendInterviewAnswer);
$('#interview-answer').addEventListener('keydown', event => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); sendInterviewAnswer(); } });
$('#interview-difficulty').addEventListener('click', event => { const button = event.target.closest('button'); if (!button) return; state.difficulty = button.dataset.value; $$('#interview-difficulty button').forEach(item => item.classList.toggle('active', item === button)); });
$('#run-diagnosis').addEventListener('click', () => runDiagnosis());
$('#run-job-agent').addEventListener('click', () => runJobAgent());
$('#run-agent-top').addEventListener('click', runAgentWorkflow);
$('#run-agent-hero').addEventListener('click', runAgentWorkflow);
$('#workflow-run').addEventListener('click', runAgentWorkflow);
$('#interview-resume').addEventListener('change', event => { state.activeResumeId = Number(event.target.value); $('#diagnosis-resume').value = event.target.value; });
$('#diagnosis-resume').addEventListener('change', event => { state.activeResumeId = Number(event.target.value); $('#interview-resume').value = event.target.value; });

boot();
