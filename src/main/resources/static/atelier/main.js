import { $, routes, error } from './ui.js';
import { cover, workHeader } from './spaces.js';
import { city } from './city.js';
import { studio } from './studio.js';
import { jobs } from './jobs.js';
import { observatory } from './observatory.js';
import { getSkill, skillAtlas, skillDetail } from './skill-atlas.js';
import { interview, growth } from './practice.js';
import { workflow } from './workflow.js';
import { project } from './project.js';
import { initAuth } from './auth.js';

const root = $('#content');
const mapHome = $('.map-home');
const quickNav = $('.quick-nav');
const motion = $('#motion');
const reduced = matchMedia('(prefers-reduced-motion: reduce)');
const views = { home:city, studio, jobs, interview, growth, project, workflow };
let controller;
let transition;
let transitionTimer;
let transitionStartedAt = 0;
let motionOff = false;
try { motionOff = localStorage.getItem('atelier-motion') === 'off'; } catch {}

function syncMotion() {
  const on = !reduced.matches && !motionOff;
  document.body.classList.toggle('no-motion',!on);
  motion.textContent = on?'动效开':'动效关';
  motion.setAttribute('aria-pressed',String(on));
  motion.disabled = reduced.matches;
}
motion.addEventListener('click',()=>{
  motionOff = !motionOff;
  try {localStorage.setItem('atelier-motion',motionOff?'off':'on');} catch {}
  syncMotion();
});
reduced.addEventListener('change',syncMotion);
syncMotion();

function clearTransitionArtifacts() {
  clearTimeout(transitionTimer);
  document.body.removeAttribute('data-share-planet');
  const html = document.documentElement;
  delete html.dataset.harborJourney;
  delete html.dataset.harborDirection;
  $('[data-journey-mark]',root)?.removeAttribute('data-journey-mark');
}

function finishTransition(force = false) {
  const current = transition;
  transition = null;
  clearTransitionArtifacts();
  if (force && current?.skipTransition) {
    try { current.skipTransition(); } catch {}
  }
  return current;
}

function watchTransition(current) {
  clearTimeout(transitionTimer);
  transitionStartedAt = performance.now();
  transitionTimer = setTimeout(() => {
    if (transition === current) finishTransition(true);
  }, 1800);
}

function recoverStuckMotion({ closeDialog = false } = {}) {
  finishTransition(true);
  clearTransitionArtifacts();
  document.body.classList.remove('modal-open', 'is-busy');
  if (closeDialog && $('#dialog')?.open) $('#dialog').close();
}

function routeFromUrl() {
  const hash = location.hash.slice(1).split('?')[0];
  if (Object.hasOwn(views,hash)) return hash;
  const query = new URLSearchParams(location.search).get('view');
  return {dashboard:'home',resume:'studio',library:'studio',jobs:'jobs',interview:'interview',diagnosis:'growth','project-questions':'project'}[query] || 'home';
}

function workLayer(route, query) {
  if (route === 'home') return false;
  if (route === 'workflow') return true;
  return query.get('work') === '1' || query.has('job') || query.has('resume') || query.has('session')
    || (route === 'growth' && query.has('report')) || (route === 'jobs' && (query.has('q') || query.has('posting')));
}

function skyLayer(query) {
  if (getSkill(query.get('skill'))) return 'skill';
  return query.get('atlas') === '1' ? 'atlas' : 'sky';
}

async function navigate(focus = false){
  controller?.abort();
  controller = new AbortController();
  const {signal} = controller;
  const route = routeFromUrl();
  const query = new URLSearchParams(location.hash.split('?')[1]);
  const legacyView = new URLSearchParams(location.search).get('view');
  if (!location.hash && legacyView && route!=='home') {
    query.set('work','1');
    if (legacyView==='library') query.set('library','1');
  }
  const working = workLayer(route,query);
  quickNav.open = false;
  $('#notice').dataset.visible = 'false';
  document.body.dataset.layer = working ? 'work' : 'cover';
  if($('#dialog').open) $('#dialog').close();
  document.body.dataset.route = route;
  document.title = routes[route][0]+' · 启程';
  mapHome.hidden = route === 'home';
  window.scrollTo(0,0);
  try {
    let ready;
    if (route === 'home') {
      city(root, signal);
    } else if (working) {
      root.innerHTML = workHeader(route, query) + '<div id="work-content"></div>';
      ready = views[route]($('#work-content', root), signal, query);
    } else if (route === 'jobs') {
      const layer = skyLayer(query);
      if (layer === 'skill') {
        const id = query.get('skill');
        document.title = getSkill(id).name+' · 启程';
        skillDetail(root,id,query.get('from') === 'atlas');
      } else if (layer === 'atlas') {
        document.title = '技能图鉴 · 启程';
        skillAtlas(root);
      } else {
        observatory(root, signal, query);
      }
    } else {
      cover(root, route, signal);
    }
    if (focus) {
      const selected = $('.job-star[aria-pressed="true"]',root);
      (selected || root).focus({preventScroll:true});
    }
    await ready;
  } catch(cause){
    if(signal.aborted) return;
    const target=$('.workspace',root);
    if(target) target.innerHTML=error(cause);
    else root.innerHTML=error(cause);
  }
}
const harborJourneys = new Set(['studio','interview','growth','project','jobs']);

async function settleJourneyArt() {
  const image = $('.cover-photo,.reference-sky-background,.city-art',root);
  if (!image || image.complete) return;
  let timeout;
  await Promise.race([
    image.decode().catch(()=>{}),
    new Promise(resolve=>{ timeout=setTimeout(resolve,180); })
  ]);
  clearTimeout(timeout);
}

window.addEventListener('hashchange', () => {
  const from = document.body.dataset.route;
  const to = routeFromUrl();
  const query = new URLSearchParams(location.hash.split('?')[1]);
  const working = workLayer(to,query);
  const changingLayer = (document.body.dataset.layer === 'work') !== working;
  const currentSky = $('[data-sky-view]',root)?.dataset.skyView ?? null;
  const nextSky = to === 'jobs' && !working ? skyLayer(query) : null;
  const changingSky = from === 'jobs' && to === 'jobs' && currentSky !== nextSky;
  const skyBoot = nextSky === 'sky' && currentSky !== 'sky' && !query.has('planet');
  const roomOpening = !working && ['home','studio','interview','growth','project'].includes(to);
  const fromCover = document.body.dataset.layer !== 'work';
  const leavingHarbor = from === 'home' && !working && harborJourneys.has(to) &&
    (to !== 'jobs' || nextSky === 'sky');
  const returningHarbor = to === 'home' && fromCover && harborJourneys.has(from);
  const journey = leavingHarbor || returningHarbor;
  const canAnimate = (journey || (!skyBoot && !roomOpening && (from !== to || changingLayer || changingSky)))
    && document.startViewTransition && !document.body.classList.contains('no-motion')
    && !matchMedia('(prefers-reduced-motion: reduce)').matches;
  finishTransition(true);
  const html = document.documentElement;
  const restoreLandmark = () => {
    if (returningHarbor) $('.city-landmark[data-destination="'+from+'"]',root)?.focus({preventScroll:true});
  };
  const currentPlanet = $('.sector-header,.job-star[aria-pressed="true"],.job-posting,.skill-room-detail',root);
  const nextSector = nextSky === 'skill' ? query.get('skill') : query.get('sector');
  const nextPosting = query.get(working ? 'posting' : 'planet');
  const sharePlanet = canAnimate && from === 'jobs' && to === 'jobs' && (changingLayer || changingSky)
    && ((nextSector && currentPlanet?.dataset.sector === nextSector)
      || (nextPosting && currentPlanet?.dataset.posting === nextPosting));
  document.body.toggleAttribute('data-share-planet',Boolean(sharePlanet));
  if (!canAnimate) {
    navigate(true);
    restoreLandmark();
    return;
  }
  const room = query.get('room') === 'meeting' ? 'meeting' : 'window';
  const cafeLight = from === 'interview' && to === 'interview' && working
    ? $('.cafe-seat[data-room-option="'+room+'"] .seat-light',root) : null;
  const target = (cafeLight || $('.city-landmark[data-destination="'+to+'"] .landmark-beacon',root)
    || (to === 'jobs' ? currentPlanet?.querySelector('.planet-surface') : null))?.getBoundingClientRect();
  document.documentElement.style.setProperty('--jump-x', target ? target.left+target.width/2+'px' : '50%');
  document.documentElement.style.setProperty('--jump-y', target ? target.top+target.height/2+'px' : '50%');
  const requestedHash = location.hash;
  const markJourneyPlace = () => {
    const place = leavingHarbor ? to : from;
    $(`.city-landmark[data-destination="${place}"] .landmark-beacon,[data-place-mark="${place}"]`,root)
      ?.setAttribute('data-journey-mark','');
  };
  if (journey) {
    html.dataset.harborJourney = leavingHarbor ? to : from;
    html.dataset.harborDirection = leavingHarbor ? 'out' : 'back';
    markJourneyPlace();
  }
  transition = document.startViewTransition(async () => {
    if (location.hash !== requestedHash) return;
    navigate(true);
    restoreLandmark();
    if (journey) {
      markJourneyPlace();
      await settleJourneyArt();
    }
  });
  const current = transition;
  watchTransition(current);
  current.ready.catch(()=>{});
  current.finished.finally(() => {
    if (transition === current) {
      transition = null;
      clearTransitionArtifacts();
    }
  }).catch(()=>{
    if (transition === current) finishTransition(true);
  });
});

document.addEventListener('keydown', event => {
  if (event.key !== 'Escape') return;
  const dialog = $('#dialog');
  if (dialog?.open) {
    dialog.close();
    event.preventDefault();
    return;
  }
  if (transition || document.documentElement.hasAttribute('data-harbor-journey')
      || document.body.hasAttribute('data-share-planet')) {
    recoverStuckMotion();
  }
});

window.addEventListener('pageshow', () => {
  if (document.documentElement.hasAttribute('data-harbor-journey')
      || document.body.hasAttribute('data-share-planet')) {
    requestAnimationFrame(() => recoverStuckMotion());
  }
});

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && transition
      && performance.now() - transitionStartedAt > 1600) {
    recoverStuckMotion();
  }
});
quickNav.addEventListener('click',event => {
  const destination = event.target.closest('a');
  if (!destination) return;
  quickNav.open = false;
  if (destination.hash === location.hash) $('summary',quickNav).focus();
});
quickNav.addEventListener('keydown',event => {
  if (event.key !== 'Escape' || !quickNav.open) return;
  quickNav.open = false;
  $('summary',quickNav).focus();
});
document.addEventListener('pointerdown',event => {
  if (quickNav.open && !quickNav.contains(event.target)) quickNav.open = false;
});
$('.skip').addEventListener('click',event=>{event.preventDefault();root.focus();});
window.addEventListener('careernav:demo-filter-changed', () => navigate());
initAuth(() => navigate());
