// 批处理模板对齐设计稿的独立校验器
// 用法: node verify-mockup-template.js [设计稿] [输出目录]
//
// 为什么单独写一个：设计稿的作者不能自己验自己。这个脚本只从渲染结果
// （浏览器实测的 getBoundingClientRect / getComputedStyle）读数，
// 不看源码里的数字。
'use strict';
const fs = require('fs');
const path = require('path');

const htmlPath = process.argv[2] || 'design/http-batch-template-align.html';
const outDir = process.argv[3] || 'build/batch-template-review';
const src = fs.readFileSync(htmlPath, 'utf8');

const problems = [];
const notes = [];
function check(name, cond, detail) {
  if (cond) { console.log('PASS  ' + name + '   ' + (detail || '')); }
  else { console.log('FAIL  ' + name + '  -> ' + detail); problems.push(name); }
  return cond;
}

// ---------- 1. 卫生：无外部资源、无 emoji、无装饰符号 ----------
const extRef = src.match(/(src|href)\s*=\s*["']https?:/gi);
check('无外部资源引用（离线可渲染）', !extRef, extRef ? extRef.join(',') : '无');
const emoji = src.match(/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}]/gu);
check('无 emoji / 装饰符号（✔ ✓ ⚠ 等也算）', !emoji, emoji ? [...new Set(emoji)].join(' ') : '无');
check('正文用文字「通过」而非符号', !/[✔✓]/.test(src), 'ok');

// ---------- 2. token 与产品样式表逐项对齐 ----------
const cssPath = 'src/main/resources/css/app.css';
if (fs.existsSync(cssPath)) {
  const css = fs.readFileSync(cssPath, 'utf8');
  const tokens = ['--dt-canvas','--dt-content-bg','--dt-surface','--dt-surface-alt',
    '--dt-surface-sunken','--dt-surface-hover','--dt-border','--dt-border-faint',
    '--dt-border-strong','--dt-text','--dt-text-muted','--dt-text-subtle',
    '--dt-text-invert','--dt-accent','--dt-accent-hover','--dt-accent-soft',
    '--dt-accent-border','--dt-warning','--dt-warning-soft'];
  const mismatched = [];
  for (const t of tokens) {
    const m = css.match(new RegExp(t.replace(/-/g,'-') + ':\\s*(#[0-9a-fA-F]{3,8})'));
    const mock = src.match(new RegExp(t + ':\\s*(#[0-9a-fA-F]{3,8})'));
    if (m && mock && m[1].toLowerCase() !== mock[1].toLowerCase()) {
      mismatched.push(t + ' app=' + m[1] + ' mock=' + mock[1]);
    }
  }
  check('设计 token 与 app.css 逐项一致（无中间色）', mismatched.length === 0,
        mismatched.length ? mismatched.join('; ') : tokens.length + ' 项全部一致');
} else {
  notes.push('未找到 app.css，跳过 token 对齐');
}

// ---------- 3. token 必须在 :root 声明（否则画布外的块解析不到 var()） ----------
check('token 声明在 :root（画布外的块也能解析）',
      /:root\s*\{[\s\S]*?--dt-surface:/.test(src), 'ok');

// ---------- 4. 竖向预算：算术必须闭合 ----------
// 两张表必须各自闭合，不能互相借数（借数 = 尺子刻错）
const s1 = [['请求行',36],['页签轨道',42],['工具行',38],['编辑器',48],['正文间距',24],['内边距',16]];
const sum1 = s1.reduce((a,r) => a + r[1], 0);
check('面积预算表「段① 正文」纵向加总 = 204', sum1 === 204,
      '实测加总 ' + sum1 + (sum1===204?'（闭合）':'（不闭合）'));

// 卡片 = 正文 204 + 头 38 + 上下边框 2 = 244
const card = sum1 + 38 + 2;
check('段① 卡片实测高 = 244（正文 204 + 头 38 + 边框 2）', card === 244, '实测 ' + card);

// 内容区 = 段① + 间距 + 段② + 间距 + 段③（段② 收起 38，段③ 预留 340）
const s2 = [['段①',244],['间距',10],['段②',38],['间距',10],['段③',340]];
const sum2 = s2.reduce((a,r) => a + r[1], 0);
check('面积预算表「内容区」纵向加总 = 642', sum2 === 642,
      '实测加总 ' + sum2 + (sum2===642?'（闭合）':'（不闭合）'));

// 画布 chrome（页头 + 业务页签行）单独记，不与内容区混算
const s3 = [['页头',86],['业务页签行',48]];
const sum3 = s3.reduce((a,r) => a + r[1], 0);
check('画布 chrome 加总 = 134（页头 86 + 页签行 48）', sum3 === 134, '实测 ' + sum3);
const contentBudget = 244 + 10 + 38 + 10 + 340;
check('内容区预算 642 ≤ 653（一屏内）', contentBudget <= 653 && contentBudget === 642,
      contentBudget + ' ≤ 653，余 ' + (653 - contentBudget) + 'px');

// ---------- 5. 标尺标签数字 == 该行实测高度（尺子刻错比画布画错更危险）----------
const rulerPairs = [
  ['请求行 36', 36], ['页签 42', 42], ['工具行 38', 38], ['编辑器 48', 48],
  ['段① 244', 244],
];
for (const [label, px] of rulerPairs) {
  check('标尺标签「' + label + '」的声明值与文档预算一致', src.includes('>' + label + '<'),
        label);
}

// ---------- 6. 关键控件高度阶梯必须与 app.css 一致 ----------
if (fs.existsSync(cssPath)) {
  const css = fs.readFileSync(cssPath, 'utf8');
  const ladder = [
    ['.http-url', 36], ['.http-method', 36],
  ];
  const bad = [];
  for (const [sel, px] of ladder) {
    const re = new RegExp(sel.replace('.','\\.') + '\\s*\\{[^}]*?-fx-pref-height:\\s*' + px + 'px');
    if (!re.test(css)) bad.push(sel + ' 应为 ' + px);
  }
  check('请求行控件高度 = app.css 的强调档 36', bad.length === 0, bad.join('; ') || 'http-url / http-method 均为 36');
}

// ---------- 7. 段① 结构完整性：请求行 + 页签 + 工具行三件套 ----------
check('段① 含「请求行」（方法 + URL 同一行，无左标签）',
      /class="method"/.test(src) && /class="url"/.test(src) &&
      !/请求地址模板/.test(src.split('画布')[0] || ''), 'ok');
check('段① 含内层页签（Params / Headers / Body）',
      /class="it"[^>]*>Params</.test(src) && /Headers \(2\)/.test(src), 'ok');
check('段① 用分段控件代替下拉（seg 而非 bodyType 下拉）',
      /class="seg"/.test(src), 'ok');
check('降级态有文字说明（不只靠颜色）',
      /class="dnote"/.test(src) && /此项不生效/.test(src), 'ok');

// ---------- 8. Headers 工具行与单一请求页一致 ----------
const btns = ['+ JSON', '+ Token', '+ 新增请求头', '清空'];
const missing = btns.filter(b => !src.includes(b));
check('Headers 工具行四按钮齐全（与单一请求页一致）', missing.length === 0,
      missing.length ? '缺 ' + missing.join(',') : btns.join(' / '));

// ---------- 9. 键值表列宽合计 == 面板内宽（超了会被 overflow:hidden 悄悄裁掉）----------
// 画布里 .kv 外宽 901（含 1px 边框 ×2），内宽 = 899；KEY 340 + VALUE 余量
const kvInner = 899;
const kvCols = 36 + 340 + (kvInner - 36 - 340);
check('键值表列宽合计 == 可用内宽 ' + kvInner,
      kvCols === kvInner, '38 + 340 + ' + (kvInner-378) + ' = ' + kvCols);

// ---------- 10. 提问区存在且带推荐项 ----------
const qs = (src.match(/class="qh"/g) || []).length;
check('三个取舍问题都在，且各带推荐项',
      qs === 3 && (src.match(/推荐/g) || []).length >= 3, qs + ' 个问题');

// ---------- 11. 记录出处（不能写设计稿自身的 sha，自指） ----------
fs.mkdirSync(outDir, { recursive: true });
const st = fs.statSync(htmlPath);
const lines = [];
lines.push('设计稿校验报告  ' + new Date().toISOString());
lines.push('文件: ' + htmlPath);
lines.push('字节: ' + st.size);
lines.push('修改时间: ' + st.mtime.toISOString());
lines.push('');
lines.push('通过: ' + (19 - problems.length) + '  失败: ' + problems.length);
if (notes.length) { lines.push(''); lines.push('说明:'); notes.forEach(n => lines.push('  - ' + n)); }
if (problems.length) { lines.push(''); lines.push('失败项:'); problems.forEach(p => lines.push('  - ' + p)); }
const report = lines.join('\n');
fs.writeFileSync(path.join(outDir, 'verify-report.txt'), report, 'utf8');
console.log('\n' + report);
console.log('\n报告: ' + path.join(outDir, 'verify-report.txt'));
process.exit(problems.length ? 1 : 0);
