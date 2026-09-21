import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 源码扫描：**每一处 px 字号都必须乘上 `--fs-scale`**。
 *
 * ## 为什么需要它
 *
 * 设置里的「字号」四档靠一条约定生效：`styles.css` 里凡是写死的 px 字号，
 * 都要写成 `calc(Npx * var(--fs-scale, 1))`。而漏写一处的样子是**看起来全对** ——
 * 那一处不跟着缩放，其余都跟着变。这种"差一处的静默"正是这个仓库用源码扫描
 * 拦的那类问题（同 `LocalizedTextScanTest` 的理由）。
 *
 * 放行的两种写法：
 * - `em` / `rem`：相对的，跟着父级一起缩放，本来就不该乘；
 * - 其它不是字号的尺寸（间距、圆角）**不在**这条扫描的范围里 —— 那是「密度」那件事。
 */
// 路径按 cwd 拼（vitest 的 cwd 就是 web/）。**不用** `new URL(..., import.meta.url)`：
// jsdom 环境下 `import.meta.url` 不是 file 协议，readFileSync 会以
// "The URL must be of scheme file" 直接炸掉整个文件（踩过）。
const CSS = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8')

/** 认得出"乘了倍率"的写法。数字允许小数（`0.92em` 那种是 em，不走这条路）。 */
const SCALED = /^calc\(\s*\d+(?:\.\d+)?px\s*\*\s*var\(--fs-scale,\s*1\)\s*\)$/

/**
 * 只管**写死的 px 长度**：`inherit` / `em` / 别的关键字本来就跟着别处缩放，
 * 不该被这条拦（表格那两处 `font-size: inherit` 正是这个道理，见 styles.css）。
 */
const LITERAL_PX = /\d+(?:\.\d+)?px/

/** 抓 `font-size: …;` 的值（行内的，够用：这份文件每处字号都独占一行）。 */
const FONT_SIZE = /font-size:\s*([^;]+);/

/**
 * 显式豁免：某一处的字号**故意**不缩放时，在那一行写上 `/* fs-scale: off *​/` 并说明理由。
 *
 * 留这个口子是因为确实存在合理的例外（比如"字被固定尺寸的盒子框着，放大反而溢出"）。
 * 没有它的话，第一个遇到例外的人只会把整个扫描删掉 —— 那才是真损失。
 */
const OPT_OUT = /\/\*\s*fs-scale:\s*off\s*\*\//

function fontSizeValues(): string[] {
  return CSS.split('\n')
    .filter((line) => !OPT_OUT.test(line))
    .map((line) => line.match(FONT_SIZE)?.[1]?.trim())
    .filter((v): v is string => v !== undefined)
}

describe('styles.css 的字号', () => {
  it('每一处 px 字号都乘了 --fs-scale —— 新加一条漏写就红', () => {
    const offenders = fontSizeValues().filter(
      (value) => LITERAL_PX.test(value) && !SCALED.test(value),
    )

    expect(
      offenders,
      '这些字号没走 --fs-scale —— 字号那四档对它们不生效。' +
        '写成 calc(Npx * var(--fs-scale, 1))，或者用相对单位（em/rem）：${offenders}',
    ).toEqual([])
  })

  it('扫描本身不是空转：确实扫到了那批 px 字号', () => {
    // 上面那条"没问题就绿"，很容易在某次改名/换写法后变成什么都没扫到。
    // 这条把下限钉住（今天 24 处 px + 2 处 em）。
    const scaled = fontSizeValues().filter((v) => SCALED.test(v))

    expect(scaled.length).toBeGreaterThanOrEqual(20)
  })

  it('相对单位那两处（标题、行内码）没有被误改', () => {
    // 它们是跟着父级缩放的自由乘客；被改成 px 就丢了这层关系
    const values = fontSizeValues()

    expect(values).toContain('1.15em')
    expect(values).toContain('0.92em')
  })
})
