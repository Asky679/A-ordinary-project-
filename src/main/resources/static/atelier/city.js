import { $, $$, routes } from './ui.js';
import { request } from './api.js';
import { animateCanvas } from './scene-motion.js';
import { filterDemo } from './auth.js';

const landmarks = [
  { id:'studio', task:'整理与优化简历', color:'#dce5bb', x:33.6, y:35.6, width:12, height:25 },
  { id:'jobs', task:'从职业方向找到岗位', color:'#c0ddf4', x:59.2, y:15.1, width:17, height:29 },
  { id:'interview', task:'练习一场 AI 面试', color:'#ffdbaf', x:64.3, y:42.8, width:21, height:21 },
  { id:'growth', task:'回看反馈和改进记录', color:'#d9eabd', x:20.3, y:57, width:14, height:19 },
  { id:'project', task:'练习讲清项目经历', color:'#e6d4f5', x:88, y:49.8, width:12, height:23 }
];
const connections = [[1,0,.17], [1,2,-.16], [0,3,.10], [0,2,-.20], [2,4,.11]];

const waterfrontLights = [
  [.466,.568,.038], [.501,.557,.085], [.535,.552,.073], [.581,.563,.071],
  [.637,.562,.048], [.668,.548,.058], [.806,.656,.081], [.857,.709,.042]
];

function drawReflections(ctx,width,height,time) {
  if (!time) return;
  for (const [index,[x,y,length]] of waterfrontLights.entries()) {
    for (let ripple=0;ripple<8;ripple++) {
      const depth=ripple/8;
      const phase=time/620+index*1.4+ripple*.84;
      const shift=Math.sin(phase)*(2+depth*6);
      const streak=(3.3+Math.sin(phase+1.1)*2)*(1+depth);
      ctx.fillStyle='#ffd399';
      ctx.globalAlpha=(.11+.36*Math.sin(phase*.68)**2)*(1-depth);
      ctx.fillRect(x*width+shift-streak/2,(y+depth*length)*height,streak,1.35+depth);
    }
  }
  ctx.globalAlpha=1;
}

function drawPaths(canvas, scene, signal) {
  return animateCanvas(canvas, (ctx,width,height,time) => {
    ctx.clearRect(0,0,width,height);
    drawReflections(ctx,width,height,time);
    connections.forEach(([from,to,bend],index) => {
      const a = landmarks[from], b = landmarks[to];
      const x = a.x*width/100, y = a.y*height/100;
      const endX = b.x*width/100, endY = b.y*height/100;
      const midX = (x+endX)/2+(endY-y)*bend;
      const midY = (y+endY)/2-(endX-x)*bend;
      const focused = scene.dataset.destination;
      const connects = focused === a.id || focused === b.id;
      ctx.lineWidth = connects ? 1.5 : 1;
      ctx.strokeStyle = connects ? '#ffe6aad0' : '#ffe7b25d';
      ctx.setLineDash(connects ? [] : [2,6]);
      ctx.beginPath();
      ctx.moveTo(x,y);
      ctx.quadraticCurveTo(midX,midY,endX,endY);
      ctx.stroke();
      ctx.setLineDash([]);
      if (!time) return;
      const progress = (time/16000+index*.227)%1;
      const start = 1-progress;
      const lampX = start*start*x+2*start*progress*midX+progress*progress*endX;
      const lampY = start*start*y+2*start*progress*midY+progress*progress*endY;
      const glow = ctx.createRadialGradient(lampX,lampY,0,lampX,lampY,connects ? 13 : 8);
      glow.addColorStop(0,'#fff3d3');
      glow.addColorStop(.2,'#ffce85d0');
      glow.addColorStop(1,'#ffc77c00');
      ctx.fillStyle = glow;
      ctx.fillRect(lampX-13,lampY-13,26,26);
    });
  }, signal);
}

async function nextStep(signal) {
  const [interviews,resumes] = await Promise.allSettled([
    request('/interviews',{signal}), request('/resumes',{signal})
  ]);
  const sessions = interviews.status === 'fulfilled' ? filterDemo(interviews.value) : [];
  const unfinished = sessions.find(item => item.status === 'IN_PROGRESS');
  if (unfinished) return {
    place:'interview', href:'#interview?work=1&session='+encodeURIComponent(unfinished.id)
      +(unfinished.difficulty === '压力' ? '&room=meeting' : ''),
    label:'继续模拟面试', detail:unfinished.jobTitle
  };
  const completed = sessions.find(item => item.status === 'COMPLETED');
  if (completed) return {
    place:'growth', href:'#growth?work=1&session='+encodeURIComponent(completed.id),
    label:'回看最近的面试', detail:completed.jobTitle
  };
  if (resumes.status === 'fulfilled' && filterDemo(resumes.value).length) {
    return {place:'jobs', href:'#jobs', label:'探索职业方向'};
  }
  return null;
}

export function city(root, signal) {
  root.innerHTML = `<section class="city" aria-labelledby="city-title">
    <div class="city-world">
      <img class="city-art" src="/atelier/visuals/city-map.webp"
        alt="" fetchpriority="high" decoding="async">
      <div class="city-mist" aria-hidden="true"></div>
      <canvas class="city-paths" aria-hidden="true"></canvas>
      <nav class="city-landmarks" aria-label="地图地标">${landmarks.map(place => `
        <a class="city-landmark" data-destination="${place.id}" href="#${place.id}"
          style="--x:${place.x}%;--y:${place.y}%;--light:${place.color};--hit-width:${place.width}%;--hit-height:${place.height}%">
          <span class="landmark-beacon" aria-hidden="true">${routes[place.id][1]}</span>
          <span class="landmark-name">${routes[place.id][0]}</span>
        </a>`).join('')}
      </nav>
    </div>
    <header class="city-intro">
      <p class="scene-label">求职城市地图</p>
      <h1 id="city-title"><span class="city-line"><span>未来不必</span></span><span class="city-line"><span>一次想清楚。</span></span></h1>
      <p class="city-lede">从一份简历、一个岗位，或一次面试练习开始。</p>
      <div class="city-next">
        <a class="city-start" href="#studio?work=1" data-destination="studio">
          <span class="city-start-copy">
            <span class="city-start-label">从简历开始</span>
            <span class="city-next-detail" hidden></span>
          </span>
          <span class="city-start-arrow" aria-hidden="true">↗</span>
        </a>
      </div>
    </header>
    <nav class="city-dock" aria-label="按任务选择地点">${landmarks.map(place => `
      <a href="#${place.id}" data-destination="${place.id}" style="--light:${place.color}">
        <span class="dock-icon" aria-hidden="true">${routes[place.id][1]}</span>
        <span><strong>${routes[place.id][0]}</strong><small>${place.task}</small></span>
        <span class="dock-arrow" aria-hidden="true">↗</span>
      </a>`).join('')}
    </nav>
  </section>`;

  const scene = $('.city',root);
  const world = $('.city-world',scene);
  const redraw = drawPaths($('.city-paths',scene),scene,signal);
  const entries = $$('[data-destination]',scene);
  const start = $('.city-start',scene);
  let hovered, focused, recommendation;
  function highlight() {
    const active = focused?.matches(':focus-visible') ? focused : hovered || focused;
    const place = active?.dataset.destination;
    if (place) scene.dataset.destination = place;
    else delete scene.dataset.destination;
    for (const entry of entries) entry.toggleAttribute('data-highlight',entry.dataset.destination === place);
    if (document.body.classList.contains('no-motion')) redraw();
  }
  function updateStart() {
    if (!recommendation || hovered === start || focused === start || signal.aborted) return;
    start.closest('.city-next').setAttribute('data-recommended','');
    start.href = recommendation.href;
    start.dataset.destination = recommendation.place;
    $('.city-start-label',start).textContent = recommendation.label;
    const detail = $('.city-next-detail',scene);
    detail.textContent = recommendation.detail || '';
    detail.hidden = !recommendation.detail;
  }
  entries.forEach(entry => {
    entry.addEventListener('pointerenter',() => { hovered = entry; highlight(); });
    entry.addEventListener('pointerleave',() => { hovered = null; updateStart(); highlight(); });
    entry.addEventListener('focus',() => { focused = entry; highlight(); });
    entry.addEventListener('blur',() => { focused = null; updateStart(); highlight(); });
  });
  nextStep(signal).then(result => {
    recommendation = result;
    updateStart();
  });
  scene.addEventListener('pointermove',event => {
    if (event.pointerType !== 'mouse' || document.body.classList.contains('no-motion')) return;
    world.style.setProperty('--look-x',(event.clientX/window.innerWidth-.5)*16+'px');
    world.style.setProperty('--look-y',(event.clientY/window.innerHeight-.5)*10+'px');
  });
  scene.addEventListener('pointerleave',() => {
    world.style.removeProperty('--look-x');
    world.style.removeProperty('--look-y');
  });
}
