import { request } from './api.js';
import { $, $$, esc, empty, error, task, external } from './ui.js';

const draft = { source:'github', repoUrl:'', description:'', file:null };
let review;

export function project(root, signal) {
  root.innerHTML = `
    <div class="workspace columns project-layout">
      <form class="panel flow" id="project-form">
        <h2>打开一个项目</h2>
        <fieldset class="project-mode"><legend>源码来源</legend>
          <label><input name="source" type="radio" value="github">GitHub 仓库</label>
          <label><input name="source" type="radio" value="zip">ZIP 文件</label>
        </fieldset>
        <label class="project-source">公开 GitHub 仓库<input name="repoUrl" type="url" placeholder="https://github.com/owner/project" required></label>
        <label class="project-source">上传 Java 项目 ZIP（5 MB 以内）<input name="file" type="file" accept=".zip,application/zip"></label>
        <label>你负责了什么？（可选）<textarea name="projectDescription" maxlength="2000" rows="4"></textarea></label>
        <button class="button primary" type="submit">生成源码追问</button>
      </form>
      <section class="panel" id="project-result" aria-label="项目分析结果">
        ${empty('这里会显示追问与源码证据','输入仓库地址，或上传 ZIP 文件。')}
      </section>
    </div>`;
  const form = $('#project-form',root), result = $('#project-result',root);
  const {source, repoUrl, file, projectDescription} = form.elements;
  source.value = draft.source;
  repoUrl.value = draft.repoUrl;
  projectDescription.value = draft.description;
  if (draft.file && typeof DataTransfer !== 'undefined') {
    const transfer = new DataTransfer();
    transfer.items.add(draft.file);
    file.files = transfer.files;
  }

  function updateSource() {
    draft.source = source.value;
    const zip = source.value === 'zip';
    repoUrl.disabled = repoUrl.closest('label').hidden = zip;
    file.disabled = file.closest('label').hidden = !zip;
    file.required = zip;
  }
  updateSource();
  form.addEventListener('change', event => {
    if (event.target.name === 'source') updateSource();
    if (event.target === file) draft.file = file.files[0] || null;
  });
  form.addEventListener('input', () => {
    draft.repoUrl = repoUrl.value;
    draft.description = projectDescription.value;
  });
  if (review) showReview(result,review,signal);

  form.addEventListener('submit',task($('button[type="submit"]',form),async () => {
    const values = new FormData(form);
    const archive = values.get('file');
    const url = String(values.get('repoUrl') || '').trim();
    const description = projectDescription.value.trim();
    if (!archive?.size && !url) throw new Error('请输入公开仓库地址或选择 ZIP。');
    if (archive?.size > 5_000_000) throw new Error('ZIP 文件不能超过 5 MB。');
    result.dataset.phase = 'loading';
    result.innerHTML = `<div role="status">${empty('正在读取源码','提取源码结构和可追问的实现细节。')}</div>`;
    const set = await (archive?.size
      ? request('/project-questions/upload',{method:'POST',data:values,signal})
      : request('/project-questions',{method:'POST',data:{repoUrl:url,projectDescription:description},signal}));
    if (signal.aborted) return;
    review = {set, description, sourceName:archive?.size ? archive.name : set.repoUrl, dimension:'', answers:new Map(), replies:new Map(), practiced:new Set()};
    showReview(result,review,signal);
    $('h2',result).focus({preventScroll:true});
  },cause => {
    result.dataset.phase = 'error';
    result.innerHTML = `<div role="alert">${error(cause)}</div>`;
  }));
}

function showReview(result, analysis, signal) {
  const {set, description, sourceName, answers, replies, practiced} = analysis;
  const questions = set.questions || [];
  const groups = new Map();
  for (const question of questions) {
    groups.set(question.dimension,(groups.get(question.dimension) || 0) + 1);
  }
  result.dataset.phase = 'ready';
  result.innerHTML = `<div class="project-summary">
    <div><h2 tabindex="-1">源码追问</h2><p class="muted project-origin">${esc(sourceName)}</p>
    <p class="muted">分析了 ${esc(set.analyzedFiles)} 个 Java 文件 · ${questions.length} 个问题</p></div>
    ${groups.size>1 ? `<label>考查维度<select id="project-dimension"><option value="">所有维度</option>
      ${[...groups].map(([name,count])=>`<option value="${esc(name)}">${esc(name)} · ${count}</option>`).join('')}
    </select></label>` : ''}
  </div><div id="source-explorer"></div><div id="project-questions"></div>`;
  const list = $('#project-questions',result);
  const filter = $('#project-dimension',result);

  function showQuestions() {
    const visible = questions.filter(question=>!analysis.dimension || analysis.dimension === question.dimension);
    list.innerHTML = visible.length ? visible.map(question=>`
      <details class="disclosure project-question">
        <summary><small>${esc(question.dimension)} · ${question.parentId?'递进问题':'主问题'}${practiced.has(question.id)?' · 已练习':''}</small>${esc(question.question)}</summary>
        ${question.signal ? `<p class="project-signal"><strong>考查重点</strong> ${esc(question.signal)}</p>` : ''}
        ${evidence(question.evidence, question.dimension)}
        <form class="flow follow-up" data-question="${esc(question.id)}">
          <label>用自己的话作答<textarea name="answer" rows="3" minlength="8" maxlength="2000" required>${esc(answers.get(question.id))}</textarea></label>
          <button class="button" type="submit">继续追问</button>
          <div class="follow-result" aria-live="polite">${reply(replies.get(question.id))}</div>
        </form>
      </details>`).join('') : empty('这份源码暂无可练习的问题');
    for (const form of $$('.follow-up',list)) {
      const questionId = form.dataset.question;
      const answerInput = form.elements.answer;
      answerInput.addEventListener('input',()=>answers.set(questionId,answerInput.value));
      form.addEventListener('submit',task($('button',form),async () => {
        const answer = answerInput.value.trim();
        if (answer.length < 8) throw new Error('写下至少 8 个字，再继续追问。');
        const follow = await request('/project-questions/follow-up',{method:'POST',signal,data:{
          repoUrl:set.repoUrl, sha:set.sha, projectDescription:description, questionId, answer
        }});
        if (signal.aborted) return;
        replies.set(questionId,follow);
        practiced.add(questionId);
        const visibleForm = $$('.follow-up',list).find(item=>item.dataset.question === questionId);
        if (visibleForm) {
          $('.follow-result',visibleForm).innerHTML = reply(follow);
          const label = $('summary small',visibleForm.closest('.project-question'));
          if (!label.textContent.endsWith('已练习')) label.textContent += ' · 已练习';
        }
      }));
    }
  }

  if (filter) {
    filter.value = analysis.dimension;
    filter.addEventListener('change',()=>{
      analysis.dimension = filter.value;
      showQuestions();
    });
  }
  showQuestions();
  sourceExplorer($('#source-explorer',result),questions,list,filter);
}

function sourceExplorer(root, questions, list, filter) {
  const paths = new Map();
  for (const question of questions) {
    const path = question.evidence?.path;
    if (!path) continue;
    if (!paths.has(path)) paths.set(path,[]);
    paths.get(path).push(question);
  }
  if (!paths.size) return;
  const tree = new Map();
  for (const path of paths.keys()) {
    let branch = tree;
    for (const part of path.split('/').slice(0,-1)) {
      if (!branch.has(part)) branch.set(part,new Map());
      branch = branch.get(part);
    }
    branch.set(path,null);
  }
  function directory(branch, depth = 0) {
    return [...branch].map(([name,children])=>children
      ? `<details ${depth<2?'open':''}><summary>${esc(name)}</summary>${directory(children,depth+1)}</details>`
      : `<button type="button" class="source-file" data-source="${esc(name)}" aria-pressed="false">${esc(name.split('/').at(-1))}</button>`
    ).join('');
  }
  root.innerHTML = `<section class="source-board"><nav aria-label="源码证据目录">
    <h3>源码证据目录</h3><p class="muted">${paths.size} 个含追问证据的文件</p>${directory(tree)}
    </nav><div class="source-inspector"></div></section>`;
  const inspector = $('.source-inspector',root);
  const files = $$('[data-source]',root);
  function inspect(path) {
    for (const file of files) file.setAttribute('aria-pressed',String(file.dataset.source===path));
    const entries = paths.get(path);
    const symbols = [...new Map(entries.map(question=>[question.evidence.symbol,question.evidence])).values()];
    inspector.innerHTML = `<p class="muted project-origin">${esc(path)}</p><label>代码位置<select>
      ${symbols.map((item,index)=>`<option value="${index}">${esc(item.symbol)} · ${esc(item.startLine)} 行</option>`).join('')}
      </select></label><div class="source-symbol"></div>`;
    const select = $('select',inspector), symbol = $('.source-symbol',inspector);
    function show(call = '') {
      const item = symbols[Number(select.value)];
      const linked = entries.filter(question=>question.evidence.symbol===item.symbol);
      const calls = [...new Set(item.calls || [])];
      symbol.innerHTML = `${calls.length ? `<div class="source-calls"><strong>片段中的调用</strong>
        ${calls.map((name,index)=>`<button type="button" data-call="${index}" aria-pressed="${call===name}">${esc(name)}</button>`).join('')}</div>` : ''}
        <div class="source-prompts"><strong>从这里开始练习</strong>
          ${linked.map((question,index)=>`<button class="quiet" type="button" data-prompt="${index}">${esc(question.question)}</button>`).join('')}</div>
        ${evidence(item,linked[0]?.dimension,call,true)}`;
      const highlight = call && $('pre mark',symbol);
      if (highlight) {
        const code = highlight.closest('pre');
        code.scrollTop += highlight.getBoundingClientRect().top-code.getBoundingClientRect().top-28;
      }
      for (const button of $$('[data-call]',symbol)) button.onclick = ()=>{
        const index = Number(button.dataset.call);
        show(calls[index]);
        $$('[data-call]',symbol)[index]?.focus();
      };
      for (const button of $$('[data-prompt]',symbol)) button.onclick = ()=>{
        if (filter?.value) {
          filter.value = '';
          filter.dispatchEvent(new Event('change'));
        }
        const id = linked[Number(button.dataset.prompt)].id;
        const question = $$('.follow-up',list).find(form=>form.dataset.question===String(id))?.closest('details');
        if (!question) return;
        question.open = true;
        $('summary',question).focus();
      };
    }
    select.onchange = ()=>show();
    show();
  }
  for (const file of files) file.onclick = ()=>inspect(file.dataset.source);
  inspect(files[0].dataset.source);
  for (let node=files[0].parentElement; node && node!==root; node=node.parentElement) {
    if (node.tagName==='DETAILS') node.open = true;
  }
}

function reply(follow) {
  return follow ? `<h3>${esc(follow.question)}</h3><p>${esc(follow.reason)}</p>${evidence(follow.evidence)}` : '';
}

function evidence(item, dimension, call = '', open = false) {
  if (!item) return '';
  const preferred = call || (dimension === '故障与边界' ? item.failure : item.decision);
  const snippet = item.snippet || '';
  const anchor = preferred && snippet.includes(preferred) ? preferred : '';
  const index = anchor ? snippet.indexOf(anchor) : -1;
  const code = index < 0 ? esc(snippet)
    : esc(snippet.slice(0,index))+'<mark>'+esc(anchor)+'</mark>'+esc(snippet.slice(index+anchor.length));
  return `<details class="project-evidence" ${open?'open':''}>
    <summary><span>源码证据</span><code>${esc(item.symbol)}</code></summary>
    <p class="muted project-origin">${esc(item.path)} · 第 ${esc(item.startLine)}–${esc(item.endLine)} 行 ${external(item.url,'查看仓库原文')}</p>
    ${anchor ? `<p class="project-anchor"><strong>关键片段</strong><code>${esc(anchor)}</code></p>` : ''}
    <pre><code>${code}</code></pre>
  </details>`;
}
