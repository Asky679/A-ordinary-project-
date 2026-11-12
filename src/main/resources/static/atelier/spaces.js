import { routes, link, placeMark } from './ui.js';
import { getJobSector } from './job-sectors.js';
import { mountSceneArt } from './scene-art.js';

const spaces = {
  studio: { work:'简历编辑台', description:'填写你的经历，随时查看排版和修改结果。' },
  jobs: { work:'岗位目录', description:'按城市、技能或简历查找岗位。' },
  interview: { work:'面试练习', description:'带上一份简历，练习目标岗位的问题。' },
  growth: { work:'成长记录', description:'回看诊断与面试，选一件可以先改进的事。' },
  workflow: { work:'求职准备', description:'把岗位探索、面试练习和学习路线串起来。' },
  project: { work:'项目工作台', description:'用源码准备项目面试和后续追问。' }
};

const rooms = [
  { title:'窗边圆桌', detail:'标准练习', mode:'window' },
  { title:'里间入口', detail:'压力练习', mode:'meeting' }
];
const artSizes = {
  'studio-desk':[1448,'(max-width: 760px) 100vw, 52vw'],
  'cafe-window':[1672,'100vw'],
  'garden-journal':[1672,'100vw'],
  'project-plate':[1448,'(max-width: 760px) 100vw, 62vw']
};

function picture(name, css = 'cover-photo', priority = 'high') {
  const [width,sizes] = artSizes[name];
  return `<img class="${css}" src="/atelier/visuals/${name}.webp"
    srcset="/atelier/visuals/${name}-small.webp 860w, /atelier/visuals/${name}.webp ${width}w"
    sizes="${sizes}" alt="" fetchpriority="${priority}" decoding="async">`;
}

export function workHeader(route, query) {
  if (route === 'jobs' && (query.has('posting') || getJobSector(query.get('sector')))) return '';
  const inMeeting = route === 'interview' && query.get('room') === 'meeting';
  const images = { studio:'studio-desk-small', interview:inMeeting ? 'cafe-meeting-small' : 'cafe-window-small',
    workflow:'harbor-morning-small', jobs:'observatory-lens-small', growth:'garden-journal-small', project:'project-plate-small' };
  const image = images[route];
  const isLibrary = route === 'studio' && query.has('library');
  return `<header class="work-masthead">
    <div class="work-masthead-art" style="--masthead-image:url('/atelier/visuals/${image}.webp')" aria-hidden="true"></div>
    <div class="work-masthead-inner">
      ${link(route==='workflow'?'home':route,route==='workflow'?'← 返回地图':'← 返回'+routes[route][0],'back-link')}
      <h1>${isLibrary?'简历仓库':spaces[route].work}</h1>
      <p>${isLibrary?'查找、打开或导出已保存的简历。':spaces[route].description}</p>
    </div>
  </header>`;
}

function studioScene() {
  return `<section class="room-stage folio-room" aria-labelledby="space-title">
    <figure class="folio-picture">
      ${picture('studio-desk')}
    </figure>
    <div class="folio-sheet">
      <div class="folio-heading">
        ${placeMark('studio')}
        <h1 id="space-title">简历<br>工作室</h1>
        <p>写下你做过的事，再把职责、方法和结果慢慢补清楚。</p>
        ${link('studio?work=1','打开简历编辑台 ↗','room-enter')}
      </div>
      <span class="folio-corner" aria-hidden="true"></span>
    </div>
  </section>`;
}

function interviewScene() {
  return `<section class="room-stage cafe-room" aria-labelledby="space-title">
    <div class="cafe-scene">
      ${picture('cafe-window')}
      <div class="cafe-shade" aria-hidden="true"></div>
      <div class="cafe-windowlight" aria-hidden="true"></div>
    </div>
    <div class="cafe-heading">
      ${placeMark('interview')}
        <h1 id="space-title">面试咖啡馆</h1>
      <p>选一张桌子，开始文字面试练习。</p>
    </div>
    <nav class="cafe-seats" aria-label="选择练习座位">
      ${rooms.map(room=>`<a class="cafe-seat" data-room-option="${room.mode}" href="#interview?work=1&room=${room.mode}">
        <span class="seat-light" aria-hidden="true"></span>
        <span class="seat-caption"><strong>${room.title}</strong><small>${room.detail}</small><span class="seat-arrow" aria-hidden="true">↗</span></span>
      </a>`).join('')}
    </nav>
    <a class="cafe-direct" href="#interview?work=1">直接进入文字面试 →</a>
  </section>`;
}

function gardenScene() {
  return `<section class="room-stage glasshouse-room" aria-labelledby="space-title">
    <div class="glasshouse-picture">
      ${picture('garden-journal')}
    </div>
    <div class="garden-journal">
      <div>
        ${placeMark('growth')}
        <h1 id="space-title">成长花园</h1>
        <p>把练习留下来。回看的时候，从一条具体建议开始。</p>
        ${link('growth?work=1','查看成长记录 ↗','room-enter')}
      </div>
    </div>
  </section>`;
}

function projectScene() {
  return `<section class="room-stage blueprint-room" aria-labelledby="space-title">
    <div class="project-intro">
      ${placeMark('project')}
        <h1 id="space-title">项目<br>观察室</h1>
      <p>带来一份 Java 源码，从真实的代码、分支和实现细节开始准备面试。</p>
      ${link('project?work=1','打开项目工作台 ↗','room-enter')}
    </div>
    <figure class="blueprint-plate">
      ${picture('project-plate')}
      <div class="blueprint-grid" aria-hidden="true"></div>
    </figure>
  </section>`;
}

const renderScene = { studio:studioScene, interview:interviewScene, growth:gardenScene, project:projectScene };

export function cover(root, route, signal) {
  root.innerHTML = renderScene[route]();
  mountSceneArt(root,route,signal);
  if (route !== 'project') return;
  const room = root.firstElementChild;
  room.querySelector('.cover-photo').decode().then(()=>{
    if (!signal.aborted) room.dataset.arrival = 'direct';
  }).catch(()=>{});
}
