const limit = (value, min, max) => Math.max(min, Math.min(max, value));

export function skyCamera(scene, field, signal, onMove, onNavigate) {
  const viewport = field.parentElement;
  const motion = () => !document.body.classList.contains('no-motion') &&
    !matchMedia('(prefers-reduced-motion: reduce)').matches;
  // Based on the reference's width-normalized drag and 0.8 star/background ratio.
  const starRatio = .8;
  let view = {x:0,y:0,zoom:1}, target = view, frame = 0;
  let drag, blockClick = false, previousFrame = 0;
  const listen = (node,type,handler,options) => node.addEventListener(type,handler,{signal,...options});

  function draw() {
    const {x,y,zoom} = view;
    field.style.transform = 'translate3d('+x+'px,'+y+'px,0) scale('+zoom+')';
    field.style.setProperty('--orbit-x',x/zoom+'px');
    field.style.setProperty('--orbit-y',y/zoom+'px');
    scene.style.setProperty('--moon-x',x*.94+'px');
    scene.style.setProperty('--moon-y',y*.94+'px');
    scene.style.setProperty('--sky-x',x/starRatio+'px');
    scene.style.setProperty('--sky-y',y/starRatio+'px');
    scene.style.setProperty('--sky-depth',1.5+(zoom-1)*.34);
    onMove(view);
  }

  function tick(time) {
    const elapsed = previousFrame ? limit(time-previousFrame,1,48) : 16.67;
    const ease = 1-Math.exp(-elapsed/155);
    previousFrame = time;
    frame = 0;
    if (Math.abs(target.x-view.x)+Math.abs(target.y-view.y)+Math.abs(target.zoom-view.zoom)*100 < .45) {
      view = target;
      previousFrame = 0;
    } else {
      view = {x:view.x+(target.x-view.x)*ease,y:view.y+(target.y-view.y)*ease,zoom:view.zoom+(target.zoom-view.zoom)*ease};
      frame = requestAnimationFrame(tick);
    }
    draw();
  }

  function move(next) {
    const maxX = viewport.clientWidth*(.15+(next.zoom-1)*.23);
    const maxY = viewport.clientHeight*(.15+(next.zoom-1)*.23);
    target = {x:limit(next.x,-maxX,maxX),y:limit(next.y,-maxY,maxY),zoom:next.zoom};
    if (!motion()) {
      cancelAnimationFrame(frame);
      frame = 0;
      previousFrame = 0;
      view = target;
      draw();
    } else if (!frame) frame = requestAnimationFrame(tick);
  }

  function zoomBy(factor, clientX, clientY) {
    onNavigate();
    const box = viewport.getBoundingClientRect();
    const x = (clientX ?? box.left+box.width/2)-box.left-box.width/2;
    const y = (clientY ?? box.top+box.height/2)-box.top-box.height/2;
    const zoom = limit(target.zoom*factor,.94,1.55);
    const ratio = zoom/target.zoom;
    move({x:x-(x-target.x)*ratio,y:y-(y-target.y)*ratio,zoom});
  }

  listen(viewport,'pointerdown',event=>{
    if (event.button !== 0 || drag) return;
    blockClick = false;
    drag = {id:event.pointerId,x:event.clientX,y:event.clientY,moved:false};
  });
  listen(window,'pointermove',event=>{
    if (!drag || drag.id !== event.pointerId) return;
    if (!drag.moved && Math.hypot(event.clientX-drag.x,event.clientY-drag.y)<5) return;
    if (!drag.moved) {
      onNavigate();
      drag.moved = true;
      drag.origin = {...target};
      drag.gain = 390*starRatio/(Math.max(1,viewport.clientWidth)*(window.devicePixelRatio || 1));
      viewport.setPointerCapture(event.pointerId);
      scene.setAttribute('data-sky-dragging','');
    }
    move({...drag.origin,
      x:drag.origin.x+(event.clientX-drag.x)*drag.gain,
      y:drag.origin.y+(event.clientY-drag.y)*drag.gain
    });
  });
  function release(event) {
    if (!drag || drag.id !== event.pointerId) return;
    blockClick = drag.moved && event.type === 'pointerup';
    scene.removeAttribute('data-sky-dragging');
    drag = null;
  }
  listen(window,'pointerup',release);
  listen(window,'pointercancel',release);
  listen(viewport,'lostpointercapture',release);
  listen(viewport,'click',event=>{
    if (blockClick && event.detail) {
      event.stopImmediatePropagation();
      blockClick = false;
    }
  },{capture:true});
  listen(viewport,'dragstart',event=>event.preventDefault());
  listen(viewport,'wheel',event=>{
    if (event.ctrlKey || event.metaKey) return;
    event.preventDefault();
    zoomBy(Math.exp(-limit(event.deltaY*(event.deltaMode === 2 ? viewport.clientHeight : event.deltaMode === 1 ? 16 : 1),-160,160)*.00075),event.clientX,event.clientY);
  },{passive:false});
  listen(viewport,'keydown',event=>{
    const offsets = {ArrowLeft:[36,0],ArrowRight:[-36,0],ArrowUp:[0,36],ArrowDown:[0,-36]};
    const offset = offsets[event.key];
    if (!offset || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
    event.preventDefault();
    onNavigate();
    move({...target,x:target.x+offset[0],y:target.y+offset[1]});
  });
  listen(scene,'click',event=>{
    const action = event.target.closest('[data-sky-control]')?.dataset.skyControl;
    if (action === 'reset') {
      onNavigate();
      move({x:0,y:0,zoom:1});
    }
    if (action === 'in') zoomBy(1.15);
    if (action === 'out') zoomBy(1/1.15);
  });
  signal.addEventListener('abort',()=>cancelAnimationFrame(frame),{once:true});
}
