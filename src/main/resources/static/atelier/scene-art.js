// Cut lines use percentages of the original illustrations, before object-fit cropping.
const shape = d => new Path2D(d);
const blueprintTraces = [
  shape('M34 30.4 L44.4 23.2 L71.8 29.3 L63.5 37 L34 30.4 M63.5 37 L63.5 42.9 L71.9 35.4 L71.8 29.3'),
  shape('M34.6 48.2 L43.1 41 L70.5 46.1 L62.8 54.6 Z M34.8 55.6 L41.8 51 L69.7 55.1 L63.2 62 Z'),
  shape('M35.6 62.2 L41.5 58.1 L69.4 62.1 L63.3 68 Z M33.2 70.5 L40 66.3 L71.8 71.5 L63.9 78.8 Z')
];

function steam(ctx,time) {
  for (const [x,y] of [[51.6,42.5],[65,42.4]]) {
    for (let j=0;j<3;j++) {
      const gradient = ctx.createLinearGradient(0,y,0,y-13);
      gradient.addColorStop(0,'#fff3da00'); gradient.addColorStop(.2,'#fff4de66');
      gradient.addColorStop(.63,'#fff5e944'); gradient.addColorStop(1,'#fff4e200');
      ctx.strokeStyle = gradient;
      ctx.lineWidth = .15+j*.052; ctx.lineCap = 'round';
      ctx.shadowColor='#f9e6ca80';ctx.shadowBlur=2.5;
      ctx.beginPath();
      for(let i=0;i<=27;i++) {
        const k=i/27;
        const wave = Math.sin(k*8-time/(x<57?680:840)+j*1.8+(x<57?0:2.4))*k*(.78+j*.30);
        const px=x+wave+(j-1)*.13;
        const py=y-k*(10+j*1.2);
        if(i) ctx.lineTo(px,py); else ctx.moveTo(px,py);
      }
      ctx.stroke();
    }
  }
  ctx.shadowBlur=0;
}
function blueprint(ctx, amount, sweep) {
  if (amount < .005) return;
  const traceLight = ctx.createLinearGradient(sweep-16,0,sweep+16,0);
  traceLight.addColorStop(0,'#f1d19a00');
  traceLight.addColorStop(.43,'#f1d19a34');
  traceLight.addColorStop(.53,'#ffe6b7da');
  traceLight.addColorStop(.72,'#f1d19a48');
  traceLight.addColorStop(1,'#f1d19a00');
  ctx.save();
  ctx.globalAlpha = amount;
  ctx.lineJoin = 'round';
  ctx.strokeStyle = traceLight;
  ctx.lineWidth = .21;
  for (const trace of blueprintTraces) ctx.stroke(trace);
  ctx.lineWidth = .10;
  ctx.setLineDash([.5,.9]);
  for (const x of [34.3,63.5,70.4]) {
    ctx.beginPath();
    ctx.moveTo(x,27);
    ctx.lineTo(x,77);
    ctx.stroke();
  }
  ctx.restore();
}

export function mountSceneArt(root,route,signal) {
  const selector = {interview:'.cafe-scene',project:'.blueprint-plate'}[route];
  if (!selector) return;
  const art = root.querySelector(selector), img = art.querySelector('img');
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  canvas.className = 'scene-art-layer';
  canvas.setAttribute('aria-hidden','true');
  art.append(canvas);
  let map, frame=0,last=0, now=0, scan;
  const enabled=()=>!document.hidden && !reduced.matches && !document.body.classList.contains('no-motion');
  function resize() {
    if (!img.naturalWidth || !enabled()) return;
    const a=canvas.getBoundingClientRect(), b=img.getBoundingClientRect();
    const factor=Math.max(b.width/img.naturalWidth,b.height/img.naturalHeight);
    const w=img.naturalWidth*factor,h=img.naturalHeight*factor;
    const [px,py]=getComputedStyle(img).objectPosition.split(' ').map(v=>parseFloat(v)/100);
    const density=Math.min(devicePixelRatio,1.5);
    map={x:b.left-a.left+(b.width-w)*(px||0),y:b.top-a.top+(b.height-h)*(py||0),w,h,density};
    canvas.width=Math.max(1,Math.round(a.width*density));
    canvas.height=Math.max(1,Math.round(a.height*density));
    queue();
  }
  function queue() { if (!frame && enabled() && map) frame=requestAnimationFrame(draw); }
  function draw(time) {
    frame=0;
    if(last&&time-last<29) {queue();return;}
    const dt=last?Math.min(time-last,50):16;
    last=time;now+=dt;
    const {x,y,w,h,density:d}=map;
    ctx.setTransform(1,0,0,1,0,0);
    ctx.clearRect(0,0,canvas.width,canvas.height);
    ctx.setTransform(w*d/100,0,0,h*d/100,x*d,y*d);
    if (route==='interview') steam(ctx,now);
    if (route==='project') {
      if (!scan || scan.playState==='idle') scan=art.querySelector('.blueprint-grid')
        .getAnimations({subtree:true}).find(animation=>animation.animationName==='blueprint-scan');
      const phase=scan?.effect.getComputedTiming().progress;
      if (phase != null) blueprint(ctx,.65,phase*128-13);
    }
    queue();
  }
  function sync() {
    if(enabled()) {resize();return;}
    cancelAnimationFrame(frame);
    frame=last=0;
    ctx.resetTransform();ctx.clearRect(0,0,canvas.width,canvas.height);
  }
  const size=new ResizeObserver(resize);
  const settings=new MutationObserver(sync);
  size.observe(img);
  settings.observe(document.body,{attributes:true,attributeFilter:['class']});
  img.addEventListener('load',resize,{signal});
  reduced.addEventListener('change',sync,{signal});
  document.addEventListener('visibilitychange',sync,{signal});
  signal.addEventListener('abort',()=>{cancelAnimationFrame(frame);size.disconnect();settings.disconnect();},{once:true});
  resize();
}
