export function animateCanvas(canvas, draw, signal) {
  const context = canvas.getContext('2d');
  if (!context) return () => {};
  let width, height, frame;
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');

  function paint(time = 0) {
    draw(context, width, height, time);
  }

  function resize() {
    width = Math.max(canvas.clientWidth, 1);
    height = Math.max(canvas.clientHeight, 1);
    const density = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.round(width * density);
    canvas.height = Math.round(height * density);
    context.setTransform(density, 0, 0, density, 0, 0);
    paint();
  }

  function tick(time) {
    paint(time);
    frame = window.requestAnimationFrame(tick);
  }

  function sync() {
    window.cancelAnimationFrame(frame);
    paint();
    if (!document.hidden && !reduced.matches && !document.body.classList.contains('no-motion')) {
      frame = window.requestAnimationFrame(tick);
    }
  }

  const size = new ResizeObserver(resize);
  const motion = new MutationObserver(sync);
  size.observe(canvas);
  motion.observe(document.body, { attributes:true, attributeFilter:['class'] });
  reduced.addEventListener('change', sync, {signal});
  document.addEventListener('visibilitychange', sync, {signal});
  signal.addEventListener('abort', () => {
    window.cancelAnimationFrame(frame);
    size.disconnect();
    motion.disconnect();
  }, {once:true});
  resize();
  sync();
  return paint;
}
