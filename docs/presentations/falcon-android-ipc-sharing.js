const pptxgen = require('pptxgenjs');

const pptx = new pptxgen();
pptx.layout = 'LAYOUT_16x9';
pptx.author = 'FastIPC / Falcon';
pptx.company = 'FastIPC';
pptx.subject = 'Android IPC 全景与 Falcon 实践';
pptx.title = 'Android IPC 全景与 Falcon 实践';
pptx.lang = 'zh-CN';
pptx.theme = {
  headFontFace: 'Arial Unicode MS', bodyFontFace: 'Arial Unicode MS', lang: 'zh-CN'
};
pptx.defineLayout({ name: 'CUSTOM_WIDE', width: 13.333, height: 7.5 });
pptx.layout = 'CUSTOM_WIDE';

const C = {
  ink: '101614', dark: '16201C', panel: '1D2924', green: '3DDC84', green2: '7BF1AE',
  amber: 'FFB347', red: 'FF6B5E', cream: 'F4F1E8', paper: 'FBFAF5', soft: 'E5E9E4',
  muted: 'AAB5AF', text: '1B2521', line: 'CBD3CE', white: 'FFFFFF', blue: '69A9FF', purple: 'B99CFF'
};
const FONT = 'Arial Unicode MS';
const CODE = 'Menlo';
const W = 13.333, H = 7.5;
const makeShadow = () => ({ type: 'outer', color: '000000', blur: 2, angle: 45, distance: 1, opacity: 0.12 });

function addText(slide, text, x, y, w, h, opts = {}) {
  slide.addText(text, {
    x, y, w, h, fontFace: opts.fontFace || FONT, fontSize: opts.fontSize || 18,
    color: opts.color || C.text, bold: opts.bold || false, margin: opts.margin ?? 0,
    align: opts.align || 'left', valign: opts.valign || 'mid', breakLine: false,
    fit: opts.fit || 'shrink', paraSpaceAfterPt: opts.paraSpaceAfterPt || 0,
    isTextBox: true, ...opts
  });
}

function rect(slide, x, y, w, h, fill, radius = 0, line = null, shadow = false) {
  slide.addShape(radius ? pptx.ShapeType.roundRect : pptx.ShapeType.rect, {
    x, y, w, h, rectRadius: radius,
    fill: { color: fill }, line: line ? { color: line, width: 1 } : { color: fill, transparency: 100 },
    shadow: shadow ? makeShadow() : undefined
  });
}

function line(slide, x, y, w, h, color = C.line, width = 1.5, dash = 'solid', begin = 'none', end = 'none') {
  slide.addShape(pptx.ShapeType.line, {
    x, y, w, h, line: { color, width, dashType: dash, beginArrowType: begin, endArrowType: end }
  });
}

function circle(slide, x, y, d, fill, text = '', color = C.ink, fs = 18) {
  slide.addShape(pptx.ShapeType.ellipse, { x, y, w: d, h: d, fill: { color: fill }, line: { color: fill } });
  if (text) addText(slide, text, x, y, d, d, { color, fontSize: fs, bold: true, align: 'center', valign: 'mid' });
}

function addHeader(slide, section, title, index, dark = false) {
  const fg = dark ? C.white : C.text;
  addText(slide, section.toUpperCase(), 0.62, 0.35, 3.2, 0.24, { fontSize: 10.5, bold: true, color: dark ? C.green2 : '267A4B', charSpacing: 1.2 });
  addText(slide, title, 0.62, 0.65, 11.9, 0.6, { fontSize: 27, bold: true, color: fg });
  addText(slide, String(index).padStart(2, '0'), 12.25, 0.38, 0.45, 0.26, { fontSize: 10, color: dark ? C.muted : '6D7872', align: 'right' });
}

function addFooter(slide, index, dark = false) {
  line(slide, 0.62, 7.08, 12.05, 0, dark ? '33443C' : 'D8DDD9', 0.8);
  addText(slide, 'ANDROID IPC × FALCON', 0.62, 7.15, 2.5, 0.16, { fontSize: 8.5, color: dark ? C.muted : '7A8580', charSpacing: 1 });
  addText(slide, `${index} / 26`, 10.8, 7.15, 1.87, 0.16, { fontSize: 8.5, color: dark ? C.muted : '7A8580', align: 'right' });
}

function addNotes(slide, minutes, script, caveat = '') {
  const note = [`建议时长：${minutes}`, `核心讲述：${script}`];
  if (caveat) note.push(`边界提醒：${caveat}`);
  slide.addNotes(note.join('\n'));
}

function addCard(slide, x, y, w, h, title, body, accent = C.green, opts = {}) {
  rect(slide, x, y, w, h, opts.fill || C.white, opts.radius ?? 0.14, opts.line || 'E1E6E2', opts.shadow ?? false);
  rect(slide, x, y, 0.07, h, accent, 0);
  addText(slide, title, x + 0.22, y + 0.16, w - 0.38, 0.28, { fontSize: opts.titleSize || 15, bold: true, color: opts.titleColor || C.text });
  addText(slide, body, x + 0.22, y + 0.52, w - 0.38, h - 0.66, { fontSize: opts.bodySize || 11.5, color: opts.bodyColor || '52605A', valign: 'top', breakLine: true });
}

function pill(slide, text, x, y, w, fill, color = C.text, fs = 11) {
  rect(slide, x, y, w, 0.34, fill, 0.16);
  addText(slide, text, x, y, w, 0.34, { fontSize: fs, bold: true, color, align: 'center' });
}

function node(slide, x, y, w, h, title, sub, fill = C.paper, accent = C.green, dark = false) {
  rect(slide, x, y, w, h, fill, 0.14, dark ? '35463E' : 'DDE3DF');
  circle(slide, x + 0.16, y + 0.17, 0.28, accent);
  addText(slide, title, x + 0.55, y + 0.12, w - 0.7, 0.3, { fontSize: 13, bold: true, color: dark ? C.white : C.text });
  addText(slide, sub, x + 0.18, y + 0.53, w - 0.36, h - 0.65, { fontSize: 10.2, color: dark ? C.muted : '617069', valign: 'top' });
}

function arrow(slide, x1, y1, x2, y2, color = C.green, width = 2) {
  line(slide, x1, y1, x2 - x1, y2 - y1, color, width, 'solid', 'none', 'triangle');
}

function chapterTag(slide, n, text, x, y, dark = false) {
  circle(slide, x, y, 0.38, C.green, String(n), C.ink, 12);
  addText(slide, text, x + 0.52, y, 2.7, 0.38, { fontSize: 11.5, bold: true, color: dark ? C.white : C.text });
}

// 01
function slide01Title() {
  const s = pptx.addSlide(); s.background = { color: C.ink };
  for (let i = 0; i < 7; i++) {
    const yy = 0.7 + i * 0.85;
    line(s, 7.35, yy, 4.7, 0, i % 2 ? '33463D' : '254036', 1.2, i % 3 ? 'dash' : 'solid');
    circle(s, 8.0 + (i % 3) * 1.25, yy - 0.08, 0.16, i === 3 ? C.green : '4D645A');
  }
  rect(s, 8.2, 2.05, 3.75, 3.0, C.panel, 0.18, '365145');
  addText(s, 'CLIENT', 8.55, 2.42, 1.05, 0.25, { fontSize: 11, bold: true, color: C.green2, charSpacing: 1.5 });
  addText(s, 'Binder', 8.55, 2.82, 2.9, 0.72, { fontSize: 38, bold: true, color: C.white });
  addText(s, 'transaction', 8.55, 3.51, 2.9, 0.46, { fontSize: 20, color: C.muted });
  pill(s, 'KSP GENERATED', 8.55, 4.26, 1.65, C.green, C.ink, 9.5);
  pill(s, 'TYPED BUNDLE', 10.34, 4.26, 1.32, '33483E', C.green2, 9.5);
  addText(s, 'ANDROID IPC', 0.75, 0.75, 2.5, 0.25, { fontSize: 11, bold: true, color: C.green2, charSpacing: 2 });
  addText(s, 'Android IPC 全景\n与 Falcon 实践', 0.75, 1.45, 6.2, 2.25, { fontSize: 43, bold: true, color: C.white, breakLine: true, valign: 'top' });
  addText(s, '从 Binder 成本模型到编译期 IPC 框架', 0.78, 4.16, 5.5, 0.42, { fontSize: 18, color: C.muted });
  chapterTag(s, 1, '选对语义', 0.78, 5.44, true); chapterTag(s, 2, '控制成本', 3.22, 5.44, true); chapterTag(s, 3, '工程治理', 5.56, 5.44, true);
  addText(s, '45 MIN · TECH SHARING', 0.78, 6.75, 3.2, 0.2, { fontSize: 9.5, color: '718179', charSpacing: 1.3 });
  addNotes(s, '1 分钟', '今天不只比较六种 IPC 方式，还会沿一条真实调用链拆开 Falcon 的实现。', 'Falcon 构建在 Binder 之上。');
}

// 02
function slide02Scenario() {
  const s = pptx.addSlide(); s.background = { color: C.paper }; addHeader(s, 'CONTEXT', '车载系统：多进程不是例外，而是系统形态', 2);
  const apps = [
    ['车辆控制', '高可靠命令', C.red], ['导航', '路线 / 定位', C.blue], ['媒体', '状态 / 播放', C.purple],
    ['座舱 UI', '多屏协同', C.green], ['第三方 App', '不同签名', C.amber]
  ];
  apps.forEach((a, i) => {
    const x = 0.72 + i * 2.48; node(s, x, 1.65, 2.05, 1.18, a[0], a[1], C.white, a[2]);
    arrow(s, x + 1.02, 2.88, 6.66, 4.34, '8BA49A', 1.2);
  });
  rect(s, 4.25, 4.08, 4.82, 1.65, C.dark, 0.22, '2D493C', true);
  addText(s, '跨进程通信中枢', 4.62, 4.36, 4.1, 0.36, { fontSize: 23, bold: true, color: C.white, align: 'center' });
  addText(s, '隔离故障 · 验证身份 · 管理连接 · 约束成本', 4.55, 4.93, 4.22, 0.28, { fontSize: 12.5, color: C.green2, align: 'center' });
  const bottom = [['隔离', '一个进程崩溃不拖垮全车'], ['安全', '不同供应商需要显式互信'], ['演进', '模块独立交付与升级']];
  bottom.forEach((a, i) => addCard(s, 0.78 + i * 4.17, 6.0, 3.75, 0.78, a[0], a[1], [C.green, C.amber, C.blue][i], { bodySize: 10.5, titleSize: 13 }));
  addFooter(s, 2); addNotes(s, '1.5 分钟', '车载系统天然包含多个进程和多个签名主体，IPC 既是性能问题，也是身份、生命周期和治理问题。');
}

// 03
function slide03CallJourney() {
  const s = pptx.addSlide(); s.background = { color: C.cream }; addHeader(s, 'MENTAL MODEL', '一次 IPC 调用，至少穿过 8 个成本点', 3);
  const steps = [
    ['业务调用', '普通方法语义'], ['Proxy', '参数打包'], ['序列化', 'Bundle / Parcel'], ['驱动', 'binder transaction'],
    ['调度', 'Binder 线程池'], ['解码', '还原参数'], ['服务执行', '真实业务'], ['返回', '反向同路径']
  ];
  steps.forEach((it, i) => {
    const x = 0.55 + i * 1.57; const top = i % 2 === 0 ? 2.0 : 3.15;
    circle(s, x + 0.43, top - 0.58, 0.45, i === 3 || i === 4 ? C.amber : C.green, String(i + 1), C.ink, 12);
    rect(s, x, top, 1.32, 1.15, i === 3 || i === 4 ? 'FFF2D9' : C.white, 0.12, 'DDE2DE');
    addText(s, it[0], x + 0.1, top + 0.18, 1.12, 0.25, { fontSize: 12.5, bold: true, align: 'center' });
    addText(s, it[1], x + 0.09, top + 0.57, 1.14, 0.3, { fontSize: 9.5, color: '617069', align: 'center' });
    if (i < 7) arrow(s, x + 1.32, top + 0.58, x + 1.57, (i + 1) % 2 === 0 ? 2.58 : 3.72, '93A59D', 1.2);
  });
  rect(s, 1.05, 5.58, 11.25, 0.82, C.dark, 0.16);
  addText(s, '关键心智：IPC ≠ 本地函数调用', 1.35, 5.78, 4.0, 0.28, { fontSize: 18, bold: true, color: C.white });
  addText(s, '真正的优化，通常从减少调用次数与数据量开始', 6.0, 5.78, 5.95, 0.28, { fontSize: 15, color: C.green2, align: 'right' });
  addFooter(s, 3); addNotes(s, '1.5 分钟', '先把 IPC 当成一条管线看：每层都可能排队、复制、编码或切换线程。');
}

// 04
function slide04Landscape() {
  const s = pptx.addSlide(); s.background = { color: C.ink }; addHeader(s, 'LANDSCAPE', 'Android IPC：先按语义分类，再谈性能', 4, true);
  const items = [
    ['RPC', 'Binder / AIDL', '同步请求响应', C.green], ['消息', 'Messenger', '串行命令队列', C.blue],
    ['数据访问', 'ContentProvider', 'URI + CRUD', C.purple], ['事件通知', 'Broadcast', '一对多分发', C.amber],
    ['流式 / 跨平台', 'Socket', '自定义协议', C.red], ['大数据共享', 'SharedMemory', '共享页 + 同步', C.green2]
  ];
  items.forEach((it, i) => {
    const col = i % 3, row = Math.floor(i / 3); const x = 0.72 + col * 4.18, y = 1.55 + row * 2.37;
    rect(s, x, y, 3.72, 1.87, C.panel, 0.18, '34483F');
    pill(s, it[0], x + 0.2, y + 0.2, 1.02, it[3], C.ink, 10);
    addText(s, it[1], x + 0.2, y + 0.73, 3.25, 0.38, { fontSize: 21, bold: true, color: C.white });
    addText(s, it[2], x + 0.2, y + 1.28, 3.18, 0.27, { fontSize: 11.5, color: C.muted });
  });
  addText(s, '不存在“最快的万能 IPC”', 0.75, 6.48, 5.3, 0.38, { fontSize: 22, bold: true, color: C.white });
  addText(s, '只有与通信语义、数据规模和拓扑匹配的选择', 6.2, 6.54, 6.25, 0.28, { fontSize: 13.5, color: C.green2, align: 'right' });
  addFooter(s, 4, true); addNotes(s, '1.5 分钟', '分类比排名更重要：RPC、消息、数据、事件、流和共享内存解决的是不同问题。');
}

// 05
function slide05Binder() {
  const s = pptx.addSlide(); s.background = { color: C.paper }; addHeader(s, 'FOUNDATION', 'Binder：Android IPC 的“系统总线”', 5);
  rect(s, 0.72, 1.53, 3.45, 4.5, C.white, 0.18, 'DCE2DE');
  rect(s, 9.16, 1.53, 3.45, 4.5, C.white, 0.18, 'DCE2DE');
  addText(s, 'CLIENT PROCESS', 0.98, 1.83, 2.0, 0.25, { fontSize: 11, bold: true, color: '47735C', charSpacing: 1 });
  addText(s, 'SERVER PROCESS', 9.42, 1.83, 2.0, 0.25, { fontSize: 11, bold: true, color: '47735C', charSpacing: 1 });
  node(s, 1.18, 2.45, 2.54, 1.1, 'Proxy', '看起来像本地对象', 'F5FAF7', C.green);
  node(s, 9.62, 2.45, 2.54, 1.1, 'Stub / Service', 'Binder 线程接收事务', 'F5FAF7', C.green);
  node(s, 1.18, 4.22, 2.54, 1.1, 'Calling identity', 'UID / PID 由内核提供', 'FFF8E9', C.amber);
  node(s, 9.62, 4.22, 2.54, 1.1, 'Thread pool', '同步、排队与阻塞', 'FFF8E9', C.amber);
  rect(s, 4.78, 2.15, 3.77, 3.25, C.dark, 0.2, '2E4B3E', true);
  addText(s, 'BINDER DRIVER', 5.31, 2.56, 2.72, 0.33, { fontSize: 17, bold: true, color: C.green2, align: 'center', charSpacing: 1.2 });
  addText(s, '对象引用\n事务路由\n调用方身份', 5.46, 3.16, 2.42, 1.38, { fontSize: 20, bold: true, color: C.white, align: 'center', breakLine: true, valign: 'mid' });
  arrow(s, 3.73, 3.0, 4.78, 3.0, C.green, 2); arrow(s, 8.55, 3.0, 9.62, 3.0, C.green, 2);
  addText(s, '系统服务几乎都在这条总线上', 4.7, 5.77, 3.95, 0.3, { fontSize: 13, bold: true, color: '466357', align: 'center' });
  addFooter(s, 5); addNotes(s, '2 分钟', 'Binder 的价值不仅是传数据，还包括对象引用、事务分发和不可伪造的调用方身份。');
}

// 06
function slide06CostModel() {
  const s = pptx.addSlide(); s.background = { color: C.cream }; addHeader(s, 'PERFORMANCE', '延迟不是一个数字，而是一组可控成本', 6);
  const costs = [
    ['切换', '用户态 ↔ 内核态', 1.0, C.green], ['复制', 'payload 与 Parcel', 1.5, C.blue], ['编解码', 'Bundle / Parcelable', 2.0, C.purple],
    ['排队', '线程池与 Handler', 2.65, C.amber], ['业务', '锁 / I/O / 计算', 3.1, C.red]
  ];
  let x = 0.9; costs.forEach((c) => {
    const w = c[2]; rect(s, x, 2.0, w, 1.0, c[3], 0.08); addText(s, c[0], x, 2.15, w, 0.27, { fontSize: 13.5, bold: true, color: C.ink, align: 'center' });
    addText(s, c[1], x, 2.52, w, 0.22, { fontSize: 8.7, color: C.ink, align: 'center' }); x += w + 0.08;
  });
  addText(s, '一次往返的总成本', 0.9, 1.55, 3.2, 0.26, { fontSize: 13, bold: true, color: '4B5B54' });
  addText(s, '→', 11.85, 2.04, 0.5, 0.8, { fontSize: 36, bold: true, color: C.text, align: 'center' });
  addText(s, 'P99', 12.1, 2.18, 0.62, 0.3, { fontSize: 14, bold: true, color: C.red, align: 'center' });
  const rules = [
    ['调用次数', '通常比单次省 20μs 更重要', C.green], ['payload', '越大，复制与编解码越突出', C.blue],
    ['线程模型', '串行 Handler 会把并发变成排队', C.amber], ['尾延迟', '平均值掩盖偶发卡顿', C.red]
  ];
  rules.forEach((r, i) => addCard(s, 0.9 + (i % 2) * 6.05, 3.75 + Math.floor(i / 2) * 1.15, 5.6, 0.88, r[0], r[1], r[2], { titleSize: 13, bodySize: 10.5 }));
  addFooter(s, 6); addNotes(s, '2 分钟', '把成本拆开，优化才有顺序：调用次数、payload 和排队往往先于传输微优化。', '不在没有设备数据时给出精确微秒结论。');
}

// 07
function slide07Aidl() {
  const s = pptx.addSlide(); s.background = { color: C.paper }; addHeader(s, 'NATIVE RPC', 'AIDL：性能基准线，也是工程基线', 7);
  rect(s, 0.78, 1.52, 5.15, 4.95, C.dark, 0.16);
  addText(s, 'interface IVehicleService {\n  VehicleState getState();\n  oneway void setMode(int mode);\n}', 1.15, 2.06, 4.5, 1.66, { fontFace: CODE, fontSize: 16.2, color: C.green2, valign: 'top', breakLine: true });
  arrow(s, 1.55, 4.55, 5.1, 4.55, C.green, 2);
  pill(s, 'Proxy', 1.14, 4.33, 1.05, C.green, C.ink); pill(s, 'Binder', 2.78, 4.33, 1.05, '33483E', C.green2); pill(s, 'Stub', 4.43, 4.33, 1.05, C.green, C.ink);
  addText(s, '平台生成 · 稳定事务码 · 类型安全', 1.15, 5.45, 4.35, 0.35, { fontSize: 13, bold: true, color: C.white, align: 'center' });
  addCard(s, 6.42, 1.52, 5.98, 1.25, '为什么快', '路径短、平台原生、编译期 Stub/Proxy，额外抽象少。', C.green, { titleSize: 16, bodySize: 12 });
  addCard(s, 6.42, 3.0, 5.98, 1.25, '为什么仍然难', '发现、连接、死亡监听、事件、安全策略仍要自行工程化。', C.amber, { titleSize: 16, bodySize: 12 });
  addCard(s, 6.42, 4.48, 5.98, 1.25, '适用场景', '稳定的一对一 RPC；接口数量少或已有成熟平台层。', C.blue, { titleSize: 16, bodySize: 12 });
  pill(s, '基准线 ≠ 万能方案', 8.22, 6.04, 2.35, 'E8F8EF', '267A4B', 12);
  addFooter(s, 7); addNotes(s, '1.7 分钟', 'AIDL 是 Falcon 应该尊重的基线：Falcon 的价值不在重新发明 Binder，而在补齐大型工程的重复工作。');
}

// 08
function slide08Messenger() {
  const s = pptx.addSlide(); s.background = { color: C.cream }; addHeader(s, 'MESSAGE IPC', 'Messenger：简单的代价，是串行队列', 8);
  rect(s, 0.86, 1.65, 3.2, 4.65, C.white, 0.18, 'DDE3DF');
  addText(s, 'CLIENT', 1.16, 1.95, 1.2, 0.25, { fontSize: 11, bold: true, color: '557067', charSpacing: 1 });
  for (let i = 0; i < 4; i++) {
    rect(s, 1.22 + i * 0.44, 2.7 + i * 0.52, 1.5, 0.52, ['EAF8F0','EAF2FF','F2ECFF','FFF1DC'][i], 0.1);
    addText(s, `Message ${i + 1}`, 1.22 + i * 0.44, 2.7 + i * 0.52, 1.5, 0.52, { fontSize: 10.5, bold: true, align: 'center' });
  }
  arrow(s, 4.16, 3.86, 6.12, 3.86, C.green, 2);
  rect(s, 6.2, 1.65, 2.25, 4.65, C.dark, 0.18);
  addText(s, 'HANDLER', 6.57, 2.05, 1.5, 0.28, { fontSize: 14, bold: true, color: C.green2, align: 'center' });
  for (let i = 0; i < 4; i++) {
    rect(s, 6.63, 2.72 + i * 0.67, 1.38, 0.46, i === 0 ? C.green : '33483E', 0.08);
    addText(s, `#${i + 1}`, 6.63, 2.72 + i * 0.67, 1.38, 0.46, { fontSize: 11, bold: true, color: i === 0 ? C.ink : C.green2, align: 'center' });
  }
  addText(s, '一次只处理一个', 6.45, 5.72, 1.75, 0.25, { fontSize: 10.5, color: C.muted, align: 'center' });
  addCard(s, 8.9, 1.65, 3.55, 1.23, '优势', 'Message + replyTo，低频命令很好用。', C.green, { titleSize: 15, bodySize: 11 });
  addCard(s, 8.9, 3.1, 3.55, 1.23, '瓶颈', '单 Handler 排队，高并发会转化为尾延迟。', C.amber, { titleSize: 15, bodySize: 11 });
  addCard(s, 8.9, 4.55, 3.55, 1.23, '适合', '状态机、控制命令、天然需要串行化的业务。', C.blue, { titleSize: 15, bodySize: 11 });
  addFooter(s, 8); addNotes(s, '1.5 分钟', 'Messenger 不一定慢，真正的特征是串行消息语义；这既能简化状态，也会限制吞吐。');
}

// 09
function slide09Provider() {
  const s = pptx.addSlide(); s.background = { color: C.paper }; addHeader(s, 'DATA IPC', 'ContentProvider：把 IPC 包装成“数据访问”', 9);
  const flow = [['ContentResolver', C.green], ['content:// URI', C.blue], ['Provider', C.purple], ['Cursor / Bundle', C.amber]];
  flow.forEach((f, i) => {
    const x = 0.9 + i * 3.05; node(s, x, 2.0, 2.35, 1.25, f[0], ['调用入口','资源定位','远程实现','结果容器'][i], C.white, f[1]);
    if (i < 3) arrow(s, x + 2.35, 2.62, x + 3.0, 2.62, '8EA49A', 1.5);
  });
  addText(s, 'query / insert / update / delete / call', 2.52, 3.67, 8.25, 0.44, { fontFace: CODE, fontSize: 17, bold: true, color: '315D48', align: 'center' });
  const items = [
    ['系统集成强', '权限、URI、观察者模型成熟', C.green], ['抽象有成本', 'Cursor、字段转换、两步 echo', C.amber], ['语义要匹配', '数据集合很好，细粒度 RPC 很拧巴', C.red]
  ];
  items.forEach((it, i) => addCard(s, 0.9 + i * 4.12, 4.55, 3.72, 1.38, it[0], it[1], it[2], { titleSize: 15, bodySize: 11 }));
  pill(s, '数据访问能力 > 单次调用极致性能', 4.55, 6.22, 4.22, 'E8F8EF', '267A4B', 12);
  addFooter(s, 9); addNotes(s, '1.5 分钟', 'ContentProvider 的优势是标准化数据访问和系统集成，不应仅用一次 RPC 延迟评价它。', 'benchmark 中 insert+query 是两次操作。');
}

// 10
function slide10Broadcast() {
  const s = pptx.addSlide(); s.background = { color: C.ink }; addHeader(s, 'EVENT IPC', 'Broadcast：擅长“告诉大家”，不擅长“问一个答案”', 10, true);
  circle(s, 1.0, 2.55, 1.18, C.green, 'TX', C.ink, 20);
  addText(s, 'Sender', 0.82, 3.88, 1.55, 0.28, { fontSize: 14, bold: true, color: C.white, align: 'center' });
  circle(s, 5.75, 2.37, 1.55, C.amber, 'AMS', C.ink, 22);
  const receivers = [['R1', 10.1, 1.5], ['R2', 10.1, 3.0], ['R3', 10.1, 4.5]];
  arrow(s, 2.18, 3.14, 5.75, 3.14, C.green, 2.5);
  receivers.forEach((r) => { arrow(s, 7.3, 3.14, r[1], r[2] + 0.44, '7B998B', 1.6); circle(s, r[1], r[2], 0.88, '2A3A33', r[0], C.green2, 14); });
  addText(s, '系统分发', 5.45, 4.15, 2.15, 0.28, { fontSize: 12.5, bold: true, color: C.amber, align: 'center' });
  addCard(s, 0.92, 5.62, 3.6, 0.95, '强项', '一对多、松耦合事件通知', C.green, { fill: C.panel, line: '33483E', titleColor: C.white, bodyColor: C.muted, titleSize: 13, bodySize: 10.5 });
  addCard(s, 4.86, 5.62, 3.6, 0.95, '成本', 'AMS 路径、调度波动、后台限制', C.amber, { fill: C.panel, line: '33483E', titleColor: C.white, bodyColor: C.muted, titleSize: 13, bodySize: 10.5 });
  addCard(s, 8.8, 5.62, 3.6, 0.95, '误区', '双广播“往返”不等价于 RPC', C.red, { fill: C.panel, line: '33483E', titleColor: C.white, bodyColor: C.muted, titleSize: 13, bodySize: 10.5 });
  addFooter(s, 10, true); addNotes(s, '1.5 分钟', 'Broadcast 的核心是事件分发。拿两次广播拼成请求响应可以测，但不能与原生 RPC 语义混为一谈。');
}

// 11
function slide11Socket() {
  const s = pptx.addSlide(); s.background = { color: C.paper }; addHeader(s, 'STREAM IPC', 'Socket：自由度最高，也意味着责任最多', 11);
  const layers = [
    ['业务消息', '你定义语义', C.green], ['协议帧', '长度 / 版本 / 校验', C.blue], ['连接', '心跳 / 重连 / 超时', C.amber], ['Socket', 'UDS / TCP', C.red]
  ];
  layers.forEach((l, i) => {
    const y = 1.7 + i * 1.1; rect(s, 0.9 + i * 0.28, y, 5.15 - i * 0.56, 0.78, l[2], 0.12);
    addText(s, l[0], 1.15 + i * 0.28, y + 0.14, 1.65, 0.25, { fontSize: 14, bold: true, color: C.ink });
    addText(s, l[1], 3.0, y + 0.15, 2.35 - i * 0.2, 0.25, { fontSize: 11, color: C.ink, align: 'right' });
  });
  addCard(s, 6.75, 1.7, 5.55, 1.15, '何时值得', '跨平台、持续流式传输、非 Android 对端、自定义网络协议。', C.green, { titleSize: 16, bodySize: 11.5 });
  const debt = [['粘包 / 拆包', C.blue], ['鉴权 / 加密', C.purple], ['心跳 / 重连', C.amber], ['线程 / 背压', C.red], ['协议演进', C.green]];
  debt.forEach((d, i) => pill(s, d[0], 6.78 + (i % 2) * 2.78, 3.25 + Math.floor(i / 2) * 0.72, 2.45, d[1], C.ink, 11));
  addText(s, '自由意味着：平台替你做的事，都要自己实现', 6.55, 5.72, 5.85, 0.48, { fontSize: 16.5, bold: true, color: C.text, align: 'center' });
  addFooter(s, 11); addNotes(s, '1.5 分钟', 'Socket 不是 Android IPC 的低级替代品，而是当跨平台或流式需求成立时的一套不同责任模型。');
}

// 12
function slide12SharedMemory() {
  const s = pptx.addSlide(); s.background = { color: C.cream }; addHeader(s, 'LARGE PAYLOAD', 'SharedMemory：省复制，换来同步与生命周期', 12);
  rect(s, 0.82, 1.65, 3.2, 4.6, C.white, 0.18, 'DCE2DE');
  rect(s, 9.3, 1.65, 3.2, 4.6, C.white, 0.18, 'DCE2DE');
  addText(s, 'PROCESS A', 1.18, 2.02, 1.6, 0.24, { fontSize: 11, bold: true, color: '50665C', charSpacing: 1 });
  addText(s, 'PROCESS B', 9.66, 2.02, 1.6, 0.24, { fontSize: 11, bold: true, color: '50665C', charSpacing: 1 });
  node(s, 1.28, 2.68, 2.3, 1.05, 'Writer', '写入共享页', 'F4FBF7', C.green);
  node(s, 9.76, 2.68, 2.3, 1.05, 'Reader', '读取共享页', 'F4FBF7', C.green);
  rect(s, 4.66, 2.15, 4.0, 3.45, C.dark, 0.2, '2F4A3E');
  addText(s, 'SHARED PAGES', 5.2, 2.52, 2.92, 0.3, { fontSize: 15, bold: true, color: C.green2, align: 'center', charSpacing: 1 });
  for (let i = 0; i < 4; i++) rect(s, 5.18 + i * 0.67, 3.16, 0.52, 1.15, i % 2 ? '4B665A' : C.green, 0.06);
  addText(s, 'FD · ownership · fence', 5.08, 4.72, 3.15, 0.28, { fontFace: CODE, fontSize: 11.5, color: C.muted, align: 'center' });
  arrow(s, 3.58, 3.2, 4.66, 3.2, C.green, 2); arrow(s, 8.66, 3.2, 9.76, 3.2, C.green, 2);
  pill(s, '视频帧 / 音频 / 大数组', 1.25, 5.58, 2.32, 'EAF8F0', '267A4B');
  pill(s, '小控制信号：通常不值得', 9.62, 5.58, 2.6, 'FFF1DC', '8C5712');
  addText(s, 'Falcon 已移除 SharedMemory 混合传输：真实场景没有高频大 payload', 3.0, 6.48, 7.35, 0.3, { fontSize: 13.5, bold: true, color: C.red, align: 'center' });
  addFooter(s, 12); addNotes(s, '1.7 分钟', '共享内存只有在数据规模证明其必要性时才值得；Falcon 的真实负载以小控制数据为主，因此主动删除了这条复杂路径。');
}

// 13
function slide13Matrix() {
  const s = pptx.addSlide(); s.background = { color: C.paper }; addHeader(s, 'SELECTION', '六种原生方式 + Falcon：一张表做初筛', 13);
  const cols = ['方案','核心语义','延迟倾向','大数据','一对多','工程成本','最佳场景'];
  const rows = [
    ['AIDL','RPC','低','有限','否','中','稳定接口'], ['Messenger','消息','中','不宜','否','低','串行命令'],
    ['Provider','数据','中高','有限','否','中','CRUD / 数据共享'], ['Broadcast','事件','高波动','不宜','是','低','系统通知'],
    ['Socket','流 / 协议','可控','支持','可扩展','高','跨平台 / 流'], ['SharedMemory','共享页','低','强','否','很高','高频大块数据'],
    ['Falcon','治理型 RPC','接近 Binder','有限','事件流','低→中','多模块 / 多 App']
  ];
  const x0 = 0.58, y0 = 1.42; const ws = [1.35,1.45,1.25,0.95,0.95,1.25,3.35];
  let xx=x0; cols.forEach((c,i)=>{rect(s,xx,y0,ws[i],0.58,C.dark,0);addText(s,c,xx+0.05,y0,ws[i]-0.1,0.58,{fontSize:11,bold:true,color:C.white,align:'center'});xx+=ws[i]+0.03;});
  rows.forEach((r,ri)=>{let x=x0; const y=y0+0.62+ri*0.64; r.forEach((v,ci)=>{const fill=ri===6?'E5F7EC':ri%2?'F3F5F3':C.white;rect(s,x,y,ws[ci],0.6,fill,0,ri===6?'8DC9A7':'E0E4E1');addText(s,v,x+0.05,y,ws[ci]-0.1,0.6,{fontSize:ci===6?10.2:10.7,bold:ci===0||ri===6,color:ri===6?'1C6E40':C.text,align:ci===6?'left':'center'});x+=ws[ci]+0.03;});});
  rect(s, 0.92, 6.47, 11.55, 0.42, C.dark, 0.14);
  addText(s, '选型顺序：语义 → 数据规模 → 治理成本   |   Falcon 是 Binder 上的工程化层', 1.1, 6.47, 11.18, 0.42, { fontSize: 11.5, bold: true, color: C.green2, align: 'center' });
  addFooter(s, 13); addNotes(s, '2 分钟', '这张表用来初筛，不是绝对排名。Falcon 仍走 Binder，增加的是生成代码、连接和治理。');
}

// 14
function slide14Benchmark() {
  const s = pptx.addSlide(); s.background = { color: C.ink }; addHeader(s, 'BENCHMARK', '公平测试：统一“远程进程 + 预热 + 稳态往返”', 14, true);
  rect(s, 0.72, 1.55, 3.05, 4.7, C.panel, 0.18, '33483E'); addText(s, 'MAIN PROCESS', 1.02, 1.88, 1.85, 0.24, { fontSize: 11, bold: true, color: C.green2, charSpacing: 1 });
  node(s, 1.15, 2.55, 2.18, 1.1, 'BenchmarkRunner', 'warmup + iterations', '26372F', C.green, true);
  node(s, 1.15, 4.25, 2.18, 1.1, '统计', 'avg · p50 · p95 · p99', '26372F', C.amber, true);
  rect(s, 9.62, 1.55, 3.0, 4.7, C.panel, 0.18, '33483E'); addText(s, 'REMOTE PROCESS', 9.92, 1.88, 2.0, 0.24, { fontSize: 11, bold: true, color: C.green2, charSpacing: 1 });
  node(s, 10.05, 2.55, 2.12, 1.1, '服务实现', '真实跨进程 echo', '26372F', C.green, true);
  node(s, 10.05, 4.25, 2.12, 1.1, '同轮对比', '同设备 · 同 payload', '26372F', C.amber, true);
  const methods = [['AIDL',C.green],['Falcon',C.green2],['Messenger',C.blue],['Provider',C.purple],['Broadcast',C.amber]];
  methods.forEach((m,i)=>{pill(s,m[0],4.25,1.65+i*0.82,1.35,m[1],C.ink,11);arrow(s,5.72,1.82+i*0.82,9.45,1.82+i*0.82,'5E786C',1.3);});
  addText(s, '连接建立在计时前', 4.23, 5.95, 2.3, 0.28, { fontSize: 11.5, bold: true, color: C.white });
  addText(s, 'Broadcast 是人为双程，语义不对等', 7.16, 5.95, 2.15, 0.42, { fontSize: 10.5, color: C.amber, align: 'right' });
  addText(s, '没有设备实测，就不画“精确柱状图”', 3.86, 6.58, 5.6, 0.32, { fontSize: 18, bold: true, color: C.green2, align: 'center' });
  addFooter(s, 14, true); addNotes(s, '2 分钟', '项目 benchmark 比较五种机制；只比较同设备同一轮的稳态往返，并输出尾延迟。', 'Broadcast 与 Provider 的操作语义不完全等价。');
}

// 15
function slide15Optimization() {
  const s = pptx.addSlide(); s.background = { color: C.paper }; addHeader(s, 'OPTIMIZE', '优化优先级：先消除工作，再加速工作', 15);
  const levels = [
    ['1', '减少调用次数', '批量、缓存、事件合并', C.green, 10.7], ['2', '控制 payload', '只传必要字段，避免大对象', C.green2, 9.4],
    ['3', '避免排队', '不阻塞主线程，匹配线程模型', C.blue, 8.1], ['4', '降低编解码', 'Parcelable / 类型化 Bundle', C.purple, 6.8],
    ['5', '复杂传输', 'SharedMemory / 自定义协议', C.amber, 5.5]
  ];
  levels.forEach((l,i)=>{const x=(13.333-l[4])/2;const y=1.55+i*0.92;rect(s,x,y,l[4],0.68,l[3],0.08);circle(s,x+0.15,y+0.14,0.4,C.ink,l[0],C.white,11);addText(s,l[1],x+0.72,y+0.12,2.25,0.25,{fontSize:14,bold:true,color:C.ink});addText(s,l[2],x+3.05,y+0.13,l[4]-3.3,0.24,{fontSize:10.5,color:C.ink,align:'right'});});
  addText(s, '收益更大', 11.62, 1.6, 1.0, 0.25, { fontSize: 10.5, bold: true, color: '27784A', align: 'center' });
  arrow(s, 12.18, 1.98, 12.18, 5.82, '668176', 1.4);
  addText(s, '复杂度更高', 11.55, 5.88, 1.12, 0.25, { fontSize: 10.5, bold: true, color: '9B651E', align: 'center' });
  pill(s, '原则：没有测量证据，不升级传输复杂度', 4.2, 6.42, 4.95, C.dark, C.green2, 13);
  addFooter(s, 15); addNotes(s, '1.5 分钟', '最高收益通常来自减少跨进程工作量；共享内存和自定义协议应该是最后一层选择。');
}

// 16
function slide16WhyFalcon() {
  const s = pptx.addSlide(); s.background = { color: C.cream }; addHeader(s, 'WHY FALCON', 'Binder 解决“能通信”，Falcon 解决“可规模化”', 16);
  rect(s, 0.75, 1.6, 4.25, 4.9, C.dark, 0.2);
  addText(s, 'BINDER / AIDL', 1.15, 2.0, 3.45, 0.3, { fontSize: 15, bold: true, color: C.green2, align: 'center', charSpacing: 1 });
  const base = ['传输','Stub / Proxy','调用方 UID','进程隔离']; base.forEach((b,i)=>pill(s,b,1.28+(i%2)*1.62,2.75+Math.floor(i/2)*0.78,1.42,i===0?C.green:'33483E',i===0?C.ink:C.green2,11));
  addText(s, '平台原生基础', 1.37, 4.7, 3.0, 0.35, { fontSize: 20, bold: true, color: C.white, align: 'center' });
  addText(s, '不重新发明', 1.57, 5.38, 2.6, 0.28, { fontSize: 12, color: C.muted, align: 'center' });
  const needs = [['接口生成',C.green],['发现 / 重连',C.blue],['事件 / Flow',C.purple],['安全治理',C.amber],['监控 / 超时',C.red],['多 App 接入',C.green2]];
  needs.forEach((n,i)=>addCard(s,5.45+(i%2)*3.55,1.6+Math.floor(i/2)*1.58,3.15,1.28,n[0],['减少样板','管理 Binder 生命周期','统一异步语义','可信签名与规则','默认关闭可观测','显式互信'][i],n[1],{titleSize:14,bodySize:10.5}));
  addText(s, 'Falcon = Binder-native 调用链 + 编译期生成 + 运行时治理', 5.52, 6.45, 6.95, 0.3, { fontSize: 15.5, bold: true, color: '2B6646', align: 'center' });
  addFooter(s, 16); addNotes(s, '1.5 分钟', 'Falcon 不取代 Binder，而是把大型工程中重复出现的生成、连接、事件、安全和观测收敛成框架。');
}

// 17
function slide17DeveloperExperience() {
  const s = pptx.addSlide(); s.background = { color: C.ink }; addHeader(s, 'DEVELOPER API', '业务只看接口；跨进程细节交给生成代码', 17, true);
  rect(s, 0.72, 1.48, 7.58, 5.28, '0C1210', 0.16, '31443B');
  const code = `@IpcService\ninterface VehicleService {\n  @IpcMethod suspend fun state(): VehicleState\n  @IpcEvent fun events(): Flow<VehicleEvent>\n}\n\nFalcon.init(context) {\n  generated(AppFalconGeneratedRegistry)\n}\nfalcon.register(VehicleService::class, impl)`;
  addText(s, code, 1.08, 1.84, 6.88, 4.42, { fontFace: CODE, fontSize: 16.2, color: C.green2, valign: 'top', breakLine: true, fit: 'shrink' });
  const chips = [['接口即契约',C.green],['不写 Stub',C.blue],['Flow 原生',C.purple],['显式 Registry',C.amber]];
  chips.forEach((c,i)=>pill(s,c[0],8.82,1.75+i*0.82,2.8,c[1],C.ink,12));
  addText(s, '像本地接口一样调用', 8.8, 5.45, 3.15, 0.38, { fontSize: 23, bold: true, color: C.white, align: 'center' });
  addText(s, '但在协程 / 后台线程中尊重远程调用语义', 8.7, 6.02, 3.35, 0.52, { fontSize: 11, color: C.muted, align: 'center' });
  addFooter(s, 17, true); addNotes(s, '2 分钟', 'Falcon 的入口是接口与注解；生成 registry 显式注入，避免运行时扫描和反射。', '同步 Binder 调用不要放在主线程。');
}

// 18
function slide18Architecture() {
  const s = pptx.addSlide(); s.background = { color: C.paper }; addHeader(s, 'ARCHITECTURE', 'Falcon 架构：编译期与运行时分工明确', 18);
  const layers = [
    ['业务接口', '@IpcService · @IpcMethod · Flow', C.green], ['falcon-annotations', '纯 Kotlin 契约', '8FE6B2'],
    ['falcon-ksp', 'Proxy · Dispatcher · Registry · Schema hash', C.blue], ['falcon-core', '发现 · BinderTransport · 安全 · 监控', C.purple],
    ['Android Binder', 'IIpcHost.invoke(IpcEnvelope)', C.amber]
  ];
  layers.forEach((l,i)=>{const x=0.85+i*0.42,y=1.52+i*1.0,w=11.62-i*0.84;rect(s,x,y,w,0.76,l[2],0.1);addText(s,l[0],x+0.28,y+0.13,2.4,0.26,{fontSize:14,bold:true,color:C.ink});addText(s,l[1],x+3.0,y+0.14,w-3.3,0.25,{fontSize:11.2,color:C.ink,align:'right'});});
  addText(s, 'COMPILE TIME', 0.94, 6.38, 2.0, 0.22, { fontSize: 10, bold: true, color: '32704D', charSpacing: 1 });
  line(s, 2.55, 6.49, 3.65, 0, C.green, 2);
  addText(s, 'RUNTIME', 7.06, 6.38, 1.2, 0.22, { fontSize: 10, bold: true, color: '5E4E84', charSpacing: 1 });
  line(s, 8.15, 6.49, 3.35, 0, C.purple, 2);
  addText(s, '静态生成消除反射分发；运行时只负责连接与治理', 3.6, 6.72, 6.15, 0.28, { fontSize: 13, bold: true, color: C.text, align: 'center' });
  addFooter(s, 18); addNotes(s, '1.7 分钟', 'annotations 定义契约，KSP 生成静态调用链，core 处理 Binder 生命周期与治理。');
}

// 19
function slide19Generated() {
  const s = pptx.addSlide(); s.background = { color: C.cream }; addHeader(s, 'CODEGEN', 'KSP 生成三件套：Proxy、Dispatcher、Registry', 19);
  const cards = [
    ['Xxx_Proxy', ['实现业务接口','参数写入 Bundle','调用 transport','解码结果'], C.green],
    ['Xxx_Dispatcher', ['when(methodId)','类型化解码','调用 impl','编码结果'], C.blue],
    ['ModuleRegistry', ['proxyFactories','dispatcherFactories','schema hashes','显式注入'], C.amber]
  ];
  cards.forEach((c,i)=>{const x=0.78+i*4.18;rect(s,x,1.62,3.72,4.65,C.white,0.18,'DDE3DF',true);circle(s,x+0.25,1.92,0.48,c[2],String(i+1),C.ink,13);addText(s,c[0],x+0.88,1.92,2.5,0.38,{fontFace:CODE,fontSize:17,bold:true});c[1].forEach((b,j)=>{circle(s,x+0.35,2.85+j*0.68,0.22,'E3F5EA','',C.ink);addText(s,b,x+0.75,2.75+j*0.68,2.5,0.42,{fontSize:12.2,bold:j===0});});pill(s,i===0?'CLIENT':i===1?'SERVER':'MODULE',x+1.06,5.72,1.58,c[2],C.ink,10);});
  addText(s, '不支持的 wire type → 编译期失败', 4.52, 6.53, 4.3, 0.32, { fontSize: 17, bold: true, color: C.red, align: 'center' });
  addFooter(s, 19); addNotes(s, '2 分钟', '三件套分别负责客户端、服务端和模块聚合；类型不支持或 methodId 冲突都在编译期失败。');
}

// 20
function slide20RequestPath() {
  const s = pptx.addSlide(); s.background = { color: C.ink }; addHeader(s, 'REQUEST PATH', '一次 Falcon 请求—响应：真正走过的 10 个节点', 20, true);
  const steps = [
    ['业务方法','state()'],['Proxy','put Bundle'],['Envelope','methodId'],['Transport','invoke'],['IIpcHost','Binder'],
    ['HostService','入口'],['Router','治理切面'],['Registry','找 Dispatcher'],['Dispatcher','when(id)'],['Impl','真实业务']
  ];
  steps.forEach((st,i)=>{const col=i%5,row=Math.floor(i/5);const x=0.55+col*2.55,y=1.6+row*2.42;node(s,x,y,2.1,1.15,st[0],st[1],C.panel,i===6?C.amber:C.green,true);if(col<4)arrow(s,x+2.1,y+0.58,x+2.45,y+0.58,i===6?C.amber:'5D786B',1.4);});
  for(let i=0;i<5;i++) line(s,1.6+i*2.55,2.8,0,1.05,'40564C',1,'dash');
  pill(s, 'CLIENT', 0.62, 1.34, 1.0, '264238', C.green2, 9.5);
  pill(s, 'SERVER', 0.62, 3.76, 1.0, '4B3D24', C.amber, 9.5);
  arrow(s, 11.9, 5.35, 1.08, 5.35, C.green2, 1.7);
  addText(s, 'Bundle 响应沿原路返回', 4.6, 5.1, 4.15, 0.42, { fontSize: 16, bold: true, color: C.white, align: 'center' });
  pill(s, '安全', 3.35, 6.2, 1.05, C.amber, C.ink); pill(s, '限流', 4.58, 6.2, 1.05, C.amber, C.ink); pill(s, '监控', 5.8, 6.2, 1.05, C.amber, C.ink); pill(s, '超时', 7.02, 6.2, 1.05, C.amber, C.ink);
  addFooter(s, 20, true); addNotes(s, '2 分钟', '从 Proxy 到 Dispatcher 全程无业务方法反射；治理切面位于服务端入口，响应仍是类型化 Bundle。');
}

// 21
function slide21MethodId() {
  const s = pptx.addSlide(); s.background = { color: C.paper }; addHeader(s, 'WIRE CONTRACT', '稳定 methodId + schema hash：匹配“方法”也匹配“数据布局”', 21);
  rect(s, 0.8, 1.55, 5.65, 2.12, C.dark, 0.16);
  addText(s, 'FNV-1a32', 1.15, 1.9, 1.55, 0.32, { fontFace: CODE, fontSize: 18, bold: true, color: C.green2 });
  addText(s, '"state(Int,String)"', 1.15, 2.48, 3.45, 0.36, { fontFace: CODE, fontSize: 17, color: C.white });
  arrow(s, 4.55, 2.67, 5.25, 2.67, C.green, 1.8);
  pill(s, '0x72A9…', 5.26, 2.48, 0.88, C.green, C.ink, 10);
  addText(s, '重载安全 · 改名即 breaking change', 1.15, 3.08, 4.85, 0.27, { fontSize: 11.5, color: C.muted });
  rect(s, 6.83, 1.55, 5.7, 2.12, 'EAF2FF', 0.16, 'CBD9EE');
  addText(s, 'SCHEMA HASH', 7.18, 1.9, 2.25, 0.3, { fontFace: CODE, fontSize: 17, bold: true, color: '315D94' });
  addText(s, '返回类型 + Parcelable 字段布局', 7.18, 2.48, 4.75, 0.35, { fontSize: 16, bold: true, color: C.text });
  addText(s, '发现阶段比对一次，避免静默错读', 7.18, 3.08, 4.65, 0.27, { fontSize: 11.5, color: '566B84' });
  const rules = [['IpcReply 参数', '不参与 methodId', C.amber], ['哈希碰撞', 'KSP 编译失败', C.red], ['返回类型变化', 'schema hash 捕获', C.blue], ['Bundle 编码', '基础类型 / Parcelable / enum', C.green]];
  rules.forEach((r,i)=>addCard(s,0.82+(i%2)*6.02,4.15+Math.floor(i/2)*1.12,5.58,0.86,r[0],r[1],r[2],{titleSize:13,bodySize:10.5}));
  addFooter(s, 21); addNotes(s, '2 分钟', 'methodId 解决静态分发和重载；schema hash 补足返回类型与 Parcelable 字段布局变化。');
}

// 22
function slide22Async() {
  const s = pptx.addSlide(); s.background = { color: C.cream }; addHeader(s, 'ASYNC IPC', 'Event / Stream / Callback：统一走生成代码与 Binder 回调', 22);
  rect(s, 0.78, 1.55, 7.42, 4.9, C.white, 0.18, 'DCE2DE');
  addText(s, 'EVENT / STREAM', 1.12, 1.9, 2.15, 0.27, { fontSize: 13, bold: true, color: '33704F', charSpacing: 1 });
  const ev = [['Proxy','typedRemoteFlow'],['subscribe','eventKey'],['Collector','ref-count'],['Dispatcher','eventFlow'],['Callback','Bundle']];
  ev.forEach((e,i)=>{const x=1.0+i*1.38;rect(s,x,2.55,1.18,1.12,i===2?'FFF3DE':'F5FAF7',0.12,'DDE3DF');addText(s,e[0],x+0.06,2.72,1.06,0.24,{fontSize:10.3,bold:true,align:'center'});addText(s,e[1],x+0.06,3.12,1.06,0.24,{fontSize:8.2,color:'617069',align:'center'});if(i<4)arrow(s,x+1.18,3.11,x+1.34,3.11,'81988D',1.2);});
  line(s, 3.02, 4.25, 3.55, 0, C.amber, 3);
  circle(s, 2.75, 4.08, 0.34, C.amber, '1', C.ink, 10); circle(s, 6.57, 4.08, 0.34, C.green, '0', C.ink, 10);
  addText(s, '首订阅启动采集', 1.08, 4.72, 2.7, 0.28, { fontSize: 12.5, bold: true, color: '805315', align: 'center' });
  addText(s, '末订阅取消 Job', 5.45, 4.72, 2.25, 0.28, { fontSize: 12.5, bold: true, color: '27784A', align: 'center' });
  pill(s, '多个订阅者共享同一服务端 Flow', 2.48, 5.48, 4.05, 'EAF8F0', '27784A', 11.5);
  rect(s, 8.65, 1.55, 3.88, 4.9, C.dark, 0.18);
  addText(s, 'CALLBACK', 9.02, 1.9, 1.72, 0.27, { fontSize: 13, bold: true, color: C.green2, charSpacing: 1 });
  const cb = [['Proxy',2.55],['reply Binder',3.45],['Dispatcher',4.35],['IpcReply',5.25]];
  cb.forEach((c,i)=>{pill(s,c[0],9.18,c[1],2.8,i===1?C.amber:'31453B',i===1?C.ink:C.green2,11);if(i<3)arrow(s,10.58,c[1]+0.35,10.58,c[1]+0.78,'657F73',1.2);});
  addFooter(s, 22); addNotes(s, '2 分钟', '事件和回调都由生成代码处理。EventCollector 用引用计数避免没有订阅者时持续收集服务端 Flow。');
}

// 23
function slide23Discovery() {
  const s = pptx.addSlide(); s.background = { color: C.ink }; addHeader(s, 'LIFECYCLE', '发现与重连：连接不是一次动作，而是一个状态机', 23, true);
  const states = [['发现','Provider / URI',C.green],['取 Binder','Bundle.putBinder',C.blue],['连接','linkToDeath',C.green2],['断开','binderDied',C.red],['重连','指数退避',C.amber]];
  states.forEach((st,i)=>{const x=0.65+i*2.52;circle(s,x+0.74,2.05,0.58,st[2],String(i+1),C.ink,13);node(s,x,2.86,2.08,1.25,st[0],st[1],C.panel,st[2],true);if(i<4)arrow(s,x+2.08,3.48,x+2.42,3.48,'617A6F',1.4);});
  const times = [['500 ms',1.15],['1 s',3.38],['2 s',5.55],['4 s',7.72],['… 30 s max',9.88]];
  line(s, 1.2, 5.28, 10.82, 0, '6B8378', 2);
  times.forEach((t,i)=>{circle(s,t[1],5.12,0.34,i===4?C.amber:C.green,'',C.ink);addText(s,t[0],t[1]-0.42,5.58,1.18,0.25,{fontFace:CODE,fontSize:10.5,bold:true,color:i===4?C.amber:C.green2,align:'center'});});
  addText(s, '每个 peer 独立退避 · 成功后复位 · 死亡回调防重复', 2.55, 6.25, 8.25, 0.3, { fontSize: 15, bold: true, color: C.white, align: 'center' });
  addText(s, '发现成本是生命周期成本，不计入稳态单次 RPC', 3.25, 6.68, 6.85, 0.27, { fontSize: 11.5, color: C.muted, align: 'center' });
  addFooter(s, 23, true); addNotes(s, '1.8 分钟', 'Provider 负责发现并返回 Binder；PeerManager 监听死亡，每个 peer 使用独立指数退避恢复。');
}

// 24
function slide24Governance() {
  const s = pptx.addSlide(); s.background = { color: C.paper }; addHeader(s, 'GOVERNANCE', '安全、稳定、可观测：都在调用入口形成闭环', 24);
  circle(s, 5.22, 2.15, 2.9, C.dark, 'ROUTER', C.green2, 24);
  const items = [
    ['签名信任','自身证书 + trustedSignatures',0.72,1.55,C.green],['访问规则','调用方进程 / 包名',0.72,4.25,C.blue],
    ['限流','每 PID 滑动窗口',9.35,1.55,C.amber],['超时','调用边界与错误码',9.35,4.25,C.red],
    ['监控','NONE / BASIC / DETAILED / FULL',4.48,5.68,C.purple]
  ];
  items.forEach((it,i)=>addCard(s,it[2],it[3],3.25,i===4?0.85:1.25,it[0],it[1],it[4],{titleSize:14,bodySize:10.2}));
  line(s, 3.97, 2.17, 1.45, 0.76, 'A1B0A9', 1.2);
  line(s, 9.35, 2.17, -1.45, 0.76, 'A1B0A9', 1.2);
  line(s, 3.97, 4.87, 1.45, -0.76, 'A1B0A9', 1.2);
  line(s, 9.35, 4.87, -1.45, -0.76, 'A1B0A9', 1.2);
  line(s, 6.66, 5.68, 0, -0.65, 'A1B0A9', 1.2);
  pill(s, '默认 MonitorLevel.NONE', 5.1, 6.72, 3.15, 'EEE9FF', '604A98', 11.5);
  addFooter(s, 24); addNotes(s, '1.8 分钟', '签名、访问规则、限流、超时和监控形成统一治理入口；监控默认关闭以避免生产开销。', '可信签名按 UID 下所有包的证书集合进行校验。');
}

// 25
function slide25Boundaries() {
  const s = pptx.addSlide(); s.background = { color: C.cream }; addHeader(s, 'BOUNDARIES', 'Falcon 的价值与边界：知道什么时候不用它', 25);
  rect(s, 0.8, 1.5, 5.75, 4.95, 'E9F8EF', 0.18, 'B9DCC8');
  addText(s, '适合', 1.15, 1.88, 1.0, 0.42, { fontSize: 25, bold: true, color: '207044' });
  const yes = ['多模块、多进程、多 App','小到中等 payload 的 Binder RPC','需要事件 / Flow / Callback','需要互信、限流、重连和监控'];
  yes.forEach((v,i)=>{circle(s,1.18,2.75+i*0.72,0.34,C.green,'✓',C.ink,11);addText(s,v,1.72,2.68+i*0.72,4.2,0.46,{fontSize:13,bold:i===0});});
  rect(s, 6.82, 1.5, 5.72, 4.95, 'FFF1E9', 0.18, 'E6C8B9');
  addText(s, '不适合', 7.18, 1.88, 1.4, 0.42, { fontSize: 25, bold: true, color: 'A14B35' });
  const no = ['视频帧 / 高频大数据','非 Android 跨平台协议','单个简单 AIDL 已足够','试图突破 Binder 事务上限'];
  no.forEach((v,i)=>{circle(s,7.2,2.75+i*0.72,0.34,C.red,'×',C.white,12);addText(s,v,7.74,2.68+i*0.72,4.15,0.46,{fontSize:13,bold:i===0});});
  rect(s, 2.25, 6.02, 8.85, 0.62, C.dark, 0.16);
  addText(s, 'Falcon 优化的是工程复杂度，而不是 Binder 的物理上限', 2.25, 6.02, 8.85, 0.62, { fontSize: 17, bold: true, color: C.green2, align: 'center' });
  addFooter(s, 25); addNotes(s, '1.5 分钟', 'Falcon 最适合治理复杂的 Binder 系统；高频大数据、跨平台协议或极小项目应选择更直接的方案。');
}

// 26
function slide26Summary() {
  const s = pptx.addSlide(); s.background = { color: C.ink };
  addText(s, 'THREE TAKEAWAYS', 0.75, 0.62, 3.0, 0.25, { fontSize: 11, bold: true, color: C.green2, charSpacing: 1.6 });
  addText(s, '把 IPC 做对，比把 IPC 做快更重要', 0.75, 1.15, 8.9, 0.72, { fontSize: 34, bold: true, color: C.white });
  const take = [
    ['01','先选语义','RPC、消息、数据、事件、流与共享内存不是同一道题',C.green],
    ['02','再控成本','减少调用与 payload，关注排队和 P99',C.blue],
    ['03','最后治理','用生成代码、连接管理与安全能力降低系统复杂度',C.amber]
  ];
  take.forEach((t,i)=>{const y=2.25+i*1.14;circle(s,0.82,y,0.58,t[3],t[0],C.ink,10);addText(s,t[1],1.65,y-0.02,2.1,0.35,{fontSize:19,bold:true,color:C.white});addText(s,t[2],4.15,y-0.01,7.65,0.38,{fontSize:13,color:C.muted});line(s,1.65,y+0.72,10.85,0,'34483F',0.8);});
  rect(s, 9.67, 0.85, 2.55, 1.05, C.green, 0.18);
  addText(s, 'Q & A', 9.67, 0.85, 2.55, 1.05, { fontSize: 29, bold: true, color: C.ink, align: 'center' });
  addText(s, 'Falcon = Binder-native + KSP + Governance', 0.82, 6.38, 6.2, 0.35, { fontFace: CODE, fontSize: 15, bold: true, color: C.green2 });
  addText(s, '谢谢', 10.68, 6.28, 1.55, 0.45, { fontSize: 22, bold: true, color: C.white, align: 'right' });
  addNotes(s, '1 分钟 + Q&A', '收束三点：语义优先、成本可测、治理规模化。随后进入问答。');
}

async function buildDeck() {
  [slide01Title, slide02Scenario, slide03CallJourney, slide04Landscape, slide05Binder,
   slide06CostModel, slide07Aidl, slide08Messenger, slide09Provider, slide10Broadcast,
   slide11Socket, slide12SharedMemory, slide13Matrix, slide14Benchmark, slide15Optimization,
   slide16WhyFalcon, slide17DeveloperExperience, slide18Architecture, slide19Generated,
   slide20RequestPath, slide21MethodId, slide22Async, slide23Discovery, slide24Governance,
   slide25Boundaries, slide26Summary].forEach(fn => fn());
  const out = 'docs/presentations/falcon-android-ipc-sharing.pptx';
  await pptx.writeFile({ fileName: out });
  console.log(`Wrote ${out} with ${pptx._slides.length} slides`);
}

buildDeck().catch(err => { console.error(err); process.exit(1); });
