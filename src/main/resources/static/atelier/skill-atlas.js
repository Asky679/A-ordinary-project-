import { $, esc, link, external } from './ui.js';
import { getJobSector } from './job-sectors.js';

const skills = {
  java: {
    name:'HTTP 接口设计', symbol:'HTTP', color:'#a6c7bb',
    intro:'用请求、响应和状态码定义前后端的协作约定。',
    notes:[
      ['资源与方法','用资源名描述对象，例如 GET /resumes/42 读取一份简历。请求方法表达用户对该资源的意图。'],
      ['状态码','资源不存在时返回 404，请求体格式有误可以返回 400。服务器遇到未预期的错误才用 500。'],
      ['错误反馈','给调用方一个可理解的错误说明。内部异常堆栈留在日志，便于开发者排查。']
    ],
    prompt:'简历接口收到合法 ID，但找不到对应记录，最合适的状态码是什么？',
    choices:['400 Bad Request','404 Not Found','500 Internal Server Error'],
    answer:1,
    reason:'请求格式正确，问题在于目标资源不存在，因此用 404。400 表示请求有问题，500 用于服务器端意外故障。',
    source:['https://www.rfc-editor.org/rfc/rfc9110.html#name-status-codes','HTTP 状态码规范']
  },
  frontend: {
    name:'首屏性能', symbol:'LCP', color:'#e3b08b',
    intro:'找出拖慢首屏主体的资源，优先改善用户最先看到的内容。',
    notes:[
      ['识别 LCP','Largest Contentful Paint 衡量视口中最大内容元素的呈现时间。用性能工具确认具体元素。'],
      ['让资源更早被发现','如果首屏大图是 LCP 元素，让初始 HTML 尽早暴露它的图片地址，并避免对它使用懒加载。'],
      ['先定位瓶颈','在相同网络与设备条件下比较资源发现、下载和渲染时间，再判断优化是否有效。']
    ],
    prompt:'首屏主图是 LCP 元素，却因 loading="lazy" 晚才发出请求。优先怎么处理？',
    choices:['移除懒加载，让浏览器更早发现并请求主图','保留懒加载，延迟到滚动后请求','只加大图片分辨率'],
    answer:0,
    reason:'首屏就需要的主图应尽早被浏览器发现和请求。资源较重时，还可以按显示尺寸提供合适的图片版本。',
    source:['https://web.dev/articles/optimize-lcp','LCP 优化指南']
  },
  data: {
    name:'SQL 分组查询', symbol:'SQL', color:'#adbfd3',
    intro:'从明细得到一组可信的指标，解释筛选口径与聚合方式。',
    notes:[
      ['先筛行','WHERE 先筛明细。例如统计本月订单，先限定订单日期。'],
      ['再分组','GROUP BY 按维度组织剩余明细。按城市分组后，COUNT(*) 统计每组中的记录数。'],
      ['最后筛组','HAVING 对聚合后的分组设置条件，适合保留达到某个数量或金额的组。']
    ],
    prompt:'按城市统计岗位数量，只展示至少有 10 条岗位的城市，应使用哪个条件？',
    choices:['WHERE COUNT(*) >= 10','HAVING COUNT(*) >= 10','ORDER BY COUNT(*) >= 10'],
    answer:1,
    reason:'城市的岗位数要等分组聚合后才有结果。HAVING 对分组做筛选，WHERE 用于聚合前的明细。',
    source:['https://www.postgresql.org/docs/current/queries-table-expressions.html','SQL 分组与 HAVING']
  },
  ai: {
    name:'RAG 检索增强', symbol:'RAG', color:'#c9adc0',
    intro:'让模型先找到相关资料，再用可核对的证据组织答案。',
    notes:[
      ['准备语料','将可查询的资料分成能独立理解的段落，保留文件名、页码等来源信息。'],
      ['检索证据','收到问题后查找相关段落，并检查检索结果是否真的包含答案所需的信息。'],
      ['生成并核对','把相关段落交给模型，要求回答标明来源。证据不足时说明缺口。']
    ],
    prompt:'系统经常答错，而送给模型的检索结果里根本没有相关段落。应该先查哪里？',
    choices:['先提高生成温度','先延长回答字数','先排查文档切分、索引和检索召回'],
    answer:2,
    reason:'当前缺口在证据进入模型之前。先确认资料已入库、切分合适，并检查相关段落能否被检索出来。',
    source:['https://platform.openai.com/docs/guides/retrieval','检索与向量库指南']
  }
};
const storagePrefix = 'wayforward-skill:';
const sessionCards = new Map();

export function getSkill(id) {
  return Object.hasOwn(skills,id) ? skills[id] : null;
}

function hasCard(id) {
  if (sessionCards.has(id)) return sessionCards.get(id);
  try { return localStorage.getItem(storagePrefix+id) === 'saved'; }
  catch { return false; }
}

function saveCard(id, saved) {
  try {
    if (saved) localStorage.setItem(storagePrefix+id,'saved');
    else localStorage.removeItem(storagePrefix+id);
    sessionCards.delete(id);
    return true;
  } catch {
    sessionCards.set(id,saved);
    return false;
  }
}

function countLabel() {
  return Object.keys(skills).filter(hasCard).length+' / '+Object.keys(skills).length;
}

export function atlasLink() {
  return `<a class="atlas-link" href="#jobs?atlas=1"><span>技能图鉴</span><small>${countLabel()}</small></a>`;
}

export function skillPath(id, fromAtlas = false) {
  return 'jobs?skill='+encodeURIComponent(id)+(fromAtlas?'&from=atlas':'');
}

export function skillRoleLinks(id) {
  const sector = getJobSector(id);
  return Object.entries(sector.roles).map(([role,profile])=>
    `<li>${link('jobs?work=1&sector='+id+'&role='+role,profile.name+' ↗','planet-role')}</li>`).join('');
}

function cardArt(id, skill) {
  return `<div class="skill-art" aria-hidden="true">
    <span class="skill-art-axis"></span>
    <span class="planet-surface lit-planet" style="--planet-image:url('/atelier/visuals/${getJobSector(id).planet}')"></span>
    <span class="skill-art-symbol">${skill.symbol}</span>
  </div>`;
}

export function skillAtlas(root) {
  root.innerHTML = `<section class="skill-room atlas-room" data-sky-view="atlas" aria-labelledby="atlas-title">
    ${link('jobs','← 返回星图','skill-back')}
    <header class="atlas-heading">
      <div><h1 id="atlas-title">技能图鉴</h1><p>挑一张卡复习知识点，再带着它去看相关岗位。</p></div>
      <p class="atlas-progress">已收录 <strong>${countLabel()}</strong></p>
    </header>
    <ul class="atlas-grid">${Object.entries(skills).map(([id,skill])=>`<li>
      <a class="atlas-card" href="#${skillPath(id,true)}" data-collected="${hasCard(id)}" style="--skill-ink:${skill.color}">
        ${cardArt(id,skill)}
        <span class="atlas-card-name"><strong>${esc(skill.name)}</strong><span>${esc(getJobSector(id).name)}</span></span>
        <span class="atlas-card-status">${hasCard(id)?'已收录':'待收录'} <span aria-hidden="true">↗</span></span>
      </a>
    </li>`).join('')}</ul>
    <p class="atlas-storage">收录记录保存在当前浏览器。</p>
  </section>`;
}

export function skillDetail(root, id, fromAtlas) {
  const skill = getSkill(id);
  const sector = getJobSector(id);
  root.innerHTML = `<section class="skill-room skill-room-detail" data-sky-view="skill" data-sector="${id}" aria-labelledby="skill-title" style="--skill-ink:${skill.color}">
    <nav class="skill-nav" aria-label="技能卡导航">
      ${link(fromAtlas?'jobs?atlas=1':'jobs?sector='+id,fromAtlas?'← 返回图鉴':'← 返回星图','skill-back')}
      ${fromAtlas ? link('jobs?sector='+id,'在星图中查看 ↗','skill-back') : atlasLink()}
    </nav>
    <div class="skill-layout">
      <aside class="skill-sheet" aria-label="技能卡面">
        ${cardArt(id,skill)}
        <div class="skill-sheet-caption"><span>${esc(sector.name)}</span><strong>${esc(skill.name)}</strong></div>
        <button type="button" class="skill-collect"></button>
        <p class="skill-collect-status" role="status"></p>
      </aside>
      <article class="skill-reading">
        <header><h1 id="skill-title">${esc(skill.name)}</h1><p>${esc(skill.intro)}</p></header>
        <dl class="skill-notes">${skill.notes.map(([title,note])=>`<div><dt>${esc(title)}</dt><dd>${esc(note)}</dd></div>`).join('')}</dl>
        <details class="skill-practice">
          <summary>试一道题</summary>
          <form>
            <fieldset><legend>${esc(skill.prompt)}</legend>
              ${skill.choices.map((choice,index)=>`<label><input type="radio" name="answer" value="${index}" required><span>${esc(choice)}</span></label>`).join('')}
            </fieldset>
            <button type="submit" class="skill-action">查看解析</button>
            <p class="skill-answer" role="status" hidden></p>
          </form>
        </details>
        <section class="skill-careers" aria-labelledby="skill-jobs"><h2 id="skill-jobs">用到这项技能的岗位</h2><ul>${skillRoleLinks(id)}</ul></section>
        <p class="skill-reference">${external(...skill.source)}</p>
      </article>
    </div>
  </section>`;
  const button = $('.skill-collect',root);
  const status = $('.skill-collect-status',root);
  function reflectCollection() {
    const collected = hasCard(id);
    button.setAttribute('aria-pressed',String(collected));
    button.textContent = collected ? '已收录 · 移出图鉴' : '收进图鉴';
    $('.skill-sheet',root).dataset.collected = String(collected);
    const progress = $('.atlas-link small',root);
    if (progress) progress.textContent = countLabel();
  }
  reflectCollection();
  button.addEventListener('click',()=>{
    const saved = !hasCard(id);
    const persistent = saveCard(id,saved);
    reflectCollection();
    status.textContent = persistent
      ? (saved ? '这张卡已放进技能图鉴。' : '这张卡已从图鉴移出。')
      : '浏览器存储不可用。本次收录调整只在当前会话有效。';
    if (saved && !document.body.classList.contains('no-motion')) {
      const sheet = $('.skill-sheet',root);
      sheet.getAnimations().forEach(animation=>animation.cancel());
      sheet.animate([{outlineOffset:'9px',outlineColor:'#dec4a2'},{outlineOffset:'1px',outlineColor:'transparent'}],
        {duration:520,easing:'ease-out'});
    }
  });
  const form = $('form',root);
  const feedback = $('.skill-answer',form);
  form.addEventListener('submit',event=>{
    event.preventDefault();
    const selected = Number(new FormData(form).get('answer'));
    feedback.textContent = (selected === skill.answer ? '判断正确。' : '参考答案是「'+skill.choices[skill.answer]+'」。')+skill.reason;
    feedback.hidden = false;
  });
  form.addEventListener('change',()=>{ feedback.hidden = true; });
}
