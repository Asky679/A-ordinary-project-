import { request, params, download } from './api.js';
import { $, $$, esc, text, date, chips, empty, error, options, link, notify, task, showDialog } from './ui.js';
import { learning } from './practice.js';
import { filterDemo, dataKindLabel } from './auth.js';

const profile = [
  ['ownerName','姓名','text',true],['targetRole','目标岗位','text',true],['city','城市'],
  ['phone','电话','tel'],['email','邮箱','email']
];
const school = [
  ['school','院校'],['major','专业'],['education','学历'],['graduationYear','毕业年份','number'],
  ['educationCategory','学历类别'],['duration','学制'],['studyMode','学习形式'],
  ['college','学院'],['department','系'],['enrollmentDate','入学日期'],
  ['studentStatus','学籍状态'],['expectedGraduationDate','预计毕业日期']
];
const groups = {
  projects: { name:'项目经历', fields:[['name','项目名称','text',true],['role','我的角色'],['techStack','技术栈'],['description','负责内容','textarea'],['challenge','遇到的挑战','textarea'],['highlight','结果或亮点','textarea']] },
  experiences: { name:'工作与实习', fields:[['company','组织或公司'],['position','岗位'],['period','时间'],['description','工作内容','textarea'],['achievement','成果','textarea']] }
};
const sections = [
  ['profile','个人资料'],['school','教育背景'],['skills','技能'],['projects','项目经历'],
  ['experiences','工作与实习'],['selfEvaluation','个人概述']
];
let draft = {};
let currentId = '';
let saved = [];

function field([key,label,type='text',required=false], data, prefix='') {
  const attrs = `name="${prefix+key}" ${required?'required':''}`;
  return `<label>${label}${type==='textarea'
    ? `<textarea ${attrs} rows="3">${esc(data[key])}</textarea>`
    : `<input ${attrs} type="${type}" value="${esc(data[key])}">`}</label>`;
}

function group(key, values) {
  const item = groups[key];
  return `<div class="split"><h3>${item.name}</h3><button class="quiet" type="button" data-add="${key}">＋ 添加</button></div><div class="stack">${(values || []).map((row,i)=>
    `<fieldset class="group"><legend>${item.name} ${i+1}</legend><div class="fields">${item.fields.map(spec=>field(spec,row,`${key}.${i}.`)).join('')}</div><button class="quiet" type="button" data-remove="${key}:${i}">移除这一项</button></fieldset>`).join('')}</div>`;
}

function getDraft(form) {
  const values = new FormData(form);
  const data = {rawText:draft.rawText || ''};
  [...profile,...school].forEach(([key])=>data[key]=String(values.get(key) ?? '').trim());
  data.graduationYear = data.graduationYear ? Number(data.graduationYear) : null;
  data.skills = [...new Set(String(values.get('skills') || '').split(/[,，;；、\n]/).map(s=>s.trim()).filter(Boolean))];
  data.selfEvaluation = String(values.get('selfEvaluation') || '').trim();
  for (const [key,item] of Object.entries(groups)) {
    data[key]=(draft[key] || []).map((_,i)=>Object.fromEntries(item.fields.map(([name])=>
      [name,String(values.get(`${key}.${i}.${name}`) || '').trim()])));
  }
  return data;
}

export function studio(root, signal, query) {
  if (query.has('library')) return library(root,signal);
  const requestedResume = query.get('resume');
  root.innerHTML = `
    <section class="workspace studio-layout">
      <div class="panel">
        <div class="studio-toolbar">
          <div class="split"><label>当前简历<select id="resume-archive"><option value="">新简历</option></select></label>${link('studio?work=1&library=1','简历仓库','quiet')}<button class="quiet" id="import-resume" type="button">导入文件 ↗</button></div>
          <div class="paper-tools" id="resume-tools" aria-label="简历工具"></div>
        </div>
        <nav class="studio-index" aria-label="简历章节">${sections.map(([key,label])=>`<button type="button" data-jump="${key}">${label}</button>`).join('')}</nav>
        <div id="resume-editor"></div>
      </div>
      <aside aria-label="简历预览" id="studio-preview" tabindex="-1"><div class="paper-format" role="group" aria-label="预览版式">
      <button class="quiet" type="button" data-format="editorial">简洁</button><button class="quiet" type="button" data-format="formal">正式</button><button class="quiet" type="button" data-format="english">英文标题</button>
      </div><div class="paper" id="resume-paper"></div></aside>
    </section>`;
  const select = $('#resume-archive',root), editor = $('#resume-editor',root);
  const paper = $('#resume-paper',root), tools = $('#resume-tools',root);
  const previewPane = $('#studio-preview',root), index = $('.studio-index',root);
  let format = 'editorial';
  try { format = localStorage.getItem('atelier-resume-format') || format; } catch {}
  const formats = $$('[data-format]',previewPane);
  function syncFormat() {
    paper.dataset.format = format;
    for (const button of formats) button.setAttribute('aria-pressed',String(button.dataset.format===format));
  }
  function heading(cn,en) { return format==='english' ? en : cn; }
  for (const button of formats) button.onclick = ()=>{
    format = button.dataset.format;
    try { localStorage.setItem('atelier-resume-format',format); } catch {}
    syncFormat();
    preview(draft);
  };
  syncFormat();

  index.addEventListener('click',event=>{
    const button = event.target.closest('[data-jump]');
    if (!button) return;
    const section = $(`[data-edit="${button.dataset.jump}"]`,editor);
    if (section.tagName === 'DETAILS') section.open = true;
    $('input, textarea, button',section)?.focus({preventScroll:true});
    section.scrollIntoView({block:'start'});
  });
  editor.addEventListener('focusin',event=>markPreview(event.target));

  function refreshSelect(){
    select.innerHTML = '<option value="">新简历</option>'+options(saved,currentId,item=>item.ownerName+' · '+item.targetRole);
  }
  function markPreview(control) {
    const section = control?.closest('[data-edit]')?.dataset.edit;
    const fieldset = control?.closest('.group');
    const match = fieldset && /^(\w+)\.(\d+)\./.exec(control.name || $('input',fieldset)?.name || '');
    for (const button of $$('[data-jump]',index)) {
      if (button.dataset.jump === section) button.setAttribute('aria-current','location');
      else button.removeAttribute('aria-current');
    }
    const key = match ? match[1]+'.'+match[2] : section;
    for (const node of $$('[data-paper]',paper)) node.toggleAttribute('data-editing',node.dataset.paper === key);
    const active = $('[data-editing]',paper);
    if (!active || previewPane.clientHeight >= previewPane.scrollHeight) return;
    const region = previewPane.getBoundingClientRect(), bounds = active.getBoundingClientRect();
    if (bounds.bottom > region.bottom || bounds.top < region.top) {
      previewPane.scrollTop += bounds.top-region.top-20;
    }
  }

  function preview(data, control){
    paper.innerHTML = `<header class="paper-block" data-paper="profile"><h2>${text(data.ownerName,'你的姓名')}</h2><p class="paper-role">${text(data.targetRole,'目标岗位')}</p>
      <p class="muted">${[data.city,data.phone,data.email].filter(Boolean).map(esc).join(' · ')}</p></header>
      ${[data.school,data.major,data.education,data.graduationYear].some(Boolean)?`<section class="paper-block" data-paper="school"><h3>${heading('教育背景','Education')}</h3><p>${[data.school,data.major,data.education,data.graduationYear].filter(Boolean).map(esc).join(' · ')}</p></section>`:''}
      ${data.skills?.length?`<section class="paper-block" data-paper="skills"><h3>${heading('技能','Skills')}</h3><div>${chips(data.skills)}</div></section>`:''}
      ${data.projects?.length?`<section class="paper-block" data-paper="projects"><h3>${heading('项目经历','Projects')}</h3>${data.projects.map((item,i)=>`<div class="paper-block" data-paper="projects.${i}"><h4>${text(item.name,'待填写项目名称')}</h4><small>${[item.role,item.techStack].filter(Boolean).map(esc).join(' · ')}</small><p>${esc(item.description)}</p><p>${esc(item.highlight)}</p></div>`).join('')}</section>`:''}
      ${data.experiences?.length?`<section class="paper-block" data-paper="experiences"><h3>${heading('工作与实习','Experience')}</h3>${data.experiences.map((item,i)=>`<div class="paper-block" data-paper="experiences.${i}"><h4>${[item.company,item.position,item.period].filter(Boolean).map(esc).join(' · ') || '待填写工作或实习'}</h4><p>${esc(item.description)}</p><p>${esc(item.achievement)}</p></div>`).join('')}</section>`:''}
      ${data.selfEvaluation?`<section class="paper-block" data-paper="selfEvaluation"><h3>${heading('个人概述','Profile')}</h3><p>${esc(data.selfEvaluation)}</p></section>`:''}`;
    markPreview(control);
  }
  function render(){
    editor.innerHTML = `<form class="flow" id="resume-form">
      <section data-edit="profile"><h2>个人资料</h2><div class="fields">${profile.map(spec=>field(spec,draft)).join('')}</div></section>
      <details class="disclosure" data-edit="school" ${draft.school?'open':''}><summary>教育背景</summary><div class="fields">${school.map(spec=>field(spec,draft)).join('')}</div></details>
      <section data-edit="skills">${field(['skills','技能（用逗号分隔）'],{skills:(draft.skills || []).join('，')})}</section>
      <section data-edit="projects">${group('projects',draft.projects)}</section>
      <section data-edit="experiences">${group('experiences',draft.experiences)}</section>
      <section data-edit="selfEvaluation">${field(['selfEvaluation','个人概述','textarea'],draft)}</section>
      <button class="button primary" type="submit">保存简历</button>
    </form>`;
    const form = $('form',editor);
    form.addEventListener('input',event=>{
      draft=getDraft(form);
      preview(draft,event.target);
    });
    $$('[data-add]',form).forEach(button=>button.addEventListener('click',()=>{
      draft=getDraft(form);
      (draft[button.dataset.add] ||= []).push({});
      render();
      const inputs=$$(`[name^="${button.dataset.add}."]`,editor);
      inputs.at(-(groups[button.dataset.add].fields.length))?.focus();
    }));
    $$('[data-remove]',form).forEach(button=>button.addEventListener('click',()=>{
      draft=getDraft(form);
      const [key,index]=button.dataset.remove.split(':');
      draft[key].splice(Number(index),1);
      render();
    }));
    form.addEventListener('submit',task($('[type=submit]',form),async()=>{
      const submitted=getDraft(form), id=currentId;
      const item=await request('/resumes'+(id?'/'+id:''),{method:id?'PUT':'POST',data:submitted});
      saved=[item,...saved.filter(entry=>entry.id!==item.id)];
      if(currentId===id) {
        currentId=String(item.id);
        if(JSON.stringify(getDraft(form))===JSON.stringify(submitted)) {
          draft=item;
          render();
        }
      }
      refreshSelect();
      if (paper.isConnected && !document.body.classList.contains('no-motion')) {
        paper.animate?.(
          [{ boxShadow:'0 0 0 3px #ab8c565e' },{ boxShadow:'0 14px 34px #3328191a' }],
          { duration:630, easing:'ease-out' }
        );
      }
      notify('简历已保存。');
    }));
    preview(draft);
    renderTools();
  }

  function renderTools() {
    tools.innerHTML = `<button type="button" class="quiet studio-preview-action" data-tool="preview">预览简历 ↓</button>
      <button type="button" class="quiet" data-tool="ats">ATS 检查</button>
      <button type="button" class="quiet" data-tool="education">上传学历证明</button>`+
      (currentId? `<a class="quiet" href="/api/resumes/${esc(currentId)}/pdf" download>导出 PDF</a>
      <button type="button" class="quiet" data-tool="versions">版本档案</button>
      <button type="button" class="quiet" data-tool="guidance">写作指导</button>
      <button type="button" class="quiet" data-tool="locks">事实保护</button>`:'');
    $$('[data-tool]',tools).forEach(button=>button.addEventListener('click',task(button,async()=>{
      const id=currentId;
      if(button.dataset.tool==='preview') {
        const preview = $('#studio-preview',root);
        preview.scrollIntoView({block:'start'});
        preview.focus({preventScroll:true});
      }
      if(button.dataset.tool==='ats') openAts();
      if(button.dataset.tool==='versions') await versions(id,()=>{
        request('/resumes/'+id).then(item=>{
          saved=[item,...saved.filter(entry=>entry.id!==item.id)];
          if(currentId===id){draft=item;render();}
          refreshSelect();
        }).catch(cause=>notify(cause.message,true));
      });
      if(button.dataset.tool==='education') {
        if (!id) {
          notify('请先保存简历，再上传学历证明。', true);
          return;
        }
        await education(id,values=>{
          if (currentId!==id) return;
          draft={...draft,...values};
          render();
          $('#dialog').close();
          const section = $('[data-edit="school"]',editor);
          section.open = true;
          $('input',section)?.focus({preventScroll:true});
          section.scrollIntoView({block:'nearest'});
          notify('已填入草稿，核对后保存简历。');
        });
      }
      if(button.dataset.tool==='locks') await locks(id);
      if(button.dataset.tool==='guidance') {
        let projectIndex;
        await guidance(id,draft.targetRole,(key,value)=>{
          if (currentId!==id || (projectIndex===undefined && !value)) return;
          const projects = draft.projects ||= [];
          if (projectIndex===undefined) projectIndex = projects.length;
          const entry = projects[projectIndex] ||= {};
          entry[key] = value;
          if (!entry.description && !entry.highlight) {
            projects.splice(projectIndex,1);
            projectIndex = undefined;
          }
          render();
          const control = $$('textarea',editor).find(node=>node.name === 'projects.'+projectIndex+'.'+key);
          if (control) {
            markPreview(control);
          }
        });
      }
    })));
  }

  select.addEventListener('change',()=>{
    const item=saved.find(row=>String(row.id)===select.value);
    currentId=select.value;
    draft=item || {};
    render();
  });
  $('#import-resume',root).addEventListener('click',()=>{
    const dialog=showDialog('导入简历',`<form class="flow"><label>选择 PDF、DOCX 或图片<input type="file" name="file" accept=".pdf,.docx,.txt,image/*" required></label><label>图片或扫描件的文字（可选）<textarea name="extractedText" rows="3" placeholder="可粘贴已识别的文字"></textarea></label><button class="button primary">解析文件</button></form><div class="stack" id="import-result"></div>`);
    const form=$('form',dialog);
    form.addEventListener('submit',task($('button',form),async()=>{
      const payload=new FormData(form);
      const file=payload.get('file');
      if(file?.type.startsWith('image/') && !String(payload.get('extractedText') || '').trim()) {
        payload.set('extractedText',await recognizeImage(file));
        payload.set('extractionMethod','OCR_BROWSER');
      } else if(payload.get('extractedText')) {
        payload.set('extractionMethod','CLIENT_TEXT');
      }
      const parsed=await request('/resumes/import',{method:'POST',data:payload});
      $('#import-result',dialog).innerHTML=`<p>${esc(parsed.ownerName || '未识别姓名')} · ${esc(parsed.targetRole || '待填写目标岗位')}</p><ul>${(parsed.warnings||[]).map(item=>`<li>${esc(item)}</li>`).join('')}</ul><button type="button" class="button" id="use-import">填入新草稿</button>`;
      $('#use-import',dialog).addEventListener('click',()=>{
        draft=parsed;currentId='';refreshSelect();render();$('#dialog').close();
      });
    }));
  });
  refreshSelect();
  render();
  request('/resumes',{signal}).then(items=>{ if(!signal.aborted){
    saved=filterDemo(items);
    const selected = saved.find(item=>String(item.id)===requestedResume);
    if (selected && currentId!==requestedResume) { currentId=requestedResume; draft=selected; render(); }
    refreshSelect();
  } }).catch(cause=>{
    if(cause.name!=='AbortError') select.setAttribute('title',cause.message);
  });
}


async function library(root,signal) {
  root.innerHTML = `<section class="workspace library-desk"><header class="split"><div><h2>已保存的简历</h2></div>${link('studio?work=1','打开编辑台 →','button primary')}</header>
    <label>查找简历<input type="search" placeholder="姓名、目标岗位或城市"></label>
    <div class="library-files" aria-live="polite">${empty('正在读取简历档案')}</div></section>`;
  const list = $('.library-files',root), filter = $('input',root);
  const items = filterDemo(await request('/resumes',{signal}));
  if (signal.aborted) return;
  function draw() {
    const keyword = filter.value.trim().toLowerCase();
    const visible = items.filter(item=>[item.ownerName,item.targetRole,item.city].some(value=>String(value||'').toLowerCase().includes(keyword)));
    list.innerHTML = visible.length ? visible.map(item=>`<article class="library-file">
      <div><h3>${esc(item.ownerName)}</h3><p>${esc(item.targetRole)}</p><small>${[dataKindLabel(item),item.city,date(item.updatedAt)].filter(Boolean).map(esc).join(' · ')}</small></div>
      <div class="actions">${link('studio?work=1&resume='+encodeURIComponent(item.id),'打开编辑 →','button')}
        <a class="quiet" href="/api/resumes/${encodeURIComponent(item.id)}/pdf" download>导出 PDF</a>
        <button class="quiet" data-remove-resume="${esc(item.id)}" type="button">删除</button></div></article>`).join('')
      : empty(keyword?'没有匹配的简历':'还没有保存简历');
  }
  list.addEventListener('click',event=>{
    const button = event.target.closest('[data-remove-resume]');
    if (!button) return;
    const item = items.find(row=>String(row.id)===button.dataset.removeResume);
    const dialog = showDialog('删除简历',`<p>确认删除「${esc(item.ownerName)} · ${esc(item.targetRole)}」？</p><button class="button" type="button">确认删除</button>`);
    const confirm = $('button',dialog);
    confirm.onclick = task(confirm,async()=>{
      await request('/resumes/'+encodeURIComponent(item.id),{method:'DELETE',signal});
      if (signal.aborted) return;
      items.splice(items.indexOf(item),1);
      saved = saved.filter(row=>row.id!==item.id);
      if (currentId===String(item.id)) { currentId=''; draft={}; }
      $('#dialog').close();
      draw();
      filter.focus();
    });
  });
  filter.oninput = draw;
  draw();
}

async function versions(id, restored) {
  const dialog=showDialog('版本档案',`<form class="flow"><label>版本名<input name="label" maxlength="64" placeholder="例如：秋招投递版"></label><label>备注<textarea name="note" rows="2"></textarea></label><button class="button primary">保存当前版本</button></form><div id="version-list"></div>`);
  const form=$('form',dialog), list=$('#version-list',dialog);
  async function refresh(){
    const items=await request('/resumes/'+id+'/versions');
    list.innerHTML=items.length ? `<div class="stack">${items.map(item=>`<div class="note split"><div><strong>${text(item.label,'版本 '+item.versionNo)}</strong><p>${date(item.createdAt)} · ${esc(item.note)}</p></div><button class="quiet" type="button" data-restore="${esc(item.id)}">恢复</button></div>`).join('')}</div>` : empty('尚未保存历史版本');
    if(items.length>1){
      list.insertAdjacentHTML('beforeend',`<form id="compare" class="fields"><label>原版本<select name="left">${options(items,items[1].id,item=>item.label||'版本 '+item.versionNo)}</select></label><label>对比版本<select name="right">${options(items,items[0].id,item=>item.label||'版本 '+item.versionNo)}</select></label><button class="quiet">查看差异</button></form><div id="changes"></div>`);
      const compare=$('#compare',list);
      compare.addEventListener('submit',task($('button',compare),async()=>{
        const result=await request('/resumes/'+id+'/versions/compare?'+new URLSearchParams(new FormData(compare)));
        const changes = result.diffs || [];
        const output = $('#changes',list);
        output.innerHTML = changes.length ? `<div class="version-spread">
          ${[[result.left,'原版本','left'],[result.right,'对比版本','right']].map(([version,label,key])=>`
            <section class="version-sheet"><h3>${label} · ${text(version.label,'版本 '+version.versionNo)}</h3>
            ${changes.map(item=>`<div class="version-change"><strong>${esc(item.label)}</strong><p>${text(item[key],'未填写')}</p></div>`).join('')}
            </section>`).join('')}</div>` : empty('这两个版本没有差异');
        output.scrollIntoView({block:'nearest'});
      }));
    }
    $$('[data-restore]',list).forEach(button=>button.addEventListener('click',task(button,async()=>{
      if(!confirm('把当前简历恢复为这个历史版本？')) return;
      await request('/resumes/'+id+'/versions/'+button.dataset.restore+'/restore',{method:'POST'});
      restored();
      $('#dialog').close();
      notify('已恢复历史版本。');
    })));
  }
  form.addEventListener('submit',task($('button',form),async()=>{
    await request('/resumes/'+id+'/versions',{method:'POST',data:Object.fromEntries(new FormData(form))});
    form.reset();
    await refresh();
  }));
  await refresh();
}

function openAts(){
  const dialog=showDialog('ATS 检查','<form class="flow"><label>上传 PDF 简历<input name="file" type="file" accept=".pdf,application/pdf" required></label><button class="button primary">检查简历</button></form><div id="ats-result"></div>');
  const form=$('form',dialog);
  form.addEventListener('submit',task($('button',form),async()=>{
    const report=await request('/ats/check',{method:'POST',data:new FormData(form)});
    const target=$('#ats-result',dialog);
    target.innerHTML=`<h3>${esc(report.atsScore)} 分 · ${esc(report.level)}</h3>${(report.issues||[]).map(item=>`<div class="note"><strong>${esc(item.issue)}</strong><p>${esc(item.action)}</p></div>`).join('')}<button class="quiet" type="button">下载检查报告</button>`;
    const button=$('button',target);
    button.addEventListener('click',task(button,async()=>download(await request('/ats/report',{method:'POST',data:report,file:true}),'ATS-检查报告.pdf')));
  }));
}

async function education(id,apply){
  const dialog=showDialog('教育核验','<form class="flow"><label>上传教育材料<input name="file" type="file" accept=".pdf,image/*" required></label><label>材料 OCR 文本（可选）<textarea name="ocrText" rows="3"></textarea></label><button class="button primary">核验材料</button></form><div id="education-result"></div>');
  const form=$('form',dialog), target=$('#education-result',dialog);
  function show(result) {
    if (!result) { target.innerHTML=empty('暂无核验记录'); return; }
    const values = Object.fromEntries(school.map(([key])=>[key,result['proof'+key[0].toUpperCase()+key.slice(1)]]).filter(([,value])=>value));
    target.innerHTML=`<h3>${esc(result.statusLabel || result.status)}</h3><p>${esc(result.summary)}</p>
      ${(result.checks||[]).map(item=>`<div class="note"><strong>${esc(item.label)}</strong><p>${esc(item.resumeValue)} · ${esc(item.proofValue)}</p></div>`).join('')}
      <div class="actions">${Object.keys(values).length?'<button class="quiet" data-fill-proof type="button">将识别字段填入草稿</button>':''}
      <button class="quiet" data-clear-proof type="button">取消核验记录</button></div>`;
    $('[data-fill-proof]',target)?.addEventListener('click',()=>apply(values));
    const button=$('[data-clear-proof]',target);
    button.addEventListener('click',task(button,async()=>{
      if (!confirm('取消当前简历的教育核验记录？')) return;
      await request('/resumes/'+id+'/education-verification',{method:'DELETE'});
      show(null);
    }));
  }
  form.addEventListener('submit',task($('button',form),async()=>{
    const data=new FormData(form), file=data.get('file');
    if (file?.type.startsWith('image/') && !String(data.get('ocrText')||'').trim()) {
      target.textContent='正在识别材料文字…';
      try { data.set('ocrText',await recognizeImage(file)); }
      catch (cause) { target.textContent=cause.message; return; }
    }
    show(await request('/resumes/'+id+'/education-verification',{method:'POST',data}));
  }));
  show(await request('/resumes/'+id+'/education-verification'));
}

let ocrLoader;
function recognizeImage(file) {
  if(!ocrLoader) {
    ocrLoader=new Promise((resolve,reject)=>{
      const script=document.createElement('script');
      script.src='https://cdn.jsdelivr.net/npm/tesseract.js@5.1.1/dist/tesseract.min.js';
      script.onload=()=>resolve(window.Tesseract);
      script.onerror=()=>reject(new Error('OCR 组件加载失败，可以粘贴已识别文字后重试。'));
      document.head.append(script);
    }).catch(cause=>{ocrLoader=null;throw cause;});
  }
  return ocrLoader.then(engine=>engine.recognize(file,'chi_sim+eng')).then(result=>result.data.text);
}

async function locks(id){
  const fields=[['ownerName','姓名'],['phone','电话'],['email','邮箱'],['school','院校'],['education','学历'],['major','专业'],['graduationYear','毕业年份']];
  const active=await request('/resumes/'+id+'/fact-locks');
  const dialog=showDialog('事实保护',`<form class="flow"><p>已锁定的字段不能通过普通编辑修改。</p>
    <div class="checklist">${fields.map(([key,label])=>`<label><input type="checkbox" name="fields" value="${key}" ${active.includes(key)?'checked':''}>${label}</label>`).join('')}</div><button class="button primary">保存保护设置</button></form>`);
  const form=$('form',dialog);
  form.addEventListener('submit',task($('button',form),async()=>{
    await request('/resumes/'+id+'/fact-locks',{method:'PUT',data:{fields:new FormData(form).getAll('fields')}});
    $('#dialog').close();
    notify('事实保护已更新。');
  }));
}

async function guidance(id, role, apply){
  const dialog=showDialog('写作指导','<label>准备方向<select id="guidance-role"></select></label><div id="guidance-result" aria-live="polite"><p class="muted">正在读取写作建议…</p></div><button id="make-plan" type="button" class="button">生成学习路线</button><div id="plan"></div>');
  const button=$('#make-plan',dialog), roleInput=$('#guidance-role',dialog);
  const directions = [...new Set([role || 'Java 后端开发'])];
  const renderRoles = selected=>{
    roleInput.innerHTML = options(directions.map(name=>({id:name})),selected,item=>item.id);
  };
  renderRoles(directions[0]);
  request('/resume-guidance/directions').then(items=>{
    if (!roleInput.isConnected) return;
    const selected=roleInput.value;
    for (const item of items) if (!directions.includes(item)) directions.push(item);
    renderRoles(selected);
  }).catch(()=>{});
  button.addEventListener('click',task(button,async()=>{
    const plan=await request('/learning-paths',{method:'POST',data:{resumeId:Number(id),targetRole:roleInput.value}});
    $('#plan',dialog).innerHTML=learning(plan);
  }));
  dialog.insertAdjacentHTML('beforeend',`<hr><h3>整理一个项目</h3><form class="flow" id="draft-project"><div class="fields">
    ${[['background','项目背景','textarea'],['responsibility','我的职责','textarea'],['techStack','技术栈'],['challenge','挑战','textarea'],['result','结果','textarea']].map(spec=>field(spec,{})).join('')}</div>
    <div class="checklist"><label><input type="checkbox" name="useAi"> 使用 AI 润色草稿</label></div>
    <button class="button">生成项目草稿</button></form><div id="draft-result" aria-live="polite"></div>`);
  const form=$('#draft-project',dialog);
  const applied = new Map();
  form.addEventListener('submit',task($('button',form),async()=>{
    const values=Object.fromEntries(new FormData(form));
    if (!Object.entries(values).some(([key,value])=>key!=='useAi' && value.trim())) {
      throw new Error('先填写至少一条你能确认的项目事实。');
    }
    const target = $('#draft-result',dialog);
    target.setAttribute('aria-busy','true');
    try {
      const result = await request('/resume-guidance/draft',{method:'POST',data:{role:roleInput.value,...values,useAi:form.elements.useAi.checked}});
      if (!target.isConnected) return;
      target.innerHTML = `<div class="draft-proof"><nav aria-label="选择要审阅的建议">
        ${[['description','项目描述'],['highlight','项目亮点']].filter(([key])=>result[key] || applied.has(key)).map(([key,label])=>
          `<button type="button" data-proof="${key}" aria-pressed="false">${label}</button>`).join('')}</nav>
        <div class="draft-paper"><small>项目经历 · 新项目段落</small><div class="draft-annotation"></div></div>
        <button type="button" class="quiet draft-return">回到简历</button></div>`;
      const proof = $('.draft-annotation',target);
      function show(key) {
        for (const button of $$('[data-proof]',target)) button.setAttribute('aria-pressed',String(button.dataset.proof===key));
        const previous = applied.get(key);
        proof.innerHTML = `<dl><dt>当前草稿</dt><dd>${text(previous,'尚未填写')}</dd>
          <dt>本次建议</dt><dd>${text(result[key],'暂无建议')}</dd></dl>
          ${result[key] && previous!==result[key]?'<button class="button" type="button" data-adopt>采纳这条</button>':''}
          ${previous?'<button class="quiet" type="button" data-undo>撤回这条</button>':''}`;
        $('[data-adopt]',proof)?.addEventListener('click',()=>{
          applied.set(key,result[key]);
          apply(key,result[key]);
          show(key);
          $('[data-undo]',proof)?.focus();
        });
        $('[data-undo]',proof)?.addEventListener('click',()=>{
          applied.delete(key);
          apply(key,'');
          show(key);
          $('[data-adopt]',proof)?.focus();
        });
      }
      for (const button of $$('[data-proof]',target)) button.onclick = ()=>show(button.dataset.proof);
      $('.draft-return',target).onclick = ()=>$('#dialog').close();
      $('[data-proof]',target)?.click();
    } finally {
      target.removeAttribute('aria-busy');
    }
  }));
  const target = $('#guidance-result',dialog);
  let revision = 0;
  async function refresh() {
    const stamp = ++revision;
    target.textContent = '正在读取写作建议…';
    try {
      const report = await request('/resume-guidance/'+id+'?'+params({role:roleInput.value}));
      if (!target.isConnected || stamp!==revision) return;
      target.innerHTML=`<h3>可以补充的技能</h3>${chips(report.missingSkills) || '<p>暂未发现缺口。</p>'}
        <h3>把经历写具体</h3>${(report.questions||[]).map(item=>`<div class="note"><strong>${esc(item.label)}</strong><p>${esc(item.hint)}</p></div>`).join('')}`;
    } catch (cause) {
      if (target.isConnected && stamp===revision) target.textContent = cause.message;
    }
  }
  roleInput.addEventListener('change',refresh);
  await refresh();
}
