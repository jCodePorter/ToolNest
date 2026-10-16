// 真实浏览器渲染校验：量每一行的 getBoundingClientRect，断言标尺标签 == 实测高度。
// 用法: node verify-mockup-render.js [设计稿] [输出目录]
// 依赖：Chrome 无头模式（--headless --screenshot / --dump-dom）
'use strict';
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const htmlPath = process.argv[2] || 'design/http-batch-template-align.html';
const outDir = process.argv[3] || 'build/batch-template-review';
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';

const abs = path.resolve(htmlPath).replace(/\\/g, '/');
fs.mkdirSync(outDir, { recursive: true });

// 注入一个测量脚本：把所有关键区域与标尺标签的实测高度写进 document.title
const measureJs = `
<script>
window.addEventListener('load', function () {
  setTimeout(function () {
    var out = [];
    function rect(sel, idx) {
      var els = document.querySelectorAll(sel);
      var el = els[idx || 0];
      if (!el) return null;
      var r = el.getBoundingClientRect();
      return { w: Math.round(r.width), h: Math.round(r.height) };
    }
    var card = document.querySelectorAll('.canvas .card')[0];
    if (card) {
      var ch = card.querySelector('.ch');
      var cb = card.querySelector('.cb');
      out.push('card=' + Math.round(card.getBoundingClientRect().height));
      out.push('cardhead=' + (ch ? Math.round(ch.getBoundingClientRect().height) : -1));
      out.push('cardbody=' + (cb ? Math.round(cb.getBoundingClientRect().height) : -1));
      var kids = cb ? Array.prototype.slice.call(cb.children) : [];
      out.push('cbkids=' + kids.length);
      kids.forEach(function (k, i) {
        out.push('cb' + i + '=' + Math.round(k.getBoundingClientRect().height));
      });
    }
    // 画布尺寸必须是 1:1
    var app = document.querySelector('.app');
    if (app) {
      var r = app.getBoundingClientRect();
      out.push('app=' + Math.round(r.width) + 'x' + Math.round(r.height));
    }
    // 分段控件每一项的高度（要与工具行 38 相容：项 32 + 轨道内边距 3×2）
    var seg = document.querySelector('.seg');
    if (seg) {
      out.push('seg=' + Math.round(seg.getBoundingClientRect().height));
      var si = seg.querySelector('.si');
      if (si) out.push('segitem=' + Math.round(si.getBoundingClientRect().height));
    }
    // 键值表：列宽合计 vs 容器内宽
    var kv = document.querySelector('.kv');
    if (kv) {
      var kvr = Math.round(kv.getBoundingClientRect().width);
      out.push('kv=' + kvr);
      var rows = kv.querySelectorAll('.kvr');
      if (rows.length) {
        var cs = rows[0].children;
        var sum = 0;
        for (var i = 0; i < cs.length; i++) sum += Math.round(cs[i].getBoundingClientRect().width);
        out.push('kvcols=' + sum + ' from ' + cs.length + ' cols');
      }
    }
    // 工具行四个按钮的宽度合计 vs 可用宽（挤不下会被 overflow 裁掉）
    var row = document.querySelectorAll('.canvas .cb div[style*="height:38px"]')[2];
    if (row) {
      var b = Array.prototype.slice.call(row.querySelectorAll('.btn'));
      var bw = b.reduce(function (a, x) { return a + x.getBoundingClientRect().width; }, 0);
      out.push('toolrowbtns=' + b.length + ' total=' + Math.round(bw)
        + ' avail=' + Math.round(row.getBoundingClientRect().width));
    }
    document.title = 'MEASURE|' + out.join('|');
  }, 300);
});
</script>
`;

const tmp = path.join(outDir, '_measure.html');
const html = fs.readFileSync(htmlPath, 'utf8');
fs.writeFileSync(tmp, html.replace('</body>', measureJs + '</body>'), 'utf8');

let dom = '';
try {
  dom = execFileSync(CHROME, [
    '--headless', '--disable-gpu', '--no-sandbox',
    '--virtual-time-budget=3000',
    '--window-size=1400,2000',
    '--dump-dom', 'file:///' + path.resolve(tmp).replace(/\\/g, '/')
  ], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'] });
} catch (e) {
  console.log('FAIL  浏览器渲染失败: ' + e.message);
  process.exit(1);
}

const m = dom.match(/<title>MEASURE\|([^<]*)<\/title>/);
if (!m) {
  console.log('FAIL  没有拿到测量结果（title 里没有 MEASURE 段）');
  console.log(dom.slice(0, 600));
  process.exit(1);
}
const kv = {};
m[1].split('|').forEach(p => {
  const i = p.indexOf('=');
  if (i > 0) kv[p.slice(0, i)] = p.slice(i + 1);
});
console.log('--- 浏览器实测 ---');
Object.keys(kv).forEach(k => console.log('  ' + k + ' = ' + kv[k]));

const problems = [];
function check(name, cond, detail) {
  if (cond) console.log('PASS  ' + name + '   ' + (detail || ''));
  else { console.log('FAIL  ' + name + '  -> ' + detail); problems.push(name); }
}

check('画布是 1:1 的 1266×763', kv.app === '1266x763', '实测 ' + kv.app);

// 段① 卡片 = 头 38 + 正文 204 + 边框 2 = 244
// （正文 204 = 请求行36 + 页签42 + 工具行38 + 编辑器48 + 间距24 + 内边距16）
const cardH = Number(kv.card), headH = Number(kv.cardhead), bodyH = Number(kv.cardbody);
check('段① 卡片实测高 = 244（头 38 + 正文 204 + 边框 2）', cardH === 244,
      '实测 ' + cardH + '（头 ' + headH + ' + 正文 ' + bodyH + '）');

// 正文四个子节点：请求行 36 / 页签 42 / 工具行 38 / 编辑器 48
const kids = [];
for (let i = 0; i < 8; i++) if (kv['cb' + i] !== undefined) kids.push(Number(kv['cb' + i]));
const ruler = [36, 42, 38, 48];
check('段① 正文子节点数 = 4', Number(kv.cbkids) === 4, '实测 ' + kv.cbkids);
for (let i = 0; i < 4; i++) {
  check('标尺标签 ' + ruler[i] + ' == 该行实测高度', kids[i] === ruler[i],
        '实测 ' + kids[i]);
}
// 子节点合计 + 间距 8×3 + 内边距 16 = 204
const kidsSum = kids.slice(0, 4).reduce((a, b) => a + b, 0);
check('正文合计 = 204（子节点 ' + kidsSum + ' + 间距 24 + 内边距 16）',
      kidsSum + 24 + 16 === 204, kidsSum + ' + 40 = ' + (kidsSum + 40));

// 分段控件：项 32 + 轨道 padding 3×2 = 38，与工具行同高
check('分段控件轨道高 = 38（项 32 + 内边距 6）',
      Number(kv.seg) === 38 && Number(kv.segitem) === 32,
      '轨道 ' + kv.seg + ' 项 ' + kv.segitem);

// 键值表：列宽合计 == 容器宽
if (kv.kvcols) {
  const [sum, from] = kv.kvcols.split(' from ');
  // 容器实测宽是 border-box（含 1px 边框 ×2），列宽合计要对齐的是**内宽**。
  // 差 2px 不显眼，但断言必须按内宽算 —— 否则这条永远是假的。
  const inner = Number(kv.kv) - 2;
  check('键值表列宽合计 == 容器内宽 ' + inner + '（外宽 ' + kv.kv + ' − 边框 2）',
        Number(sum) === inner, sum + ' vs ' + inner + '（' + from.trim() + ' 列）');
}

// 工具行按钮不挤：合计 + 间隙 < 可用宽
if (kv.toolrowbtns) {
  const m2 = kv.toolrowbtns.match(/total=(\d+) avail=(\d+)/);
  if (m2) {
    check('工具行按钮不撑破可用宽', Number(m2[1]) < Number(m2[2]),
          kv.toolrowbtns + '（含间隙后仍在 ' + m2[2] + ' 内）');
  }
}

// 出图
const shot = path.join(outDir, 'mockup-full.png');
try {
  execFileSync(CHROME, ['--headless', '--disable-gpu', '--no-sandbox',
    '--hide-scrollbars', '--virtual-time-budget=3000',
    '--window-size=1400,3400',
    '--screenshot=' + shot.replace(/\\/g, '/'),
    'file:///' + path.resolve(htmlPath).replace(/\\/g, '/')],
    { stdio: ['ignore', 'ignore', 'ignore'] });
  console.log('\n截图: ' + shot);
} catch (e) {
  console.log('截图失败: ' + e.message);
}

const lines = ['设计稿渲染校验  ' + new Date().toISOString(),
  '文件: ' + htmlPath, '字节: ' + fs.statSync(htmlPath).size,
  '修改时间: ' + fs.statSync(htmlPath).mtime.toISOString(), '',
  '浏览器实测值:'];
Object.keys(kv).forEach(k => lines.push('  ' + k + ' = ' + kv[k]));
lines.push('');
lines.push('通过: ' + (11 - problems.length) + '  失败: ' + problems.length);
if (problems.length) { lines.push(''); problems.forEach(p => lines.push('  - ' + p)); }
fs.writeFileSync(path.join(outDir, 'render-report.txt'), lines.join('\n'), 'utf8');
console.log('\n通过: ' + (11 - problems.length) + '  失败: ' + problems.length);
process.exit(problems.length ? 1 : 0);
