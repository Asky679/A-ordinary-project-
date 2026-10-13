const MATCH_WEIGHT_KEY = 'careernav-match-weights';
const EXCLUDED_CITY_KEY = 'careernav-excluded-cities';
const DEFAULT_MATCH_WEIGHTS = Object.freeze({ skills:35, role:20, experience:15, education:10, city:5, evidence:5 });
const state = {
  resumes: [], jobs: [], recommendations: [], dashboard: null,
  activeResumeId: null, activeJobId: null, interview: null,
  diagnosis: null, learningPath: null, skillGraph: null,
  projects: [], difficulty: '标准', view: 'dashboard',
  matchWeights: { ...DEFAULT_MATCH_WEIGHTS }, excludedCities: [], recommendationRequestId: 0
};
const pageTitles = { dashboard:'成长总览', resume:'简历工坊', jobs:'岗位雷达', interview:'模拟面试', diagnosis:'简历诊断', learning:'学习路线' };
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
  if (view === 'learning' && !state.learningPath) generateLearningPath();
  window.scrollTo({ top:0, behavior:'smooth' });
}

async function boot() {
  try {
    const [resumes, jobs, dashboard, agentStatus] = await Promise.all([
      api('/api/resumes'), api('/api/jobs'), api('/api/dashboard'), api('/api/agent/status')
    ]);
    state.resumes = resumes; state.jobs = jobs; state.dashboard = dashboard;
    state.activeResumeId = resumes[0]?.id || null;
    state.activeJobId = dashboard.topJobs?.[0]?.job?.id || jobs[0]?.id || null;
    restoreMatchPreferences(); updateMatchWeightPanel(); populateAllSelects();
    if (resumes[0]) fillResumeForm(resumes[0]);
    renderDashboard(dashboard);
    renderJobs();
    updateAgentStatus(agentStatus);
    if (dashboard.latestDiagnosis) { state.diagnosis = dashboard.latestDiagnosis; renderDiagnosis(dashboard.latestDiagnosis); }
    $('#sidebar-agent-mode').textContent = agentStatus.mode === 'LANGCHAIN4J_ONLINE' ? '在线大模型工具链' : '本地推理工具链';
    $('#mcp-status').textContent = agentStatus.mcpEnabled ? 'REMOTE ONLINE' : 'LOCAL FALLBACK';
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
  if (state.activeResumeId) { $('#interview-resume').value = state.activeResumeId; $('#diagnosis-resume').value = state.activeResumeId; }
  if (state.activeJobId) { $('#interview-job').value = state.activeJobId; $('#diagnosis-job').value = state.activeJobId; }
}
function populateInterviewOptions() { populateAllSelects(); if (state.interview) renderInterview(state.interview); }
function populateDiagnosisOptions() { populateAllSelects(); if (state.diagnosis) renderDiagnosis(state.diagnosis); }
function clampMatchWeight(value) { return Math.max(0, Math.min(50, Number(value) || 0)); }
function normalizeMatchWeights(values={}) {
  const result = {
    skills: clampMatchWeight(values.skills ?? DEFAULT_MATCH_WEIGHTS.skills),
    role: clampMatchWeight(values.role ?? DEFAULT_MATCH_WEIGHTS.role),
    experience: clampMatchWeight(values.experience ?? DEFAULT_MATCH_WEIGHTS.experience),
    education: clampMatchWeight(values.education ?? DEFAULT_MATCH_WEIGHTS.education),
    city: clampMatchWeight(values.city ?? DEFAULT_MATCH_WEIGHTS.city),
    evidence: clampMatchWeight(values.evidence ?? DEFAULT_MATCH_WEIGHTS.evidence)
  };
  if (Object.values(result).reduce((sum, value) => sum + value, 0) === 0) result.skills = DEFAULT_MATCH_WEIGHTS.skills;
  return result;
}
function restoreMatchPreferences() {
  try {
    const storedWeights = JSON.parse(localStorage.getItem(MATCH_WEIGHT_KEY) || 'null');
    if (storedWeights) state.matchWeights = normalizeMatchWeights(storedWeights);
  } catch { /* use defaults */ }
  try {
    const storedCities = JSON.parse(localStorage.getItem(EXCLUDED_CITY_KEY) || '[]');
    if (Array.isArray(storedCities)) state.excludedCities = [...new Set(storedCities.map(item => String(item).trim()).filter(Boolean))];
  } catch { state.excludedCities = []; }
}
function saveMatchPreferences() {
  try {
    localStorage.setItem(MATCH_WEIGHT_KEY, JSON.stringify(state.matchWeights));
    localStorage.setItem(EXCLUDED_CITY_KEY, JSON.stringify(state.excludedCities));
  } catch { /* local preference only */ }
}
function readMatchWeightControls() {
  const values = {};
  $$('[data-match-weight]').forEach(input => { values[input.dataset.matchWeight] = Number(input.value); });
  return normalizeMatchWeights(values);
}
function updateMatchWeightPanel() {
  const weights = normalizeMatchWeights(state.matchWeights);
  $$('[data-match-weight]').forEach(input => {
    const key = input.dataset.matchWeight;
    input.value = weights[key];
    const label = $(`[data-weight-value="${key}"]`);
    if (label) label.textContent = weights[key];
  });
  const total = Object.values(weights).reduce((sum, value) => sum + value, 0);
  const labels = { skills:'技能', role:'方向', experience:'经验', education:'学历', city:'城市', evidence:'证据' };
  const effective = Object.entries(weights).map(([key, value]) => ({ key, value, percent:Math.round(value / total * 100) }))
    .sort((a,b) => b.percent - a.percent);
  const summary = effective.slice(0,2).map(item => `${labels[item.key]} ${item.percent}%`).join(' · ');
  if ($('#match-weight-summary')) $('#match-weight-summary').textContent = `有效权重：${summary}`;
  if ($('#match-weight-hint')) $('#match-weight-hint').textContent = `当前权重会自动归一化到 100 分，共 6 个维度`;
  const chips = $('#excluded-city-chips');
  if (chips) chips.innerHTML = state.excludedCities.length
    ? state.excludedCities.map((city,index) => `<span class="excluded-city-chip">${escapeHtml(city)}<button type="button" data-remove-excluded-city="${index}" title="移除">×</button></span>`).join('')
    : '<span class="excluded-city-empty">暂未排除城市</span>';
  if ($('#excluded-city-count')) $('#excluded-city-count').textContent = `${state.excludedCities.length} 个`;
}
async function applyMatchSettings(showToast=true) {
  state.matchWeights = normalizeMatchWeights(readMatchWeightControls());
  saveMatchPreferences(); updateMatchWeightPanel();
  try {
    await loadRecommendations(); renderJobs();
    if (showToast) toast('匹配规则已更新', state.excludedCities.length ? `已排除 ${state.excludedCities.length} 个城市并重新计算岗位` : '已按新的权重重新计算岗位');
  } catch (error) { toast('重新匹配失败', error.message, true); }
}
function addExcludedCity() {
  const input = $('#excluded-city-input');
  const values = input.value.split(/[,，、]/).map(item => item.trim()).filter(Boolean);
  if (!values.length) return;
  state.excludedCities = [...new Set([...state.excludedCities, ...values])];
  input.value = ''; saveMatchPreferences(); updateMatchWeightPanel(); applyMatchSettings(false);
}
function removeExcludedCity(index) {
  state.excludedCities.splice(index,1); saveMatchPreferences(); updateMatchWeightPanel(); applyMatchSettings(false);
}
function resetMatchSettings() {
  state.matchWeights = { ...DEFAULT_MATCH_WEIGHTS }; state.excludedCities = [];
  saveMatchPreferences(); updateMatchWeightPanel(); applyMatchSettings(true);
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
}
function emptyResume() {
  $('#resume-form').reset();
  state.activeResumeId = null; state.skills = [];
  state.projects = [blankProject()]; state.experiences = [];
  $('#skills').value = ''; $('#education').value = '本科';
  renderSkillPicker(); renderProjectEditor(); renderExperienceEditor(); updateResumePreview(); $('#owner-name').focus();
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
  } catch (error) { toast('保存失败', error.message, true); }
}
async function refreshDashboard() { state.dashboard = await api('/api/dashboard'); renderDashboard(state.dashboard); state.recommendations = state.dashboard.topJobs || []; }/* jobs */
async function loadRecommendations() {
  if (!state.activeResumeId) { state.recommendations = []; return; }
  const weights = normalizeMatchWeights(state.matchWeights);
  const params = new URLSearchParams({
    resumeId: state.activeResumeId,
    limit: '50',
    skillWeight: weights.skills,
    roleWeight: weights.role,
    experienceWeight: weights.experience,
    educationWeight: weights.education,
    cityWeight: weights.city,
    evidenceWeight: weights.evidence,
    excludedCities: state.excludedCities.join(',')
  });
  const requestId = ++state.recommendationRequestId;
  const data = await api(`/api/jobs/recommend?${params.toString()}`);
  if (requestId === state.recommendationRequestId) state.recommendations = data;
}
async function renderJobs() {
  const target = $('#job-list');
  if (!target) return;
  if (!state.recommendations.length) { try { await loadRecommendations(); } catch (error) { toast('岗位匹配失败', error.message, true); } }
  const keyword = ($('#job-keyword')?.value || '').toLowerCase();
  const city = $('#job-city-filter')?.value || '';
  const sort = $('#job-sort')?.value || 'match';
  let items = state.recommendations.filter(item => {
    const job = item.job; const haystack = `${job.title} ${job.company} ${job.description} ${(job.skills||[]).join(' ')}`.toLowerCase();
    return (!keyword || haystack.includes(keyword)) && (!city || job.city === city);
  });
  if (sort === 'match') items.sort((a,b) => b.matchScore - a.matchScore);
  else items.sort((a,b) => new Date(b.job.collectedAt) - new Date(a.job.collectedAt));
  $('#job-count').textContent = `${items.length} 个机会`;
  if (!items.length) { target.innerHTML = '<div class="panel loading-card">没有符合条件的岗位，请调整筛选条件。</div>'; return; }
  target.innerHTML = items.map((item,index) => {
    const job = item.job; const matched = new Set(item.matchedSkills || []);
    const dutyPreview = compactText(job.jobDescription || job.description, 105);
    const reqPreview = compactText(job.jobRequirements, 105);
    const skills = (job.skills || []).slice(0,7).map(skill => {
      const normalized = String(skill).toLowerCase().replace(/\./g,'').replace(/\s/g,'');
      const isMatched = (item.matchedSkills || []).some(value => String(value).toLowerCase().replace(/\./g,'').replace(/\s/g,'') === normalized);
      const isMissing = (item.missingSkills || []).some(value => String(value).toLowerCase().replace(/\./g,'').replace(/\s/g,'') === normalized);
      return `<span class="skill-chip ${isMatched?'matched':isMissing?'missing':''}">${escapeHtml(skill)}</span>`;
    }).join('');
    const breakdown = (item.scoreBreakdown || []).filter(component => component.weight > 0).map(component => `<span title="${escapeHtml(component.detail || '')}">${escapeHtml(component.label)} <b>${component.contribution}</b></span>`).join('');
    return `<article class="panel job-card" data-job="${job.id}">
      <div class="job-card-head"><div class="job-company"><div class="company-logo">${escapeHtml(job.company.slice(0,1))}</div><div><h3>${escapeHtml(job.title)}</h3><p>${escapeHtml(job.company)} · ${escapeHtml(job.experience || '经验不限')}</p></div></div><div class="match-badge"><strong>${item.matchScore}%</strong><span>${escapeHtml(item.matchLevel)}</span></div></div>
      <div class="job-meta"><span class="salary">${escapeHtml(job.salary || '薪资面议')}</span><span>${escapeHtml(job.city)}</span><span>${escapeHtml(job.education || '学历不限')}</span></div>
      <div class="job-skills">${skills}</div>
      <div class="job-requirement-preview">
        <div><span>岗位职责 ${job.contentOrigin === 'AI_GENERATED' ? '<i class="ai-badge">AI生成</i>' : ''}</span><p>${escapeHtml(dutyPreview || '暂无职责摘要')}</p></div>
        <div><span>任职要求 ${job.contentOrigin === 'AI_GENERATED' ? '<i class="ai-badge">AI生成</i>' : ''}</span><p>${escapeHtml(reqPreview || '暂无要求摘要')}</p></div>
      </div>
      <div class="job-reasons">${(item.reasons||[]).slice(0,4).map(reason => `<span>• ${escapeHtml(reason)}</span>`).join('')}</div>
      <div class="score-breakdown"><span class="score-breakdown-label">评分构成</span>${breakdown || '<span>暂无拆解</span>'}</div>
      <span class="source-tag">${escapeHtml(job.source || '本地岗位源')}</span>
      <div class="job-card-foot"><button class="ghost-button" data-job-action="detail" data-job-id="${job.id}">岗位详情</button><button class="primary-button" data-job-action="diagnose" data-job-id="${job.id}">诊断匹配</button><button class="primary-button" data-job-action="interview" data-job-id="${job.id}">模拟面试</button></div>
    </article>`;
  }).join('');
}
function openJobModal(jobId) {
  const job = jobById(jobId); if (!job) return;
  $('#job-modal-content').innerHTML = `<span class="eyebrow">JOB DETAIL · MCP FEED</span><h2>${escapeHtml(job.title)}</h2><div class="modal-company">${escapeHtml(job.company)} · ${escapeHtml(job.city)} · ${escapeHtml(job.source || '')}</div>
    <div class="job-detail-grid"><div><span>薪资范围</span><strong>${escapeHtml(job.salary || '面议')}</strong></div><div><span>学历/经验</span><strong>${escapeHtml(job.education || '不限')} / ${escapeHtml(job.experience || '不限')}</strong></div></div>
    <h3>技术需求</h3><div class="job-skills">${(job.skills||[]).map(skill => `<span class="skill-chip matched">${escapeHtml(skill)}</span>`).join('')}</div>
    <h3 style="margin-top:20px">岗位职责 ${job.contentOrigin === 'AI_GENERATED' ? '<span class="ai-badge">AI生成</span>' : ''}</h3><div class="job-description" style="white-space:pre-wrap">${escapeHtml(job.jobDescription || job.description || '暂无岗位职责')}</div>
    <h3 style="margin-top:20px">任职要求 ${job.contentOrigin === 'AI_GENERATED' ? '<span class="ai-badge">AI生成</span>' : ''}</h3><div class="job-description" style="white-space:pre-wrap">${escapeHtml(job.jobRequirements || '暂无结构化任职要求')}</div>
    <div class="job-card-foot"><button class="primary-button" data-job-action="diagnose" data-job-id="${job.id}">用此岗位诊断</button><button class="primary-button" data-job-action="interview" data-job-id="${job.id}">开始模拟面试</button></div>`;
  $('#job-modal').classList.add('open'); $('#job-modal').setAttribute('aria-hidden','false');
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
  target.innerHTML = `<div class="report-head"><div class="diagnosis-score-stack"><div class="big-score-ring" style="--score:${report.overallScore}"><div><strong>${report.overallScore}</strong><span>综合竞争力</span></div></div><div class="score-split"><div><strong>${qualityScore}</strong><span>简历质量</span></div><div><strong>${matchScore ?? '--'}</strong><span>岗位匹配</span><small>${escapeHtml(report.jobMatchLevel || '未选择岗位')}</small></div></div></div><div class="report-overview"><div class="report-title"><h2>${escapeHtml(report.targetRole)}</h2><span class="report-level">${escapeHtml(report.level)}</span></div><p class="report-summary">${escapeHtml(report.summary)}</p><div class="score-formula">${escapeHtml(formula)}</div>${breakdown ? `<div class="score-breakdown">${breakdown}</div>` : ''}<div class="keyword-row">${matched}${missing}</div><div class="strength-row">${(report.strengths || []).map(item => `<span>✦ ${escapeHtml(item)}</span>`).join('')}</div></div></div><div class="score-explain"><div class="explain-head"><span class="eyebrow">WHY THIS SCORE</span><h3>为什么是这个分数</h3></div><div class="explain-grid"><div><h4>简历质量主要失分项</h4>${qualityFocus}</div><div><h4>岗位匹配主要影响因素</h4>${matchFocus || '<p class="explain-empty">未选择岗位或岗位数据不足</p>'}</div></div></div><div class="report-body"><div class="dimension-block"><span class="eyebrow">RESUME QUALITY</span><h3>简历质量维度</h3><div class="dimension-list">${dimensions}</div></div><div class="suggestion-block"><span class="eyebrow">ACTION PRIORITY</span><h3>修改行动清单</h3><div class="suggestion-list">${suggestions}</div></div></div>`;
}

/* learning path */
async function generateLearningPath() {
  const resumeId = state.activeResumeId || Number($('#diagnosis-resume')?.value);
  const role = $('#learning-role')?.value.trim() || 'Java 后端开发工程师';
  if (!resumeId) { toast('无法生成','请先准备一份简历',true); return; }
  try {
    const [path, graph] = await Promise.all([
      post('/api/learning-paths', { resumeId, targetRole:role }),
      api(`/api/skills/graph?role=${encodeURIComponent(role)}&resumeId=${resumeId}`)
    ]);
    state.learningPath = path; state.skillGraph = graph; renderLearningPath(path); renderSkillGraph(graph);
  } catch (error) { toast('生成失败',error.message,true); }
}
function renderLearningPath(path) {
  $('#readiness-score').textContent = `${path.readiness}%`;
  $('#learning-summary').textContent = path.summary;
  $('#learning-modules').innerHTML = path.modules.map((module,index) => `<div class="learning-module ${index===0?'active':''}"><div class="module-index">0${module.order}</div><div class="module-content"><div class="module-title"><h4>${escapeHtml(module.title)}</h4><span>${escapeHtml(module.duration)} · ${escapeHtml(module.status)}</span></div><p>${escapeHtml(module.objective)}</p><div class="topic-tags">${(module.topics||[]).map(topic => `<span>${escapeHtml(topic)}</span>`).join('')}</div><span class="deliverable">交付物：${escapeHtml(module.deliverable)}</span></div></div>`).join('');
}
function renderSkillGraph(graph) {
  const target = $('#skill-graph'); const nodes = [...(graph.nodes||[])].sort((a,b) => b.demand - a.demand);
  const middle = Math.ceil(nodes.length / 2);
  const column = items => items.map(node => `<div class="graph-node ${node.owned?'owned':''}"><div><span>${escapeHtml(node.label)}</span><i><b style="width:${node.demand}%"></b></i></div></div>`).join('');
  target.innerHTML = `<div class="graph-columns"><div class="graph-column"><h4>核心与进阶能力</h4>${column(nodes.slice(0,middle))}</div><div class="graph-column"><h4>工程与方向能力</h4>${column(nodes.slice(middle))}</div></div>`;
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
    state.diagnosis = result.diagnosis; state.recommendations = result.recommendations; state.learningPath = result.learningPath; state.interview = result.interview;
    renderDiagnosis(result.diagnosis); renderDashboardJobs(result.recommendations); renderLearningPath(result.learningPath);
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
$('#resume-form').addEventListener('submit', event => { event.preventDefault(); saveResume(); });
$('#resume-form').addEventListener('input', updateResumePreview);
$('#resume-form').addEventListener('change', updateResumePreview);
window.addEventListener('resize', () => requestAnimationFrame(fitResumePreview));$('#refresh-btn').addEventListener('click', async () => { try { await refreshDashboard(); renderJobs(); toast('数据已刷新','总览数据已同步'); } catch (error) { toast('刷新失败',error.message,true); } });
$('#refresh-jobs').addEventListener('click', refreshJobs);
$('#job-keyword').addEventListener('input', renderJobs);
$('#job-city-filter').addEventListener('change', renderJobs);
$('#job-sort').addEventListener('change', renderJobs);
$('#match-weight-panel').addEventListener('input', event => {
  if (!event.target.matches('[data-match-weight]')) return;
  state.matchWeights = readMatchWeightControls(); saveMatchPreferences(); updateMatchWeightPanel();
});
$('#match-weight-panel').addEventListener('change', event => {
  if (event.target.matches('[data-match-weight]')) applyMatchSettings(false);
});
$('#apply-match-weights').addEventListener('click', () => applyMatchSettings(true));
$('#reset-match-weights').addEventListener('click', resetMatchSettings);
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
$('#generate-path').addEventListener('click', generateLearningPath);
$('#run-agent-top').addEventListener('click', runAgentWorkflow);
$('#run-agent-hero').addEventListener('click', runAgentWorkflow);
$('#workflow-run').addEventListener('click', runAgentWorkflow);
$('#interview-resume').addEventListener('change', event => { state.activeResumeId = Number(event.target.value); $('#diagnosis-resume').value = event.target.value; });
$('#diagnosis-resume').addEventListener('change', event => { state.activeResumeId = Number(event.target.value); $('#interview-resume').value = event.target.value; });

boot();
