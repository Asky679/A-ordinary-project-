# 启程美术素材清单

图片负责建筑、人物、植物与室内光线的实际质感。CSS、Canvas 负责构图、实时交互、局部变化与可访问的入口。图片里不烘焙产品文字、地标和控制组件。

| 素材 | 艺术规格与场景 | 比例、透明和层次 | 动态处理 | 交付与当前状态 |
| --- | --- | --- | --- | --- |
| 港湾手记主视觉 | 清晨的海湾与五栋可辨建筑，水粉笔触和纸面颗粒，统一三分之四视角。 | 1672 × 941，不透明。远岸、建筑、岸线、水面清楚分层，左上留导览位置。 | 浏览器绘制路径、焦点和水光，地标随图片尺寸校准。 | 内置 ImageGen 制作，已接入 `harbor-morning.webp` 与 `harbor-morning-small.webp`。 |
| 案头窗光 | 棉纸、钢笔与黄铜夹构成前景，木桌与窗景形成中远景。编辑页只取低对比横幅。 | 1448 × 1086，不透明。下一阶段可把窗框和光线分层。 | 纸页由 UI 展开，局部窗光缓慢变化。 | 内置 ImageGen 制作，已接入 `studio-desk.webp` 和对应小图。 |
| 咖啡馆双机位 | 窗边与会议桌分别服务两种难度。统一色温与材质，窗边更亲切，会议场景光源更稳。 | 两张 16:9 不透明 WebP。后续可分离桌面前景、窗和窗外环境。 | 选择时遮罩叠化。若增加人物，需要连续姿态和与台词无关的轻动作资产。 | 内置 ImageGen 制作，已接入 `cafe-window.webp`、`cafe-meeting.webp` 和对应小图。分层机位和人物为后续素材需求。 |
| 天文主场与星球 | 蓝紫黑洞吸积盘与高反差星尘，四颗可点选行星采用真实任务影像。 | 主场 3200 × 1800 和 1600 × 900 WebP，四颗星球为长边 400 px 的透明 WebP。 | 以 CSS 调色并控制星球亮度，Canvas 星尘低速漂移。岗位详情沿用同一颗球面。 | 素材位于 `atelier/visuals/observatory`，来源见下。技能图鉴使用 `atlas-*.webp`。 |
| 岗位星图加载 | 复用参考页的舱标、双层波形与六边形几何。 | 两个原始 SVG 图形，100 块运行时生成的 SVG 六边形。 | 岗位与背景就绪后，中心到四周逐块缩开。 | `atelier/visuals/cosmic/loading-ship.svg` 与 `loading-wave.svg` 源自参考首页。 |
| 温室观察记录 | 植物水彩与彩铅，玻璃、叶片和前景路径有明确层次，日光来自右上。 | 1672 × 941，不透明。理想扩展为分层背景、前景叶片透明 WebP 和独立光照遮罩。 | 透过玻璃的局部光束，实际记录的显示由后端数据触发。 | 内置 ImageGen 制作，已接入 `garden-journal.webp` 和对应小图。 |
| 工程图版 | 青版与石墨的器件拆解图，作为理解工程结构的视觉隐喻。源码与行号由真实分析结果提供。 | 1448 × 1086，不透明。若拆解本身要响应交互，需要拆出器件透明层。 | 图版一次揭示，等待扫描与实际解析状态绑定。 | 内置 ImageGen 制作，已接入 `project-plate.webp` 和对应小图。 |

网页素材位于 `src/main/resources/static/atelier/visuals/`。场景按需加载，封面使用完整 WebP，工作页页眉使用小图。字体沿用有许可证的本地 Inter 与 Instrument Serif。

## 岗位星图素材来源

- 背景采用 NASA/JPL-Caltech 的 PIA20051，调整了画幅和蓝紫色调。[黑洞冕概念图](https://science.nasa.gov/photojournal/shifting-coronas-around-black-holes-artist-concept/)
- 水星采用 NASA/Johns Hopkins University Applied Physics Laboratory/Carnegie Institution of Washington 的 PIA16853，裁出球面并冷调处理。[水星增强色影像](https://science.nasa.gov/photojournal/colors-of-the-innermost-planet-view-1/)
- 木星采用 NASA/JPL/University of Arizona 的 PIA02873，裁出球面并镜像对齐受光方向。[木星全景](https://science.nasa.gov/resource/high-resolution-globe-of-jupiter/)
- 海王星采用 NASA/JPL 的 PIA01492，裁出球面并降低饱和度。[海王星全盘](https://science.nasa.gov/photojournal/neptune-full-disk-view/)
- 天王星采用 NASA/JPL-Caltech 的 PIA18182，裁出球面并镜像对齐受光方向。[旅行者 2 号天王星影像](https://science.nasa.gov/photojournal/uranus-as-seen-by-nasas-voyager-2/)

## 内置 ImageGen 提示词

### 港湾主视觉

```text
Use case: stylized-concept. Create an ORIGINAL full-bleed landscape website backdrop, very wide 16:9 if supported, no UI. A welcoming coastal future neighborhood just after sunrise, seen from one consistent high three-quarter viewpoint, mature painted architectural illustration for a premium narrative game. Visual medium: confident layered gouache on textured paper with fine dry-brush edges and a subtle printed grain, carefully observed stone, copper, oak and plants. Deliberate large silhouettes with rich small architectural detail. Quiet blue-green sea, matte sage roofs and olive trees, warm terracotta roofs and apricot morning light, cream limestone. Visible shadow direction from sun upper right, soft cool ambient shade. Five distinct approachable destinations around a single curved walkable bay: a narrow workshop with tall windows at x 35% y 41%; a large domed observatory on the hill x 58% y 23%; a curved cozy café facing the waterfront x 65% y 46%; a small glass greenhouse x 21% y 60%; and a slim engineering atelier by the right pier x 86% y 53%. Connect these buildings by coherent stone paths with planting and water on the lower right. The composition has distinct foreground, midground, and far shoreline and enough brightness to see the artwork. The leftmost 25% is relatively quiet olive coastline and mist where a separate HTML paper folio overlays. Only one small distant light rail bridge if it fits naturally. Restrained, human, tactile. No text, no letters, no numbers, no labels, no markers, no logos, no watermarks, no mockup, no gradient graphics, no shiny CGI, no cartoon characters, no neon, no absurd roofs or repeating buildings.
```

### 咖啡馆窗边

```text
Use case: stylized-concept. An original wide 16:9 environment painting for the entrance to a calm interview practice café in a harbor neighborhood. Editorial animation background for an adult narrative film, delicate painted wood grain and linen, restrained gouache texture and observational perspective. Camera from a seated visitor's eye level, medium-wide. A pair of well-made chairs across a small unoccupied round oak table, cups and a notebook off-center. Large arched stone window on the right shows a softly blurred quiet coastal street with a hint of blue-green water. Through the window late afternoon light from high right falls across the table and aged oak floor, the shadowed near foreground is naturally deep walnut so an HTML title and seating controls can sit at the bottom. Light mineral plaster, tobacco wood, terracotta and golden cream, tiny sage details. Mature, warm and quiet. Selectively detailed; one single clear focal place to sit, foreground chair edge for depth. No text, lettering, humans, cartoon mascots, branding, signage, interface, overlays, excessive bokeh or deep vignette.
```

### 咖啡馆会议位

```text
Use case: stylized-concept. An original wide 16:9 environment painting of a quiet interview practice room at the back of a coastal café. Same adult editorial animation world as a soft late-afternoon Mediterranean harbor café, painted gouache and fine dry brush. Seated eye level, centered natural view of one long oak meeting table and two chairs, a pair of notes and water glasses. A broad east-facing mullioned window at right, soft direct daylight on the table, weathered pale limestone and walnut walls, textured neutral linen. Exterior glimpses of a coastal courtyard and potted olive trees; light is neutral cream with amber edges and small sage details. More orderly than the café with direct, professional eye lines. Leave the natural dark walnut foreground at the bottom for separate HTML title. Strong foreground, middle ground and background, tactile and composed with no blank flat gradients. No text, humans, lettering, signage, logos, UI, floor plan, sterile corporate blues or futuristic technology.
```

### 职业天文馆星体

```text
Use case: stylized-concept. Create an original premium 16:9 astronomical environment matte painting for a web observatory, no interface. One immense gravitationally lensed black hole and its accretion disk, clearly the only major subject. Ink-black event horizon centered at x 60%, y 47%, with a clearly defined black sphere-like silhouette, occupy about 34% of the whole width. The accretion disk wraps visibly around it, its underside bent into a bright arc and its upper ring thinner and softer; the plane sweeps diagonally from lower left to upper right. Visually plausible gravitational lensing, refined photochemical bloom, thin long-exposure striations and dense luminous matter, subtle film grain. Graphite and deep muted indigo surround the luminous cream and pale copper rim; tiny touches of smoky lavender only at the outer edge. Wide quiet near-black left 26% for overlaid HTML and below-left for a panel. No prominent planets, no foreground objects, no readable constellation marks, no labels, no words, no coordinates, no symbols, no numbers, no diagrams, no decorative futuristic lines, no signage, no UI, no watermark. Refined cinematic astronomy, avoid cartoon color or cheap blue-purple gradient.
```

### 简历工作室

```text
Use case: photorealistic-natural. Original horizontal premium editorial still life photograph for a quiet resume-writing studio, approximately 4:3 composition safe in the center. Seen from across a broad, gently worn oak desk at an intimate seated angle. Cream cotton-paper sheets and two thin linen folders on the desk, one brass paper clip and a fountain pen, all convincingly real. Late-morning light from the tall mullioned window on the right rakes across the blank sheets; detailed soft shadows, cool stone window reveal and muted olive foliage outside. Hand-bound books in the deeper left middle ground, dark olive cloth chair back at a far edge, no busy clutter. Cotton fiber, polished brass, fine wood grain, warm highlights, neutral shadows, subtle 35 mm print grain, restrained editorial art direction. A scene about sorting out your own story, composed around the main paper bundle. No human, no visible text of any kind, no watermark, no graphic UI, no typewriter with letters, no glowing effects.
```

### 成长花园

```text
Use case: illustration-story. An original mature botanical watercolor-and-colored-pencil landscape for a growth journal website, extra-wide 16:9. The view from under a wrought-iron and glass conservatory, opening out onto the edge of a coastal town. Carefully observed young leafy plants, a few soft white flowers, and worn terracotta pots in the foreground, a clean stone path and a small dark green bench in the middle, sunlight and a coastal slope through glass behind. A delicate light beam from the upper right catches lime-green leaves, with layered moss, olive and warm ivory. Fine colored-pencil contour and translucent pigment bloom over a thick cotton-paper tooth. Botanical artist's patient observation, grown-up restraint, natural light and depth, not a busy cottagecore montage. Visually arresting but very calm. The main image should work as a broad crop along the top of a site, so preserve the path and bench in the middle band. No people, no words, no letters, no labels, no decorative frames, no watermark, no interface.
```

### 项目观察室

```text
Use case: stylized-concept. A single high-end cyanotype and graphite industrial illustration for a software project analysis workbench, landscape 4:3 safe framing. A genuinely drafted technical section of a modern architectural desk on a muted deep petroleum-blue archival paper ground. One precise coherent subject, the exploded stack of a small modular instrument on the bench: a brass frame, layers of etched circuit, one finely ribbed side plate, aligned screws and wooden base, from a deliberate axonometric drafting viewpoint. Fine off-white etched linework, detailed cross-hatching and oxidized cyan, restrained cream highlights, warm paper fibers. Each component is visibly part of the same device and space. Technical copper-plate lithographic quality, depth between parts. Leave breathing room at edges for separate browser-made frame. No letters, no numbers, no notes, no arrows with annotations, no bogus text, no wireframe dashboard, no HUD, no watermark, no repetitive geometric decoration.
```
