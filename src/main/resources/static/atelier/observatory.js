import { request } from './api.js';
import { isSample } from './jobs.js';
import { $, $$, chips, esc, link, placeMark } from './ui.js';
import { atlasLink } from './skill-atlas.js';
import { postingPath, starPath, starSlots, starTexture } from './job-sky-layout.js';
import { animateCanvas } from './scene-motion.js';
import { skyCamera } from './sky-camera.js';

const turn = Math.PI * 2;
let recentJobs;
const dustLayers = [
  {count:820,min:.18,max:.64,parallax:.42,brightness:.55,drift:.65},
  {count:280,min:.52,max:1.25,parallax:1.05,brightness:.83,drift:1},
  {count:40,min:1,max:2.45,parallax:1.7,brightness:.93,drift:1.45}
].map((layer,depth)=>({
  ...layer,
  points:Array.from({length:layer.count},(_,index)=>{
    const sample = offset => {
      const value = Math.sin((index+offset+depth*1327)*143.17)*19789.31;
      return value-Math.floor(value);
    };
    return {
      x:sample(4),y:sample(237),size:sample(820)**1.6,
      vx:(sample(987)-.5)*.000028,vy:(sample(1201)-.5)*.000028,
      phase:sample(1641)*turn,rate:.00065+sample(1749)*.002
    };
  })
}));
const wrap = position => position-Math.floor(position);

function trackSky(canvas,signal,camera) {
  return animateCanvas(canvas,(ctx,width,height,time)=>{
    const view = camera();
    ctx.clearRect(0,0,width,height);
    ctx.fillStyle = '#fff';
    const radiusScale = Math.max(.75,Math.min(1.4,(width+height)/2340));
    for (const layer of dustLayers) {
      const zoom = 1+(view.zoom-1)*layer.parallax*.47;
      const offsetX = view.x*layer.parallax/width;
      const offsetY = view.y*layer.parallax/height;
      for (const point of layer.points) {
        const x = wrap(.5+(point.x-.5)*zoom+offsetX+time*point.vx*layer.drift)*width;
        const y = wrap(.5+(point.y-.5)*zoom+offsetY+time*point.vy*layer.drift)*height;
        ctx.globalAlpha = layer.brightness*(.33+.67*(.5+.5*Math.sin(point.phase+time*point.rate)));
        const radius = (layer.min+(layer.max-layer.min)*point.size)*radiusScale*Math.sqrt(zoom);
        ctx.beginPath();
        ctx.arc(x,y,radius,0,turn);
        ctx.fill();
      }
    }
    ctx.globalAlpha = 1;
  },signal);
}

function arrangeJobs(jobs, selected, star) {
  const available = jobs.filter(job=>job?.id != null && job.title);
  const used = new Set();
  const picked = starSlots.map(slot=>({slot,job:null}));
  function place(entry, job) {
    if (!job) return;
    entry.job = job;
    used.add(job.id);
  }
  const current = available.find(job=>String(job.id) === selected);
  if (current) {
    const entry = picked.find(({slot})=>slot.id === star)
      || picked.find(({slot})=>slot.matches.test(current.title)) || picked[0];
    place(entry,current);
  }
  for (const entry of picked.filter(item=>!item.job)) {
    const {preferred,matches} = entry.slot;
    const find = pattern => pattern && available.find(job=>!used.has(job.id) && pattern.test(job.title));
    place(entry,find(preferred) || find(matches));
  }
  for (const entry of picked.filter(item=>!item.job)) {
    place(entry,available.find(job=>!used.has(job.id)));
  }
  return picked.filter(entry=>entry.job);
}

function frameRail() {
  return `<svg viewBox="0 0 110 670" focusable="false" aria-hidden="true">
    <polyline points="5.3,4.9 24.7,46.9 45,51.4 59.2,81.9 53.7,109.7 62.5,128.7 71,132.5 77.6,159.1"></polyline>
    <polyline points="5.3,665.1 24.7,623.1 45,618.6 59.2,588.1 53.7,560.3 62.5,541.3 71,537.5 77.6,510.9"></polyline>
  </svg>`;
}

export function observatory(root, signal, query) {
  root.innerHTML = `<section class="reference-sky" data-sky-view="sky" aria-labelledby="observatory-title">
    <div class="reference-sky-scene" aria-hidden="true">
      <img class="reference-sky-background" src="/atelier/visuals/cosmic/background_436870b549.jpg" alt="" fetchpriority="high" decoding="async">
      <img class="sky-companion sky-companion-ring" src="/atelier/visuals/cosmic/star_unknown_1_0edb617d31.png" alt="">
      <img class="sky-companion sky-companion-rock" src="/atelier/visuals/cosmic/star_5cf82170c3.png" alt="">
      <img class="sky-companion sky-companion-distant" src="/atelier/visuals/observatory/mercury.webp" alt="">
      <img class="sky-companion sky-companion-outer" src="/atelier/visuals/observatory/neptune.webp" alt="">
      <canvas class="reference-sky-dust"></canvas>
    </div>
    <div class="reference-sky-rails" aria-hidden="true">${frameRail()}${frameRail()}</div>
    <div class="job-star-field" role="group" tabindex="0" aria-label="岗位星图，方向键移动视野">
      <div class="job-stars"></div>
    </div>
    <svg class="job-sightline" viewBox="0 0 620 460" focusable="false" aria-hidden="true" hidden>
      <g><circle cx="143.8" cy="132.1" r="103.8" vector-effect="non-scaling-stroke"></circle><circle cx="143.8" cy="132.1" r="21.5"></circle></g>
      <path pathLength="1" vector-effect="non-scaling-stroke" d="M214.6,202.9l75.1,69.5l38.1-3.5l40.5,38.7l16,55.6l84.9,79.2H620"></path>
    </svg>
    <aside class="job-star-preview" id="job-star-preview" aria-label="岗位预览" hidden>
      <button class="job-star-close" type="button" aria-label="关闭岗位预览">×</button>
      <div class="job-star-details" aria-live="polite"></div>
    </aside>
    <footer class="reference-sky-dock">
      <div>
        ${placeMark('jobs')}<h1 id="observatory-title">职业天文馆</h1>
        <p class="reference-sky-status" role="status">正在载入岗位…</p>
        <a class="reference-sky-credit" href="https://www.cosmicbroth.com/"
          target="_blank" rel="noopener noreferrer">主图参考 · Cosmic Broth</a>
      </div>
      <output class="sky-contact" aria-hidden="true"></output>
      <nav aria-label="岗位星图入口">
        <div class="sky-controls" role="group" aria-label="调整望远镜视野">
          <button type="button" data-sky-control="out" aria-label="拉远">−</button>
          <button type="button" data-sky-control="reset">复位</button>
          <button type="button" data-sky-control="in" aria-label="拉近">+</button>
        </div>
        ${atlasLink()}
        ${link('jobs?work=1','全部岗位 →','reference-sky-jobs')}
      </nav>
    </footer>
  </section>`;

  const scene = $('.reference-sky',root);
  const field = $('.job-stars',scene);
  const panel = $('#job-star-preview',scene);
  const line = $('.job-sightline',scene);
  const details = $('.job-star-details',panel);
  const status = $('.reference-sky-status',scene);
  let catalog = [];
  let active;
  let cameraView = {x:0,y:0,zoom:1};
  let repaintDust;
  skyCamera(scene,field,signal,view=>{
    cameraView = view;
    placePreview();
    if (document.body.classList.contains('no-motion') || matchMedia('(prefers-reduced-motion: reduce)').matches) repaintDust?.();
  },()=>{
    $('.sky-contact',scene).textContent = '';
    if (active) close(false);
  });

  function placePreview() {
    if (!active) return;
    const star = $('[aria-pressed="true"]',field).getBoundingClientRect();
    const frame = scene.getBoundingClientRect();
    const panelWidth = panel.offsetWidth;
    const panelHeight = panel.offsetHeight;
    const starX = star.left-frame.left+star.width/2;
    const starY = star.top-frame.top+star.height/2;
    const lineWidth = 78;
    const lineHeight = lineWidth*460/620;
    const reachX = lineWidth*(620-143.8)/620;
    const reachY = lineHeight*(442.4-132.1)/460;
    const leftSide = starX+star.width/2+reachX+panelWidth+22 > frame.width;
    const above = starY+reachY+panelHeight > frame.height-105;
    const anchorX = starX+(leftSide?-1:1)*(star.width/2+4);
    const panelX = anchorX+(leftSide?-reachX-panelWidth:reachX);
    const panelY = starY+(above?-reachY:reachY)-22;
    line.style.left = anchorX-lineWidth*(leftSide ? 1-143.8/620 : 143.8/620)+'px';
    line.style.top = starY-lineHeight*(above ? 1-132.1/460 : 132.1/460)+'px';
    line.style.transform = `scale(${leftSide?-1:1},${above?-1:1})`;
    panel.style.left = Math.max(16,Math.min(panelX,frame.width-panelWidth-16))+'px';
    panel.style.top = Math.max(76,Math.min(panelY,frame.height-panelHeight-88))+'px';
  }
  const observer = new ResizeObserver(placePreview);
  observer.observe(scene);
  signal.addEventListener('abort',()=>observer.disconnect(),{once:true});

  function select(entry) {
    active = entry;
    $('.sky-contact',scene).textContent = '';
    for (const star of $$('button',field)) star.setAttribute('aria-pressed',String(star.dataset.posting === String(entry?.job.id)));
    panel.hidden = line.hidden = !entry;
    if (!entry) return;
    const {job,slot} = entry;
    details.innerHTML = `<h2>${esc(job.title)}</h2>
      <p>${[job.company,job.city,job.salary,isSample(job)?'示例岗位':''].filter(Boolean).map(esc).join(' · ')}</p>
      <div class="job-star-skills">${chips(job.skills?.slice(0,3))}</div>
      ${link(postingPath(job.id,slot.id),'查看岗位详情 →','job-star-enter')}`;
    placePreview();
    if (document.body.classList.contains('no-motion') || matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const ring = $('g',line), ray = $('path',line);
    for (const node of [ring,ray,details]) node.getAnimations().forEach(animation=>animation.cancel());
    ring.animate([{transform:'scale(0)'},{transform:'scale(1)'}],{duration:300,easing:'ease-out'});
    ray.animate([{strokeDashoffset:1},{strokeDashoffset:0}],{duration:320,delay:40,fill:'backwards',easing:'ease-out'});
    details.animate([{clipPath:'inset(0 100% 0 0)',opacity:0},{clipPath:'inset(0)',opacity:1}],
      {duration:250,delay:65,fill:'backwards',easing:'cubic-bezier(.2,.75,.25,1)'});
  }

  function close(focus = true) {
    if (focus) $('[aria-pressed="true"]',field)?.focus({preventScroll:true});
    history.replaceState(history.state,'','#jobs');
    select(null);
  }
  $('.job-star-close',panel).addEventListener('click',()=>close());
  root.addEventListener('keydown',event=>{ if (event.key === 'Escape' && active) close(); },{signal});
  field.addEventListener('click',event=>{
    const star = event.target.closest('.job-star');
    if (!star) return;
    const entry = catalog.find(item=>String(item.job.id) === star.dataset.posting);
    if (entry !== active) {
      history.replaceState(history.state,'','#'+starPath(entry.job.id,entry.slot.id));
      select(entry);
    }
    if (event.detail === 0) $('.job-star-enter',details).focus({preventScroll:true});
  });

  const contact = $('.sky-contact',scene);
  function identify(event) {
    const star = event.target.closest('.job-star');
    if (!star || scene.hasAttribute('data-sky-dragging') || active) return;
    contact.textContent = '观测目标 / '+star.getAttribute('aria-label');
  }
  for (const type of ['pointerover','focusin']) field.addEventListener(type,identify,{signal});
  for (const type of ['pointerout','focusout']) field.addEventListener(type,event=>{
    const star = event.target.closest('.job-star');
    if (star && !star.contains(event.relatedTarget)) contact.textContent = '';
  },{signal});

  function showJobs(jobs) {
    if (signal.aborted) return;
    catalog = arrangeJobs(jobs,query.get('planet'),query.get('star'));
    field.innerHTML = catalog.map(({job,slot})=>`<button class="job-star" type="button" data-posting="${esc(job.id)}"
      aria-controls="job-star-preview" aria-pressed="false" aria-label="${esc(job.title)}" data-depth="${slot.depth}"
      style="--star-x:${slot.position[0]}%;--star-y:${slot.position[1]}%;--star-scale:${slot.scale};--star-depth:${slot.depth}">
      <img class="job-star-texture planet-surface" src="${starTexture(slot.id)}" alt="">
    </button>`).join('');
    status.textContent = catalog.length ? '按住拖动 · 滚轮调焦 · 点击星球观测' : '暂时没有可展示的岗位。';
    const current = catalog.find(({job})=>String(job.id) === query.get('planet'))
      || catalog.find(({slot,job})=>slot.id === query.get('sector') && slot.matches.test(job.title));
    if (current) select(current);
  }

  repaintDust = trackSky($('.reference-sky-dust',scene),signal,()=>cameraView);
  if (query.has('planet') && recentJobs?.some(job=>String(job.id) === query.get('planet'))) {
    showJobs(recentJobs);
    scene.setAttribute('data-sky-aligned','');
    return;
  }
  const backgroundReady = $('.reference-sky-background',scene).decode().catch(()=>{});
  const jobsReady = request('/jobs?limit=100',{signal}).then(async jobs=>{
    const id = query.get('planet');
    if (/^[1-9]\d*$/.test(id || '') && !jobs.some(job=>String(job.id) === id)) {
      const selected = await request('/jobs/'+id,{signal}).catch(()=>null);
      if (selected) jobs.unshift(selected);
    }
    return jobs;
  });
  Promise.all([jobsReady,backgroundReady]).then(([jobs])=>{
    if (signal.aborted) return;
    recentJobs = jobs;
    showJobs(jobs);
    scene.setAttribute('data-sky-aligned','');
  }).catch(()=>{
    if (!signal.aborted) status.textContent = '岗位暂时无法载入，可打开全部岗位重试。';
  });
}
