// 搜索关键词高亮：把文本切成「命中 / 未命中」片段，交给 WXML 渲染样式
//
// 为什么不用 rich-text（拼 HTML 字符串）：文本来自商品标题与用户输入，拼 HTML 就带上了注入面；
// 拆成片段数组后样式完全由 WXML 控制，**零注入面**，也不依赖 rich-text 的解析行为。
//
// 命中口径与后端「精确优先」的召回一致：整串优先，另外把空格/逗号分隔的每个词也标出来
// （用户搜「猫粮 2kg」时，标题里的「猫粮」和「2kg」都应当被标亮）。

/** @returns {Array<{text: string, hit: boolean}>} 至少返回一段（保证 WXML 一定能渲染） */
function segments(text, keyword) {
  const src = text === null || text === undefined ? '' : String(text);
  const kw = (keyword || '').trim();
  if (!src || !kw) {
    return [{ text: src, hit: false }];
  }

  const words = [kw].concat(kw.split(/[\s,，、]+/)).filter((w) => w.length > 0);
  const lower = src.toLowerCase();
  const marks = new Array(src.length).fill(false);

  words.forEach((word) => {
    const lw = word.toLowerCase();
    let from = 0;
    while (from <= lower.length - lw.length) {
      const idx = lower.indexOf(lw, from);
      if (idx < 0) break;
      for (let i = idx; i < idx + lw.length; i++) marks[i] = true;
      from = idx + lw.length; // 不重叠匹配，避免同一段被反复标记
    }
  });

  const out = [];
  let cur = { text: src[0], hit: marks[0] };
  for (let i = 1; i < src.length; i++) {
    if (marks[i] === cur.hit) {
      cur.text += src[i];
    } else {
      out.push(cur);
      cur = { text: src[i], hit: marks[i] };
    }
  }
  out.push(cur);
  return out;
}

module.exports = { segments: segments };
