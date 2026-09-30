#!/usr/bin/env node
/*
 * 生成内置离线词库（assets/dict/core.tsv）
 *
 * 数据来源：ECDICT（https://github.com/skywind3000/ECDICT，MIT）
 *   - ecdict.csv     词条、音标、英文释义、中文释义、词频等
 *   - lemma.en.txt   词形还原表（ran → run），用于查不到原形时回退
 *
 * 用法：
 *   node tools/build-local-dict.mjs <ecdict.csv> <lemma.en.txt> <输出目录>
 * 例：
 *   node tools/build-local-dict.mjs ecdict.csv lemma.en.txt app/src/main/assets/dict
 *
 * 产物：
 *   core.tsv   常用词条：`单词\t音标\t中文释义`（释义里多条用「；」分隔）
 *   lemma.tsv  词形还原：`变形\t原形`
 */

import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';

const [, , csvPath, lemmaPath, outDir] = process.argv;
if (!csvPath || !lemmaPath || !outDir) {
  console.error('用法: node tools/build-local-dict.mjs <ecdict.csv> <lemma.en.txt> <输出目录>');
  process.exit(1);
}

const LIMIT = Number(process.env.DICT_LIMIT || 40000);
const CJK = /[\u4e00-\u9fff]/;

/** 简单 CSV 行解析（支持双引号包裹与转义） */
function splitCsv(line) {
  const out = [];
  let field = '';
  let quoted = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (quoted) {
      if (c === '"') {
        if (line[i + 1] === '"') { field += '"'; i++; } else quoted = false;
      } else field += c;
    } else if (c === '"') {
      quoted = true;
    } else if (c === ',') {
      out.push(field); field = '';
    } else field += c;
  }
  out.push(field);
  return out;
}

function cleanMeaning(text) {
  return text
    .replace(/\\n/g, '；')
    .replace(/\\r/g, ' ')
    .replace(/\\t/g, ' ')
    .replace(/\[网络\][^；]*；?/g, '')
    .split(/[；\n]/)
    .map((s) => s.trim())
    .filter(Boolean)
    .slice(0, 6)
    .join('；');
}

function score(row) {
  let s = 0;
  if (row.oxford === '1') s += 8;
  s += Number(row.collins || 0) * 2;
  if (Number(row.bnc) > 0) s += 2;
  if (Number(row.frq) > 0) s += 2;
  if (row.tag) s += 1;
  if (/^[a-z-]+$/.test(row.word)) s += 1;   // 纯单词优于短语/专名
  if (row.word.length <= 12) s += 1;
  return s;
}

function rank(row) {
  const frq = Number(row.frq) || 999999;
  const bnc = Number(row.bnc) || 999999;
  return frq * 2 + bnc;
}

async function readEntries() {
  const rows = [];
  const rl = readline.createInterface({ input: fs.createReadStream(csvPath), crlfDelay: Infinity });
  let header = true;
  for await (const line of rl) {
    if (header) { header = false; continue; }
    if (!line.trim()) continue;
    const parts = splitCsv(line);
    if (parts.length < 5) continue;
    const row = {
      word: (parts[0] || '').trim(),
      phonetic: (parts[1] || '').trim(),
      definition: (parts[2] || '').trim(),
      translation: (parts[3] || '').trim(),
      collins: parts[5] || '',
      oxford: parts[6] || '',
      tag: parts[7] || '',
      bnc: parts[8] || '',
      frq: parts[9] || ''
    };
    if (!row.word || !CJK.test(row.translation)) continue;
    if (row.word.length > 32) continue;
    rows.push(row);
  }
  return rows;
}

const rows = (await readEntries())
  .sort((a, b) => score(b) - score(a) || rank(a) - rank(b))
  .slice(0, LIMIT);

fs.mkdirSync(outDir, { recursive: true });
const coreLines = [];
const coreWords = new Set();
for (const row of rows) {
  const meaning = cleanMeaning(row.translation);
  if (!meaning) continue;
  const word = row.word.toLowerCase();
  coreLines.push([word, row.phonetic, meaning].join('\t'));
  coreWords.add(word);
}
fs.writeFileSync(path.join(outDir, 'core.tsv'), coreLines.join('\n') + '\n', 'utf8');
console.log('core.tsv 词条数：' + coreLines.length +
  ' 大小：' + (fs.statSync(path.join(outDir, 'core.tsv')).size / 1048576).toFixed(1) + ' MB');

/*
 * 词形还原表。
 *
 * lemma.en.txt 的真实格式是「原形/词频 -> 变形1,变形2,...」（该文件头注明共 84487 个 lemma 组），
 * 例如：run/44715 -> running,ran,runs。它给的是「原形 → 变形」，而查词需要的是反过来的
 * 「变形 → 原形」，所以这里必须整表反转，不能只按空白切两列。
 *
 * 历史 bug：旧实现写的是 parts[0] / parts[1]，于是 form='run/44715'、base='->'，
 * 产出的 84487 行第二列恒为 "->"，词形还原整列作废（ran 查不到 run）。
 *
 * 一个变形可能同时挂在多个原形下（如 better 同时属于 well/156075 与 good/128437），取舍规则：
 *   1) 优先取 core.tsv 里查得到的原形 —— 否则这条映射永远查不到词条，等于废数据；
 *   2) 同一优先级下保留先出现的那个（文件按词频降序排列，即更常用的原形优先）。
 */
const lemmaMap = new Map();
let lemmaConflict = 0;
for (const line of fs.readFileSync(lemmaPath, 'utf8').split(/\r?\n/)) {
  if (!line.trim() || line.startsWith(';') || line.startsWith('#')) continue;
  const arrow = line.indexOf('->');
  if (arrow < 0) continue;
  const head = line.slice(0, arrow).trim();
  // 大多数行是「原形/词频 -> 变形表」，也有约 2.2 万行只写「原形 -> 变形表」，两种都要认
  const slash = head.lastIndexOf('/');
  const base = (slash > 0 ? head.slice(0, slash) : head).trim().toLowerCase();
  if (!base) continue;
  for (const raw of line.slice(arrow + 2).split(',')) {
    const form = raw.trim().toLowerCase();
    if (!form || form === base) continue;
    const prev = lemmaMap.get(form);
    if (prev === undefined) { lemmaMap.set(form, base); continue; }
    if (prev === base) continue;
    lemmaConflict++;
    if (!coreWords.has(prev) && coreWords.has(base)) lemmaMap.set(form, base);
  }
}
const lemmaLines = [];
for (const [form, base] of lemmaMap) lemmaLines.push(form + '\t' + base);
fs.writeFileSync(path.join(outDir, 'lemma.tsv'), lemmaLines.join('\n') + '\n', 'utf8');
console.log('lemma.tsv 条数：' + lemmaLines.length +
  ' 大小：' + (fs.statSync(path.join(outDir, 'lemma.tsv')).size / 1048576).toFixed(1) + ' MB' +
  '（多原形冲突 ' + lemmaConflict + ' 处，按「core 命中优先、词频次之」取舍）');
