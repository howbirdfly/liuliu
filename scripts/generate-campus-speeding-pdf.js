import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import PDFDocument from 'pdfkit';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const outputPath = path.resolve(__dirname, '../docs/校园超速监控系统_子系统划分与集成通信图.pdf');
const fontPath = 'C:\\Windows\\Fonts\\simhei.ttf';

const tableHeaders = ['子系统', '子系统功能', '划分依据'];
const tableRows = [
  ['车速检测子系统', '负责采集车辆车牌号、检测时间和车速，并把检测记录发送到后台。', '按前端采集职责划分。'],
  ['速度显示子系统', '负责在路旁显示车辆实时车速和校园安全车速上限。', '按实时显示功能划分。'],
  ['检测记录管理子系统', '负责接收、保存所有车辆检测记录，并为查询和统计提供数据。', '围绕核心数据“检测记录”划分。'],
  ['超速处理子系统', '负责根据限速值判断是否超速，并生成超速事件。', '把业务规则单独拆分，便于后续调整。'],
  ['车辆/人员信息子系统', '负责关联校职工车辆、外来车辆报备信息，找到驾驶员和校内负责人。', '需要对接外部信息，适合独立划分。'],
  ['短信通知子系统', '负责发送超速警示、月度提醒等短信消息。', '通知能力可被多个场景复用。'],
  ['统计与黑名单子系统', '负责月末和季末统计超速次数，并对达到阈值的车辆执行黑名单处理。', '按定时统计和后续控制流程划分。'],
  ['报表查询子系统', '负责生成月报、季报，并支持管理员按车辆或按路段查询记录。', '按后台查询和报表功能划分。'],
  ['用户权限管理子系统', '负责管理员账户、权限控制、权限转让和个人信息维护。', '权限控制属于基础支撑能力。'],
  ['系统配置子系统', '负责维护安全车速上限、月度阈值 M、季度阈值 N 等参数。', '把易变参数单独抽离，便于维护。']
];

const diagramBoxes = [
  { id: 'detect', label: '车速检测子系统', x: 40, y: 90, w: 130, h: 42 },
  { id: 'record', label: '检测记录管理子系统', x: 245, y: 90, w: 145, h: 42 },
  { id: 'process', label: '超速处理子系统', x: 470, y: 90, w: 130, h: 42 },
  { id: 'sms', label: '短信通知子系统', x: 690, y: 90, w: 120, h: 42 },

  { id: 'display', label: '速度显示子系统', x: 40, y: 205, w: 130, h: 42 },
  { id: 'stats', label: '统计与黑名单子系统', x: 245, y: 205, w: 145, h: 42 },
  { id: 'relation', label: '车辆/人员信息子系统', x: 470, y: 205, w: 130, h: 42 },
  { id: 'config', label: '系统配置子系统', x: 690, y: 205, w: 120, h: 42 },

  { id: 'user', label: '用户权限管理子系统', x: 40, y: 320, w: 130, h: 42 },
  { id: 'report', label: '报表查询子系统', x: 245, y: 320, w: 145, h: 42 }
];

const doc = new PDFDocument({
  size: 'A4',
  margin: 50
});

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
doc.pipe(fs.createWriteStream(outputPath));
doc.registerFont('CN', fontPath);
doc.font('CN');

function ensureSpace(heightNeeded) {
  const bottom = doc.page.height - doc.page.margins.bottom;
  if (doc.y + heightNeeded <= bottom) {
    return;
  }
  doc.addPage();
  doc.font('CN');
}

function writeTitle(text) {
  doc.x = doc.page.margins.left;
  doc.fontSize(15).text(text, { align: 'center' });
  doc.moveDown(1);
}

function writeSection(text) {
  ensureSpace(30);
  doc.x = doc.page.margins.left;
  doc.fontSize(12.5).text(text);
  doc.moveDown(0.5);
}

function writeParagraph(text) {
  ensureSpace(60);
  doc.x = doc.page.margins.left;
  doc.fontSize(10.8).text(text, {
    lineGap: 3,
    align: 'left'
  });
  doc.moveDown(0.5);
}

function rowHeight(values, widths, padding, fontSize) {
  doc.fontSize(fontSize);
  return Math.max(
    ...values.map((value, index) =>
      doc.heightOfString(String(value), {
        width: widths[index] - padding * 2,
        lineGap: 2
      })
    )
  ) + 14;
}

function drawRow(values, widths, isHeader = false) {
  const x0 = doc.page.margins.left;
  const padding = 6;
  const fontSize = 10.2;
  const h = rowHeight(values, widths, padding, fontSize);
  ensureSpace(h + 2);
  const rowY = doc.y;

  let x = x0;
  values.forEach((value, index) => {
    doc.rect(x, rowY, widths[index], h).stroke();
    doc.fontSize(fontSize).text(String(value), x + padding, rowY + 5, {
      width: widths[index] - padding * 2,
      lineGap: 2,
      align: isHeader ? 'center' : 'left'
    });
    x += widths[index];
  });
  doc.y = rowY + h;
  doc.x = doc.page.margins.left;
}

function drawTable(headers, rows) {
  const widths = [120, 220, 135];
  doc.x = doc.page.margins.left;
  drawRow(headers, widths, true);
  rows.forEach((row) => {
    const h = rowHeight(row, widths, 6, 10.2);
    const bottom = doc.page.height - doc.page.margins.bottom;
    if (doc.y + h > bottom) {
      doc.addPage();
      doc.font('CN');
      drawRow(headers, widths, true);
    }
    drawRow(row, widths, false);
  });
  doc.x = doc.page.margins.left;
  doc.moveDown(0.6);
}

function getBox(id) {
  return diagramBoxes.find((item) => item.id === id);
}

function drawBox(box) {
  doc.rect(box.x, box.y, box.w, box.h).stroke();
  doc.fontSize(10).text(box.label, box.x + 6, box.y + 13, {
    width: box.w - 12,
    align: 'center'
  });
}

function drawHorizontalArrow(from, to, label, offsetY = 0) {
  const y = from.y + from.h / 2 + offsetY;
  const startX = from.x + from.w;
  const endX = to.x;
  doc.moveTo(startX, y).lineTo(endX - 8, y).stroke();
  doc.moveTo(endX - 8, y - 4).lineTo(endX, y).lineTo(endX - 8, y + 4).stroke();
  doc.fontSize(8.5).text(label, (startX + endX) / 2 - 32, y - 16, {
    width: 64,
    align: 'center'
  });
}

function drawVerticalArrow(from, to, label, offsetX = 0) {
  const x = from.x + from.w / 2 + offsetX;
  const startY = from.y + from.h;
  const endY = to.y;
  doc.moveTo(x, startY).lineTo(x, endY - 8).stroke();
  doc.moveTo(x - 4, endY - 8).lineTo(x, endY).lineTo(x + 4, endY - 8).stroke();
  doc.fontSize(8.5).text(label, x - 34, (startY + endY) / 2 - 7, {
    width: 68,
    align: 'center'
  });
}

function drawLArrow(fromX, fromY, turnX, turnY, endX, endY, label, labelX, labelY) {
  doc.moveTo(fromX, fromY).lineTo(turnX, turnY).lineTo(endX - 8, endY).stroke();
  doc.moveTo(endX - 8, endY - 4).lineTo(endX, endY).lineTo(endX - 8, endY + 4).stroke();
  doc.fontSize(8.5).text(label, labelX, labelY, {
    width: 70,
    align: 'center'
  });
}

function drawDiagramPage() {
  doc.addPage({ size: 'A4', layout: 'landscape', margin: 35 });
  doc.font('CN');

  doc.fontSize(13).text('2. 子系统之间的集成通信图');
  doc.moveDown(0.4);
  doc.fontSize(10.5).text('下面给出校园超速监控系统的集成通信图。图中主要表示各子系统之间的信息传递关系，不强调先后顺序。');

  diagramBoxes.forEach(drawBox);

  drawHorizontalArrow(getBox('detect'), getBox('record'), '上传记录');
  drawHorizontalArrow(getBox('record'), getBox('process'), '提供记录');
  drawHorizontalArrow(getBox('process'), getBox('sms'), '触发警示');

  drawVerticalArrow(getBox('detect'), getBox('display'), '发送车速', -28);
  drawVerticalArrow(getBox('record'), getBox('stats'), '统计数据', -32);
  drawVerticalArrow(getBox('process'), getBox('relation'), '查询信息', -28);
  drawVerticalArrow(getBox('user'), getBox('report'), '权限控制', 28);

  drawLArrow(
    getBox('relation').x + getBox('relation').w,
    getBox('relation').y + getBox('relation').h / 2,
    645,
    getBox('relation').y + getBox('relation').h / 2,
    getBox('sms').x,
    getBox('sms').y + getBox('sms').h / 2,
    '提供对象',
    625,
    150
  );

  drawLArrow(
    getBox('config').x,
    getBox('config').y + getBox('config').h / 2,
    635,
    getBox('config').y + getBox('config').h / 2,
    getBox('process').x + getBox('process').w / 2,
    getBox('process').y + getBox('process').h,
    '限速值',
    610,
    135
  );

  drawLArrow(
    getBox('config').x,
    getBox('config').y + getBox('config').h / 2 + 10,
    410,
    getBox('config').y + getBox('config').h / 2 + 10,
    getBox('stats').x + getBox('stats').w / 2,
    getBox('stats').y + getBox('stats').h,
    'M/N 阈值',
    500,
    252
  );

  drawLArrow(
    getBox('record').x + getBox('record').w / 2,
    getBox('record').y + getBox('record').h,
    getBox('record').x + getBox('record').w / 2,
    340,
    getBox('report').x + getBox('report').w / 2,
    getBox('report').y,
    '查询数据',
    275,
    285
  );

  drawLArrow(
    getBox('stats').x + getBox('stats').w,
    getBox('stats').y + getBox('stats').h / 2,
    430,
    getBox('stats').y + getBox('stats').h / 2,
    getBox('sms').x,
    getBox('sms').y + getBox('sms').h / 2 + 12,
    '月度提醒',
    515,
    185
  );

  doc.fontSize(10.2).text('图示说明：', 35, 405);
  const notes = [
    '1. 车速检测子系统采集原始信息，并把实时车速发送给速度显示子系统，把检测记录发送给检测记录管理子系统。',
    '2. 超速处理子系统根据检测记录和系统配置中的限速值完成超速判定；如果超速，再结合车辆/人员信息子系统确定通知对象。',
    '3. 短信通知子系统负责发送警示短信和月度提醒短信。',
    '4. 统计与黑名单子系统负责月度、季度统计；报表查询子系统主要给管理员查看记录和报表使用。'
  ];
  let noteY = 425;
  notes.forEach((item) => {
    doc.fontSize(9.8).text(item, 42, noteY, {
      width: 760,
      lineGap: 2
    });
    noteY += doc.heightOfString(item, { width: 760, lineGap: 2 }) + 5;
  });
}

writeTitle('校园超速监控系统子系统划分与集成通信图');

writeSection('1. 根据题目背景，划分子系统');
writeParagraph('根据题目背景，这个系统既包括前端检测设备，也包括后台记录、超速处理、统计分析和管理员使用的管理功能。为了降低系统复杂度，可以把功能相关、联系紧密的部分划分到同一个子系统中，不同子系统之间再通过接口进行通信。这样更符合高内聚、低耦合的思想。');
writeParagraph('我把校园超速监控系统划分为以下几个子系统：');

drawTable(tableHeaders, tableRows);

writeSection('1.1 划分依据说明');
writeParagraph('第一，按照功能职责划分。车速检测、速度显示、记录管理、超速处理、短信通知、统计黑名单、报表查询、权限管理等本身就是不同类型的功能。');
writeParagraph('第二，按照关注点划分。实时检测和显示属于前端部分，月末和季末统计属于后台定时处理部分，管理员查询和配置属于后台管理部分。');
writeParagraph('第三，按照高内聚低耦合原则划分。把相互关联比较强的功能放在同一个子系统中，不同子系统之间只保留必要的数据传递。');
writeParagraph('第四，按照变化点划分。安全车速上限、月度阈值 M、季度阈值 N 等参数可能会变，所以单独放在系统配置子系统中会更方便维护。');
writeParagraph('第五，按照权限控制划分。因为系统中存在超级管理员和普通管理员两类角色，所以用户权限管理适合单独作为一个子系统。');

drawDiagramPage();

doc.end();
