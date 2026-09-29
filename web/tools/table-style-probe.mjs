#!/usr/bin/env node
/**
 * 表格观感的选型台 —— 一次性工具，不参与构建。
 *
 * ## 两轮样本
 *
 * **第一轮（2026-09-18）**：用户截图，转写区里那张 4 列表格"怎么这么难看"。
 * 原始 markdown 是游戏工程那次会话里的一条真实回复（见 MD），每个单元格里都塞着
 * 行内代码，第 4 列还是整段中文散文。结论写进了 styles.css 的单元格注释。
 *
 * **第二轮（2026-09-29）**：用户又截了一张 —— 「报表指标按数据来源拆成四类」
 * 那张表，**第一列被压成一列一个字**（"配 表 项 （ 成 熟 …"）。这与第一轮
 * 那三条（pre-wrap / anywhere / vertical-align）**不是一个病因**：那三条管的是
 * 格子里怎么折行，这一条管的是**列宽怎么分**。所以这次量的是列宽与行数。
 * 原文同样是捞出来的（`~/.claude/projects/C--M71-server/94aa7839-….jsonl:4077`），
 * 见 MD2 —— 不是照截图描的近似品。
 *
 * **定稿：`any4`**（格子 `overflow-wrap: anywhere` + `min-width: 4em`，进了
 * styles.css；回归断言在 tools/layout-probe.mjs）。量出来的依据：
 * `current` 下那张表的 **min 内容宽 634 > 窄栏可用 470** ⇒ 余量为零 ⇒ 每列都
 * 钉在自己的下限上；`any4` 把它压到 157，宽栏下分列从 [47,539,47] 变成
 * [203,466,126]。改回旧 CSS 会让 layout-probe 红四条（首列 47px、表撑破
 * 滚动壳 201px 等）。
 *
 * 两张表**每次一起渲染**：A 档那张是回归样本（改列宽不能把它弄回难看的样子），
 * 新那张是本次要修的。
 *
 * ## 三条保真规则（照抄 tools/link-contrast-probe.mjs / thinking-palette.mjs）
 *
 *   1. 样式表是**真实**的 src/styles.css —— 页面里就是
 *      `.bubble__text` > `.md-table` > table 那套类名，不是照着截图描的近似品；
 *   2. 正文是**真实**的 markdown，走**真实的 marked**（web/node_modules 里的
 *      那一份，也就是 Markdown.tsx 用的那个版本）转成 DOM，
 *      包括 `inline-code` 这个类名 —— 它是 Markdown.tsx 手工挂上去的，
 *      marked 自己不产它，postProcess 里补；
 *   3. 外层 DOM 照抄 AssistantBubble：`.entry > .row.row--assistant >
 *      .bubble.bubble--assistant > .bubble__text`。少一层，量出来的宽度就不是
 *      真实可用宽 —— 而宽度正是这张表难看的**主因**。
 *
 * ⚠ `.bubble__text` 带着 `white-space: pre-wrap`（styles.css:190），
 *   它会**继承进每个单元格**。这条在截图里看不出来、在 CSS 里极易漏，
 *   是第一轮探针钉住的第一号事实。
 *
 * ## 怎么读结果
 *
 * 每跑一次，除了出图，还会把**列宽 / 每格几行 / 有没有横向滚动**打在终端上
 * （页面自己量的，走 `--dump-dom` 捞回来，同 layout-probe.mjs）。
 * "第一列被压瘪"在终端上就是 `表B 列宽 [20, 640, 60] 行数 [30, 3, 2]`：
 * 20px 一列、30 行 —— 一列一个字，正是用户截图里那个样子。
 *
 * 用法：
 *   node tools/table-style-probe.mjs                   # 今天的样式，出图 + 报数
 *   node tools/table-style-probe.mjs --variant=minw5    # 试某个候选
 *   node tools/table-style-probe.mjs --open             # 生成并用默认浏览器打开
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const cssPath = join(webDir, 'src', 'styles.css')
const outDir = resolve(webDir, '..', 'build', 'probe')
const outFile = join(outDir, 'table-style.html')

// ------------------------------------------------------------------ 真实正文

/** 真实 markdown：游戏工程会话 8031d74a 里那条回复（A 档那张表）。 */
const MD = `
## A 档：现在基本测不到的路径

| # | 指令 | 落点 | 为什么需要 |
|---|---|---|---|
| 17 | \`种植指定作物 <landSID> <cropSID>\` | team \`cropcom.PlantCrop(player, land, cropSID)\`（走占用兜底+广播，免扣 \`moneyCost\`、不看 \`unlockLevel\`） | 图鉴条目是按 \`FarmCropData.itemSid\` 对齐的，作物 3/4 号还要解锁等级+货币；手点种满一遍很慢。配合 15/16 就能把「任意作物 × 阶段 × 变异」矩阵跑完 |
| 18 | \`清空指定田地 <landSID>\` | team \`scene.RemoveCrop\` + \`crop.Destroy()\` + 广播 | 现在只有 4（取消全部解锁）/6（整场重置），单田清理没有 |
| 19 | \`设置接受祝福次数 <landSID> <count>\` | team 写 \`crop.blessTotalCount\` | 这个字段同时是①「农作物祝福上限」拒绝分支（\`blessTotalLimit\`）②变异概率加成（\`baseMutationRate + count × blessMutationBonus\`）的输入，设成上限值可稳定复现拒绝分支 |
| 20 | \`塞入待播报变异 <landSID> <mutationID[,..]>\` | team \`MergePendingMutation(scene.pendingMutations, ...)\`（复用"同大类只留最高倍率"规则）+ \`_PushFarmDesc\` | \`pendingMutations\` 只在**主人不在农场**时写（\`AddPendingMutation\` 里 \`IsPlayerInScene\` 直接 return），人工只能"离开→等变异→再进"，这条离线播报基本漏测 |
`

/**
 * 真实 markdown：**第二轮的报障样本**。
 *
 * 出处：`~/.claude/projects/C--M71-server/94aa7839-9864-4f61-9bec-36bc053c477e.jsonl:4077`
 * 那条 assistant 回复里的第一张表（用户在截图里露出的就是它的第一行）。
 * 这里**只取第一张**（其余两张是另外的形态，本次没报障，不进样本 —— 样本越多
 * 每次出图越难读）。
 */
const MD2 = `
| 类别 | 线上来源 | 满足度 |
|---|---|---|
| **配表项**（成熟时长、浇水减时、造价、解锁等级/费用、图鉴积分、奖励档） | 直接读 \`trunk/data\`（\`FarmCropData/FarmLevelData/FarmlandData/FarmMutationData/FarmCropCollectionData\`…） | ✅ 现成，不用日志 |
| **截面统计**（等级/开地/图鉴/累计币/被祝福/当前作物分布） | \`FarmScene\` 存档（mongo \`FARM_SCENE\`：farmLevel/farmExp/unlockedLandSID/crops/blessedCount…）+ 玩家侧 \`MutantFarmData\`（collectionItems/score/bestRecords/totalFarmCoin）+ 成就/目标实例 | ✅ DB 只读查询聚合即可 |
| **事件级**（种植/浇水/收获/祝福/拿取/变异/出售/升级/解锁/领奖/进出/跨服） | \`FarmLog\`（team 服，logger \`mutantfarm\`）73 个事件 + \`GameLog\`（player 侧，logger \`mutantfarm\`） | ✅ 很全，字段够 |
| **走势/对照**（逐日曲线、达成天数、实测变异率、产出速率） | 需要把上面的事件**按玩家+时间重放** | ⚠️ 能算，但没现成报表，且有两处要补 |
`

// ------------------------------------------------------------------ 转 DOM

const { marked } = await import(
  'file:///' + join(webDir, 'node_modules', 'marked', 'lib', 'marked.esm.js').replace(/\\/g, '/')
)

/**
 * marked 的输出与 Markdown.tsx 的产物对齐。
 *
 * 组件不是"渲染 HTML 字符串"，而是把 token 拼成 React 元素 —— 两者的 DOM
 * 结构一致，**只有两处类名**是组件手工挂的、marked 不产：
 *   codespan → `class="inline-code"`（Markdown.tsx:234）
 *   link     → `class="md-link"`    （Markdown.tsx:254）
 * 这里用字符串替换补上，而不是重写 renderer —— 免去 marked 版本间
 * renderer 签名（老版位置参数 / 新版 token 对象）的坑。
 *
 * **`.md-table` 那层壳也得补**（2026-09-29 才发现漏了 —— 表现是量出来的
 * `wrapW` 一路是 null）：Markdown.tsx:133 把每张表都包在 `<div class="md-table">`
 * 里，那层负责 `max-width: 100%; overflow-x: auto`。缺了它，超宽的表会去撑气泡，
 * 而真实界面里是**表自己横向滚**——正是这次要量的那条路。
 */
function toDom(md) {
  return marked
    .parse(md)
    .replace(/<code>/g, '<code class="inline-code">')
    .replace(/<a href=/g, '<a class="md-link" href=')
    .replace(/<table>/g, '<div class="md-table"><table>')
    .replace(/<\/table>/g, '</table></div>')
}

// ------------------------------------------------------------------ 两套主题

const THEMES = [
  {
    name: '深色（Darcula）',
    vars: {
      '--bg': '#1e1f22', '--text': '#dcdcdc', '--text-dim': '#8c8c8c',
      '--border': '#393b40', '--surface': '#2b2d30', '--code-bg': '#191a1c',
      '--accent': '#2f65ca', '--accent-text': '#7fb0ff',
    },
  },
  {
    name: '浅色（IntelliJ Light）',
    vars: {
      '--bg': '#f7f8fa', '--text': '#000000', '--text-dim': '#808080',
      '--border': '#d1d1d1', '--surface': '#ffffff', '--code-bg': '#f2f2f2',
      '--accent': '#a8c7fa', '--accent-text': '#1f6feb',
    },
  },
]

/**
 * 候选修法。`current` 就是今天的 styles.css（一个字都不加）。
 * 其余每条都是**一句话能说清的独立假设** —— 选型台的意义是能一眼看出
 * 是哪一条在起作用，而不是一坨改了十几处的补丁叠上去。
 *
 * 第一轮那几条（ws / valign / wrap / fix / head / fix2）留着当**记录**：
 * 它们早已进了 styles.css，跑它们与 current 的差别应该只剩"多了表头改透明"
 * 那一处。第二轮新加的是下面这四条。
 */
const VARIANTS = {
  current: { note: '今天的样式（什么都不加）', css: '' },

  // ---- 第一轮（2026-09-18）的六个候选，留作记录 ----
  ws: {
    note: '① 单元格 white-space: normal（掐掉 pre-wrap 继承）',
    css: `.bubble__text th, .bubble__text td { white-space: normal; }`,
  },

  valign: {
    note: '② 单元格 vertical-align: top',
    css: `.bubble__text th, .bubble__text td { vertical-align: top; }`,
  },

  wrap: {
    note: '③ 单元格 overflow-wrap: break-word（放回列宽下限）',
    css: `.bubble__text th, .bubble__text td { overflow-wrap: break-word; }`,
  },

  fix: {
    note: '①②③ 合并 + 内边距/行高',
    css: `
      .bubble__text th, .bubble__text td {
        white-space: normal;          /* ① pre-wrap 是给正文的，不是给表格的 */
        overflow-wrap: break-word;    /* ③ anywhere 让列宽下限掉到 1 个字 */
        vertical-align: top;          /* ② 数字别再飘在行中间 */
        padding: 5px 10px;
        line-height: 1.5;             /* 中文没有 1.5 会挤成一条 */
      }
      .bubble__text th { background: var(--code-bg, #191a1c); }
    `,
  },

  head: {
    note: '表头改透明 + 下边框',
    css: `.bubble__text th { background: transparent; border-bottom: 2px solid var(--border, #393b40); }`,
  },

  fix2: {
    note: '①②③ + 表头改透明 + 下边框',
    css: `
      .bubble__text th, .bubble__text td {
        white-space: normal;
        overflow-wrap: break-word;
        vertical-align: top;
        padding: 5px 10px;
        line-height: 1.5;
      }
      .bubble__text th {
        background: transparent;
        border-bottom: 2px solid var(--border, #393b40);
      }
    `,
  },

  // ---- 第二轮（2026-09-29）：列宽怎么分 ----
  //
  // 病根（假设，跑完看数）：中文之间**处处可断行**，所以纯中文格子的
  // 最小内容宽 = **一个字**。表格一旦装不下，自动布局就把这一列压到那个下限，
  // 于是一列一个字。英文长词（FarmCropCollectionData）反而有下限。
  // 下面每条都想给列宽加一个**下限**，区别在下限加在哪一层。

  minw3: {
    note: '④ 单元格 min-width: 3em（约 3 个字）',
    css: `.bubble__text th, .bubble__text td { min-width: 3em; }`,
  },

  minw5: {
    note: '⑤ 单元格 min-width: 5em（约 5 个字）',
    css: `.bubble__text th, .bubble__text td { min-width: 5em; }`,
  },

  keepall: {
    note: '⑥ 单元格 word-break: keep-all（中文整段不可断，列宽下限=整句）',
    css: `.bubble__text th, .bubble__text td { word-break: keep-all; overflow-wrap: break-word; }`,
  },

  wmax: {
    note: '⑦ 表格 width: max-content（不压列，超宽交给外面滚）',
    css: `.bubble__text table { width: max-content; }`,
  },

  // ⑤ 之上再加一条：表头不许折行（"类别"两个字竖着排也没了）
  minw5head: {
    note: '⑤ + 表头 white-space: nowrap',
    css: `
      .bubble__text th, .bubble__text td { min-width: 5em; }
      .bubble__text th { white-space: nowrap; }
    `,
  },

  minw6: {
    note: '⑧ 单元格 min-width: 6em',
    css: `.bubble__text th, .bubble__text td { min-width: 6em; }`,
  },

  minw8: {
    note: '⑨ 单元格 min-width: 8em（约 8 个字一行）',
    css: `.bubble__text th, .bubble__text td { min-width: 8em; }`,
  },

  // ⑨ 的保险丝：表的最小内容宽一旦超过栏宽，**气泡会被顶宽**（keepall 那条
  // 把表顶到 777 > 可用 666 就是这么来的）。给气泡/滚动壳掐掉 min-width:auto，
  // 超出的部分就交给 `.md-table` 的 overflow-x 去滚，而不是撑破转写区。
  minw8clamp: {
    note: '⑨ + 气泡与滚动壳 min-width: 0（超宽改成表内横滚）',
    css: `
      .bubble__text th, .bubble__text td { min-width: 8em; }
      .bubble--assistant { min-width: 0; }
      .bubble__text { min-width: 0; }
      .md-table { min-width: 0; }
    `,
  },

  w100: {
    note: '⑩ 表格 width: 100%（把宽度定死，看分布会不会变）',
    css: `.bubble__text table { width: 100%; }`,
  },

  // ⑪ —— 上面那些都在**给列宽设下限**，方向可能反了：真正吃掉宽度的是
  // **最小内容宽**里那串 76 字符的 `FarmCropData/…/FarmCropCollectionData`
  // （行内代码里一个断行机会都没有，最小内容宽 = 整串 539px）。它一占，
  // 剩下两列只能分 95px。放开行内代码的断行，最小内容宽就掉下来，
  // 浏览器才有余地按内容分。
  //
  // **只给 .inline-code**，不是给所有格子 —— 09-18 那次 `anywhere` 继承到
  // 每个格子上，把序号列的"17"算成 1 个字宽、断成了"1"/"7"。
  codeany: {
    note: '⑪ 只给行内代码放开断行（overflow-wrap: anywhere）',
    css: `.bubble__text .inline-code { overflow-wrap: anywhere; }`,
  },

  // ⑪ 之上再给一个**小**地板：中文列再挤也不至于一列一个字
  codeany4: {
    note: '⑪ + 单元格 min-width: 4em',
    css: `
      .bubble__text .inline-code { overflow-wrap: anywhere; }
      .bubble__text th, .bubble__text td { min-width: 4em; }
    `,
  },

  codeany6: {
    note: '⑪ + 单元格 min-width: 6em',
    css: `
      .bubble__text .inline-code { overflow-wrap: anywhere; }
      .bubble__text th, .bubble__text td { min-width: 6em; }
    `,
  },

  // ---- 第三轮（2026-09-29 晚）：格线**看不见** ----
  //
  // 用户的一句话报障："现在就是看不见"。量出来不是错觉：
  // 格线用的是注入的 `--border`（Darcula 下 #393b40），格子底是 `--surface`
  // （#2b2d30）—— **1.23:1**（浅色主题 1.53:1）。1px、1.2:1 的线，看不见。
  // WCAG 对非文本对比的要求是 3:1，下面按"往 --text 方向混多少"排开量。
  //
  // 这里用 `color-mix` 而不是在 Kotlin 侧新算一个颜色：混的方向两套主题都对
  // （深色往浅混、浅色往深混），且不必再多一个注入变量。
  line25: {
    note: '⑬ 格线往 --text 混 25%（≈2.3～2.7:1，仍然很轻）',
    css: `.bubble__text th, .bubble__text td { border-color: color-mix(in srgb, var(--border) 75%, var(--text) 25%); }`,
  },

  line35: {
    note: '⑭ 格线往 --text 混 35%（≈3:1，WCAG 非文本门槛）',
    css: `.bubble__text th, .bubble__text td { border-color: color-mix(in srgb, var(--border) 65%, var(--text) 35%); }`,
  },

  line45: {
    note: '⑮ 格线往 --text 混 45%（≈3.6～4.7:1，重格线）',
    css: `.bubble__text th, .bubble__text td { border-color: color-mix(in srgb, var(--border) 55%, var(--text) 45%); }`,
  },

  line35fr: {
    note: '⑭ + 外框更重（表格自己也描一圈 45%）',
    css: `
      .bubble__text th, .bubble__text td { border-color: color-mix(in srgb, var(--border) 65%, var(--text) 35%); }
      .bubble__text table { border: 1px solid color-mix(in srgb, var(--border) 55%, var(--text) 45%); }
    `,
  },

  dimline: {
    note: '⑯ 格线直接用 --text-dim（4.1 / 4.0:1，最省事的一条）',
    css: `.bubble__text th, .bubble__text td { border-color: var(--text-dim); }`,
  },

  // ⑫ —— ⑪ 只能降到 470（正好等于栏宽，**余量还是 0**，见 min/max 那两个数），
  // 因为 col2 里还留着一截 376px 的不可断段（不是那串代码，是别的）。
  // 索性给**所有格子**放开断行：下限一路降到 1 个字，表的 min 远小于栏宽，
  // 余量出现，浏览器才可能按内容分而不是"每列都钉在下限"。
  //
  // 与 09-18 那条教训的关系：那次是 `anywhere` 继承进格子又**没有地板**，
  // 序号列于是被算成 1 个字宽、"17"断成两行。地板就是防这一手的。
  anyonly: {
    note: '⑫ 单元格 overflow-wrap: anywhere（不加地板，看余量）',
    css: `.bubble__text th, .bubble__text td { overflow-wrap: anywhere; }`,
  },

  any4: {
    note: '⑫ + 单元格 min-width: 4em',
    css: `
      .bubble__text th, .bubble__text td { overflow-wrap: anywhere; min-width: 4em; }
    `,
  },

  any6: {
    note: '⑫ + 单元格 min-width: 6em',
    css: `
      .bubble__text th, .bubble__text td { overflow-wrap: anywhere; min-width: 6em; }
    `,
  },
}

// ------------------------------------------------------------------ 页面

/** 一个面板 = 一套主题，里面两张表（A 档 + 报障那张）。候选的覆盖样式是**全局**的
 *  （探针一次只跑一个候选），直接整页生效 —— 不做作用域改写，免得引入探针自己的 bug。 */
function panel(theme, variant) {
  const style = Object.entries(theme.vars).map(([k, v]) => `${k}=${v}`).join('; ')
  return `
<section class="panel" style="${Object.entries(theme.vars).map(([k, v]) => `${k}: ${v};`).join(' ')}">
  <header class="panel__cap">${theme.name} · ${variant.note}</header>
  <div class="entry">
    <div class="row row--assistant">
      <div class="bubble bubble--assistant">
        <div class="bubble__text">${toDom(MD)}</div>
      </div>
    </div>
    <div class="row row--assistant">
      <div class="bubble bubble--assistant">
        <div class="bubble__text">${toDom(MD2)}</div>
      </div>
    </div>
  </div>
</section>`
}

function buildPage(variantKey) {
  const variant = VARIANTS[variantKey]
  if (!variant) throw new Error(`未知候选：${variantKey}（可选：${Object.keys(VARIANTS).join(', ')}）`)
  const blocks = THEMES.map((theme) => panel(theme, variant))
  // 栏宽：默认 860（宽窗口，看横排比例）；--narrow 420（**贴近真实工具窗口** ——
  // "一列一个字"那种极端是窄栏里才现形的，宽窗口下只表现为"窄一点"）
  const stackW = NARROW ? 420 : 860
  // **必须 String.raw**：这段页面脚本里有正则（反斜杠加括号、反斜杠加 s 那种），
  // 而普通模板字符串会把反斜杠当转义吃掉 —— 发到页面上的正则就变了形
  // （2026-09-29 量对比度时栽的第三回：前两回是注释里的反引号）。
  // String.raw 照样能做字符串插值，只是反斜杠原样保留。
  return String.raw`<!doctype html>
<html><head><meta charset="utf-8">
<link rel="stylesheet" href="file:///${cssPath.replace(/\\/g, '/')}">
<style>
  html, body { height: auto; overflow: visible; }
  body { padding: 14px; font-family: -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; }
  /* 单列铺开：并排两列会让每栏只剩 420px，而 420px 下这张表本来就必然横向滚动，
     量不出"格子挤不挤"。要横着比就 --open 之后自己拉窗口 */
  .stack { display: grid; grid-template-columns: 1fr; gap: 14px; max-width: ${stackW}px; }
  .panel { border: 1px solid var(--border); border-radius: 8px; padding: 10px;
           background: var(--bg); color: var(--text, #dcdcdc); }
  .panel__cap { font-size: 11px; color: var(--text-dim); margin-bottom: 8px;
                font-family: ui-monospace, monospace; }
  /* 测量值不摆在气泡里（那会挤到被测的东西），单独一行小字 */
  #measure-slot { font: 11px ui-monospace, monospace; color: #8c8c8c; padding: 4px 2px; }
</style>
${variant.css ? `<style>\n${variant.css}\n</style>` : ''}
</head><body><div class="stack">${blocks.join('')}</div>
<pre id="measure-slot">MEASURE {}</pre>
<script>
  // 量**深色那一个面板**（两套主题布局一样，量一套就够）
  function measureTable(t) {
    if (!t) return null
    const wrap = t.closest('.md-table')
    const row = (t.tBodies[0] && t.tBodies[0].rows[0]) || t.rows[0]
    const cells = row ? Array.from(row.cells) : []
    const rects = (el) => {
      const r = document.createRange()
      r.selectNodeContents(el)
      return r.getClientRects().length
    }
    // 表自己的**最小/最大内容宽**：自动表格布局就在这两个数之间做分配，
    // 不看它俩，光看渲染出来的列宽是推不出病因的（2026-09-29 加）。
    // 量法是临时把 width 改成 min-/max-content 再读回来 —— 量完立刻还原，
    // 免得影响后面的截图（截图是另起一次浏览器，本来就互不干扰）。
    const intrinsic = (value) => {
      const prev = t.style.width
      t.style.width = value
      const w = Math.round(t.getBoundingClientRect().width)
      t.style.width = prev
      return w
    }
    return {
      cols: cells.map((c) => Math.round(c.getBoundingClientRect().width)),
      lines: cells.map(rects),
      w: Math.round(t.getBoundingClientRect().width),
      minContent: intrinsic('min-content'),
      maxContent: intrinsic('max-content'),
      wrapW: wrap ? Math.round(wrap.clientWidth) : null,
      wrapScrollW: wrap ? Math.round(wrap.scrollWidth) : null,
      scrolls: wrap ? wrap.scrollWidth > wrap.clientWidth + 1 : null,
    }
  }
  const panelEl = document.querySelector('.panel')
  const tables = panelEl ? panelEl.querySelectorAll('.bubble__text table') : []
  // 宿主这条链的宽度：表为什么是这个宽，答案在这几层里（2026-09-29 量到
  // "表比栏宽还宽"时加的 —— 光看表本身的数字推不出来）
  const host = (() => {
    const pick = (el, name) => {
      if (!el) return name + ': 无'
      const cs = getComputedStyle(el)
      return name + ':' + Math.round(el.getBoundingClientRect().width) +
        '(min ' + cs.minWidth + ', max ' + cs.maxWidth + ', ' + cs.display + ')'
    }
    return [
      pick(panelEl, '面板'),
      pick(panelEl && panelEl.querySelector('.entry'), 'entry'),
      pick(panelEl && panelEl.querySelector('.row--assistant'), 'row'),
      pick(panelEl && panelEl.querySelector('.bubble--assistant'), '气泡'),
      pick(panelEl && panelEl.querySelector('.bubble__text'), '正文'),
      pick(panelEl && panelEl.querySelector('.md-table'), '滚动壳'),
    ].join(' | ')
  })()
  const out = {
    // 正文的字号档会变（--fs-scale），记下来免得两组数字不是同一个前提
    fontSize: (() => {
      const el = document.querySelector('.bubble__text')
      return el ? getComputedStyle(el).fontSize : null
    })(),
    host: host,
    // 报障那张表**第一行每格**的最小内容宽：表的 min 是被哪个格子顶住的，
    // 一眼看得出来（不然只能对着列宽猜，2026-09-29 就是这么绕了两轮的）。
    // 量法：把格子的内容深拷进一个 width: min-content 的绝对定位 div，
    // **放进格子里**（这样继承的是格子的字体与断行规则，不是 body 的）。
    // 注：这段在模板字符串里，注释里别用反引号 —— 会把外面那层提前闭合。
    cellMins: (() => {
      const t = tables[1]
      const row = t && t.tBodies[0] && t.tBodies[0].rows[0]
      if (!row) return null
      return Array.from(row.cells).map((cell) => {
        const d = document.createElement('div')
        d.style.cssText = 'position:absolute; width:min-content; left:-9999px;'
        for (const n of cell.childNodes) d.appendChild(n.cloneNode(true))
        cell.appendChild(d)
        const w = Math.round(d.getBoundingClientRect().width)
        d.remove()
        return w
      })
    })(),
    // 格线**看不看得见**：算 WCAG 对比度（格线色 vs 格子真正压着的那层底色）。
    // 2026-09-29 晚加的 —— "看不见"这种报障必须落成一个数，不然只能靠眼睛争。
    lines: (() => {
      const t = tables[1]
      const r = t && t.tBodies[0] && t.tBodies[0].rows[0]
      const th = t && t.tHead && t.tHead.rows[0]
      if (!r) return null
      const srgb = (c) => {
        c /= 255
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)
      }
      // 颜色解析要认**三种**写法，少一种就会量出 null（2026-09-29 栽了两回）：
      //   #rrggbb           —— 计算值里的老写法
      //   rgb(a, b, c)      —— 同上
      //   color(srgb r g b) —— **color-mix 算出来的就是这个**，分量是 0~1
      // 注意：这一段在模板字符串里，注释里不能出现反引号（会把外层提前闭合）。
      const parse = (css) => {
        if (!css) return null
        const s = String(css).trim()
        if (s[0] === '#') {
          return { v: [1, 3, 5].map((i) => parseInt(s.substr(i, 2), 16)), a: 1 }
        }
        let m = s.match(/^rgba?\(([^)]+)\)/)
        if (m) {
          const p = m[1].split(/[,\s/]+/).filter(Boolean).map(Number)
          return { v: p.slice(0, 3), a: p.length > 3 ? p[3] : 1 }
        }
        m = s.match(/^color\(srgb\s+([^)]+)\)/)
        if (m) {
          const p = m[1].split(/[\s/]+/).filter(Boolean).map(Number)
          return { v: p.slice(0, 3).map((x) => x * 255), a: p.length > 3 ? p[3] : 1 }
        }
        return null
      }
      // 往上找第一层不透明的底色 —— 格子自己通常是 transparent
      const bgOf = (el) => {
        let e = el
        while (e) {
          const c = parse(getComputedStyle(e).backgroundColor)
          if (c && c.a > 0) return c
          e = e.parentElement
        }
        return { v: [255, 255, 255], a: 1 }
      }
      const ratio = (a, b) => {
        const L = (c) => 0.2126 * srgb(c.v[0]) + 0.7152 * srgb(c.v[1]) + 0.0722 * srgb(c.v[2])
        const la = L(a), lb = L(b)
        const hi = Math.max(la, lb), lo = Math.min(la, lb)
        return Math.round(((hi + 0.05) / (lo + 0.05)) * 100) / 100
      }
      const cell = r.cells[0]
      const cs = getComputedStyle(cell)
      const borderColor = parse(cs.borderTopColor)
      const cellBg = bgOf(cell)
      const headCell = th ? th.cells[0] : null
      const hcs = headCell ? getComputedStyle(headCell) : null
      return {
        line: cs.borderTopColor,
        bg: 'rgb(' + cellBg.v.join(', ') + ')',
        body: borderColor ? ratio(borderColor, cellBg) : null,
        head: hcs ? ratio(parse(hcs.borderTopColor) || borderColor, bgOf(headCell)) : null,
      }
    })(),
    tableA: measureTable(tables[0]),
    tableB: measureTable(tables[1]),
  }
  document.getElementById('measure-slot').textContent = 'MEASURE ' + JSON.stringify(out)
</script>
</body></html>`
}

// ------------------------------------------------------------------ 跑

const variantKey = (process.argv.find((a) => a.startsWith('--variant=')) ?? '--variant=current')
  .split('=')[1]
const NARROW = process.argv.includes('--narrow')
/** 量的那一次与截图那一次**必须同一个视口** —— 否则数字与图对不上。 */
const VIEWPORT = `${NARROW ? 480 : 900},2600`

mkdirSync(outDir, { recursive: true })
writeFileSync(outFile, buildPage(variantKey))
console.log(`候选：${variantKey} — ${VARIANTS[variantKey].note}`)
console.log(`页面：${outFile}`)

const CHROMIUM = [
  process.env.CCoder_CHROMIUM,
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
].filter(Boolean).find((p) => existsSync(p))

if (!CHROMIUM) {
  console.error('找不到 Chromium（Edge / Chrome），设 CCoder_CHROMIUM 指定路径')
  process.exit(2)
}

const url = 'file:///' + outFile.replace(/\\/g, '/')
const baseArgs = ['--headless=new', '--disable-gpu', '--no-sandbox', '--virtual-time-budget=2000']

// ① 报数：页面自己量（`--dump-dom` 把量完的 DOM 捞回来）。
//    **非贪婪会漏**：JSON 里嵌着数组和对象，`\{.*?\}` 会在第一个 `}` 就收手 ——
//    这里 JSON.stringify 出来**没有换行**，所以按行取到底。
const dom = execFileSync(CHROMIUM, [...baseArgs, `--window-size=${VIEWPORT}`, '--dump-dom', url], {
  encoding: 'utf8',
  maxBuffer: 64 * 1024 * 1024,
})
const hit = dom.match(/MEASURE (\{[^\n]*\})/)
if (!hit) {
  console.error('页面没有回报测量值（脚本没跑起来？）')
  process.exit(3)
}
const m = JSON.parse(hit[1].replace(/&quot;/g, '"').replace(/&amp;/g, '&'))

function report(label, t) {
  if (!t) return `${label} 没量到`
  const scroll = t.scrolls ? `溢出（外面滚 ${t.wrapScrollW}>${t.wrapW}）` : '不溢出'
  return `${label}  列宽 ${JSON.stringify(t.cols)}  行数 ${JSON.stringify(t.lines)}  ` +
    `表宽 ${t.w}（min ${t.minContent} / max ${t.maxContent}，栏宽 ${t.wrapW}）  ${scroll}`
}

console.log(`正文 ${m.fontSize} 下的实测：`)
console.log(`  宿主  ${m.host}`)
console.log(`  表A（A 档 4 列，回归样本）  ${report('', m.tableA)}`)
console.log(`  表B（报表 3 列，本次报障）  ${report('', m.tableB)}`)
if (m.cellMins) console.log(`  表B 第一行各格的最小内容宽  ${JSON.stringify(m.cellMins)}`)
if (m.lines) {
  console.log(
    `  格线 ${m.lines.line} 压在 ${m.lines.bg} 上：` +
      `正文格 ${m.lines.body}:1 · 表头格 ${m.lines.head}:1` +
      `（"看不见"= 1.2:1 左右；看得见的门槛 3:1）`,
  )
}
console.log()

if (process.argv.includes('--open')) {
  execFileSync('cmd', ['/c', 'start', '', outFile], { stdio: 'ignore' })
}

const shot = join(outDir, `table-style-${variantKey}${NARROW ? '-narrow' : ''}.png`)
execFileSync(
  CHROMIUM,
  [...baseArgs, `--window-size=${VIEWPORT}`, `--screenshot=${shot}`, url],
  { stdio: 'ignore' },
)
console.log(`截图：${shot}`)
