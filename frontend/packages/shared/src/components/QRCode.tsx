/**
 * 二维码渲染组件。
 *
 * <h2>2026-10-18：矩阵生成已改为使用成熟库 {@code qrcode}</h2>
 * 本文件最早是「零依赖手写 ISO/IEC 18004」，实测生成的是<b>不合标准</b>的码：
 *   · OpenCV 与 zbar 两套解码器都无法识别（同一内容用 OpenCV 生成的参考码可正常解出 —— 排除解码器问题）；
 *   · 矩阵级诊断：三个定位图案均不在位（左上角 (0,0) 应为深色却是白）、时序图案 5 处错误、
 *     校正图形错位、格式信息与 32 种合法组合最近汉明距离 6（要求 ≤3）。
 * 表现就是「微信 / 支付宝 / 系统相机扫了毫无反应」，支付链路直接断在第一步。
 *
 * <p>二维码编码是有标准答案的事，自研没有收益只有风险，因此矩阵生成交给 {@code qrcode} 库；
 * SVG 绘制仍保留在本文件（矢量不糊、样式与站内一致）。
 * 原有的自研编码函数仅作留档，不再参与渲染。
 */
/**
 * 极简二维码渲染组件（零依赖）。
 *
 * 为什么不装第三方库：本项目只用于展示支付 codeUrl，
 * 引入 qrcode 之类依赖会多出 200KB+ 包体与一条供应链风险。
 *
 * 实现范围：Byte 模式（UTF-8），纠错等级 L/M/Q/H，
 * 版本 1–10 自动选型（足够容纳 url 长度 < 200 的收银台地址）。
 * 编码流程遵循 ISO/IEC 18004：数据编码 → 纠错码(Reed-Solomon) → 矩阵排布 → 掩码择优。
 *
 * 注意：本工程开启了 noUncheckedIndexedAccess，所有定长表都用 `at()` 包装取值，
 * 越界会显式抛错而不是静默产生 undefined 流入计算。
 */

import React, { useMemo } from 'react';
import QR from 'qrcode';

// ---------------- GF(256) 伽罗华域算术 ----------------

const EXP_TABLE = new Uint8Array(512);
const LOG_TABLE = new Uint8Array(256);

(function initGaloisField() {
  let x = 1;
  for (let i = 0; i < 255; i += 1) {
    EXP_TABLE[i] = x;
    LOG_TABLE[x] = i;
    x <<= 1;
    if (x & 0x100) x ^= 0x11d; // 本原多项式 x^8+x^4+x^3+x^2+1
  }
  for (let i = 255; i < 512; i += 1) EXP_TABLE[i] = EXP_TABLE[i - 255] as number;
})();

/** 从定长表安全取值（表是常量，越界即编程错误，直接抛出便于定位） */
function at<T>(table: readonly T[], index: number, label: string): T {
  const value = table[index];
  if (value === undefined) throw new Error(`${label} 索引越界: ${index}`);
  return value;
}

function gfMul(a: number, b: number): number {
  if (a === 0 || b === 0) return 0;
  return at(numberTable(EXP_TABLE), at(numberTable(LOG_TABLE), a, 'LOG') + at(numberTable(LOG_TABLE), b, 'LOG'), 'EXP');
}

/** Uint8Array → number[] 视图（定长表用，只做一次转换） */
function numberTable(bytes: Uint8Array): readonly number[] {
  return Array.from(bytes);
}

/** 生成 degree 次生成多项式 */
function rsGeneratorPoly(degree: number): number[] {
  let poly = [1];
  for (let i = 0; i < degree; i += 1) {
    const next = new Array<number>(poly.length + 1).fill(0);
    for (let j = 0; j < poly.length; j += 1) {
      const coef = at(poly, j, 'poly');
      next[j] = (at(next, j, 'next') ^ gfMul(coef, at(numberTable(EXP_TABLE), i, 'EXP'))) as number;
      next[j + 1] = (at(next, j + 1, 'next') ^ coef) as number;
    }
    poly = next;
  }
  return poly;
}

/** 计算纠错码字 */
function rsEncode(data: readonly number[], ecCount: number): number[] {
  const gen = rsGeneratorPoly(ecCount);
  const res = new Array<number>(ecCount).fill(0);
  for (const byte of data) {
    const factor = byte ^ (at(res, 0, 'res') as number);
    res.shift();
    res.push(0);
    if (factor !== 0) {
      for (let i = 0; i < ecCount; i += 1) {
        res[i] = (at(res, i, 'res') ^ gfMul(at(gen, i + 1, 'gen'), factor)) as number;
      }
    }
  }
  return res;
}

// ---------------- 版本与容量表（版本 1–10） ----------------

type EcLevel = 'L' | 'M' | 'Q' | 'H';

const MAX_VERSION = 10;

/** 总码字数 */
const TOTAL_CODEWORDS: readonly number[] = [0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346];

/** 每块纠错码字数 */
const EC_CODEWORDS_PER_BLOCK: Record<EcLevel, readonly number[]> = {
  L: [0, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18],
  M: [0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26],
  Q: [0, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24],
  H: [0, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28],
};

/** 分块数 */
const BLOCK_COUNT: Record<EcLevel, readonly number[]> = {
  L: [0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4],
  M: [0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5],
  Q: [0, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8],
  H: [0, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8],
};

/** 校正图形中心坐标 */
const ALIGNMENT_POSITIONS: readonly (readonly number[])[] = [
  [],
  [],
  [6, 18],
  [6, 22],
  [6, 26],
  [6, 30],
  [6, 34],
  [6, 22, 38],
  [6, 24, 42],
  [6, 26, 46],
  [6, 28, 50],
];

const EC_LEVEL_BITS: Record<EcLevel, number> = { L: 1, M: 0, Q: 3, H: 2 };

interface VersionSpec {
  version: number;
  totalCodewords: number;
  ecPerBlock: number;
  blocks: number;
  dataCodewords: number;
}

function specOf(version: number, ec: EcLevel): VersionSpec {
  const totalCodewords = at(TOTAL_CODEWORDS, version, 'TOTAL_CODEWORDS');
  const ecPerBlock = at(EC_CODEWORDS_PER_BLOCK[ec], version, `EC_${ec}`);
  const blocks = at(BLOCK_COUNT[ec], version, `BLOCK_${ec}`);
  return {
    version,
    totalCodewords,
    ecPerBlock,
    blocks,
    dataCodewords: totalCodewords - ecPerBlock * blocks,
  };
}

/** 选择能容纳数据的最小版本 */
function pickVersion(byteLength: number, ec: EcLevel): VersionSpec {
  for (let version = 1; version <= MAX_VERSION; version += 1) {
    const spec = specOf(version, ec);
    // 模式指示符 4 bit + 字符计数（版本 1–9 用 8 bit，10 用 16 bit）
    const countBits = version <= 9 ? 8 : 16;
    const capacityBits = spec.dataCodewords * 8 - 4 - countBits;
    if (byteLength * 8 <= capacityBits) return spec;
  }
  throw new Error('二维码内容过长');
}

// ---------------- 位缓冲 ----------------

class BitBuffer {
  readonly bits: number[] = [];

  put(value: number, length: number): void {
    for (let i = length - 1; i >= 0; i -= 1) this.bits.push((value >>> i) & 1);
  }

  get length(): number {
    return this.bits.length;
  }
}

// ---------------- 矩阵 ----------------

interface QrMatrix {
  size: number;
  modules: boolean[][];
  reserved: boolean[][];
}

function newGrid(size: number, fill: boolean): boolean[][] {
  return Array.from({ length: size }, () => new Array<boolean>(size).fill(fill));
}

function buildMatrix(version: number): QrMatrix {
  const size = version * 4 + 17;
  const modules = newGrid(size, false);
  const reserved = newGrid(size, false);

  const set = (r: number, c: number, dark: boolean) => {
    if (r < 0 || r >= size || c < 0 || c >= size) return;
    (modules[r] as boolean[])[c] = dark;
    (reserved[r] as boolean[])[c] = true;
  };

  // 三个定位图形（含 1 模块白边）
  const finderOrigins: [number, number][] = [
    [0, 0],
    [0, size - 7],
    [size - 7, 0],
  ];
  for (const [or, oc] of finderOrigins) {
    for (let r = -1; r <= 7; r += 1) {
      for (let c = -1; c <= 7; c += 1) {
        const inRing =
          (r >= 0 && r <= 6 && (c === 0 || c === 6)) ||
          (c >= 0 && c <= 6 && (r === 0 || r === 6)) ||
          (r >= 2 && r <= 4 && c >= 2 && c <= 4);
        set(or + r, oc + c, inRing);
      }
    }
  }

  // 时序图形
  for (let i = 8; i < size - 8; i += 1) {
    const dark = i % 2 === 0;
    set(6, i, dark);
    set(i, 6, dark);
  }

  // 校正图形
  const positions = at(ALIGNMENT_POSITIONS, version, 'ALIGNMENT');
  for (const r of positions) {
    for (const c of positions) {
      if ((r <= 8 && c <= 8) || (r <= 8 && c >= size - 9) || (r >= size - 9 && c <= 8)) continue;
      for (let dr = -2; dr <= 2; dr += 1) {
        for (let dc = -2; dc <= 2; dc += 1) {
          const dark = Math.abs(dr) === 2 || Math.abs(dc) === 2 || (dr === 0 && dc === 0);
          set(r + dr, c + dc, dark);
        }
      }
    }
  }

  // 预留格式信息区
  for (let i = 0; i <= 8; i += 1) {
    set(8, i, false);
    set(i, 8, false);
  }
  for (let i = 0; i < 8; i += 1) {
    set(8, size - 1 - i, false);
    set(size - 1 - i, 8, false);
  }
  set(size - 8, 8, true);

  // 预留版本信息区（版本 ≥ 7）
  if (version >= 7) {
    for (let i = 0; i < 18; i += 1) {
      const r = Math.floor(i / 3);
      const c = size - 11 + (i % 3);
      set(r, c, false);
      set(c, r, false);
    }
  }

  return { size, modules, reserved };
}

function placeData(matrix: QrMatrix, codewords: readonly number[]): void {
  const { size, modules, reserved } = matrix;
  const bits: number[] = [];
  for (const cw of codewords) {
    for (let i = 7; i >= 0; i -= 1) bits.push((cw >>> i) & 1);
  }

  let index = 0;
  let upward = true;

  for (let col = size - 1; col > 0; col -= 2) {
    if (col === 6) col -= 1; // 跳过时序列
    for (let i = 0; i < size; i += 1) {
      const row = upward ? size - 1 - i : i;
      for (const c of [col, col - 1]) {
        if ((reserved[row] as boolean[])[c]) continue;
        (modules[row] as boolean[])[c] = index < bits.length ? bits[index] === 1 : false;
        index += 1;
      }
    }
    upward = !upward;
  }
}

function maskFn(pattern: number, row: number, col: number): boolean {
  switch (pattern) {
    case 0:
      return (row + col) % 2 === 0;
    case 1:
      return row % 2 === 0;
    case 2:
      return col % 3 === 0;
    case 3:
      return (row + col) % 3 === 0;
    case 4:
      return (Math.floor(row / 2) + Math.floor(col / 3)) % 2 === 0;
    case 5:
      return ((row * col) % 2) + ((row * col) % 3) === 0;
    case 6:
      return (((row * col) % 2) + ((row * col) % 3)) % 2 === 0;
    default:
      return (((row + col) % 2) + ((row * col) % 3)) % 2 === 0;
  }
}

/** ISO/IEC 18004 掩码惩罚分 */
function penaltyScore(modules: boolean[][], size: number): number {
  let score = 0;
  const darkAt = (r: number, c: number) => (modules[r] as boolean[])[c] as boolean;

  // 规则 1：同色连续 ≥5
  for (let i = 0; i < size; i += 1) {
    let runRow = 1;
    let runCol = 1;
    for (let j = 1; j < size; j += 1) {
      if (darkAt(i, j) === darkAt(i, j - 1)) runRow += 1;
      else {
        if (runRow >= 5) score += 3 + (runRow - 5);
        runRow = 1;
      }
      if (darkAt(j, i) === darkAt(j - 1, i)) runCol += 1;
      else {
        if (runCol >= 5) score += 3 + (runCol - 5);
        runCol = 1;
      }
    }
    if (runRow >= 5) score += 3 + (runRow - 5);
    if (runCol >= 5) score += 3 + (runCol - 5);
  }

  // 规则 2：2×2 同色
  for (let r = 0; r < size - 1; r += 1) {
    for (let c = 0; c < size - 1; c += 1) {
      const v = darkAt(r, c);
      if (v === darkAt(r, c + 1) && v === darkAt(r + 1, c) && v === darkAt(r + 1, c + 1)) score += 3;
    }
  }

  // 规则 3：1:1:3:1:1 特征
  const pattern = [true, false, true, true, true, false, true];
  const matches = (get: (i: number) => boolean, start: number) => {
    for (let k = 0; k < 7; k += 1) if (get(start + k) !== (pattern[k] as boolean)) return false;
    return true;
  };
  const quiet = (get: (i: number) => boolean, from: number, to: number) => {
    for (let i = Math.max(0, from); i < Math.min(size, to); i += 1) {
      if (get(i)) return false;
    }
    return true;
  };
  for (let i = 0; i < size; i += 1) {
    const rowGet = (j: number) => (modules[i] as boolean[])[j] as boolean;
    const colGet = (j: number) => (modules[j] as boolean[])[i] as boolean;
    for (let j = 0; j <= size - 7; j += 1) {
      if (matches(rowGet, j) && (quiet(rowGet, j - 4, j) || quiet(rowGet, j + 7, j + 11))) score += 40;
      if (matches(colGet, j) && (quiet(colGet, j - 4, j) || quiet(colGet, j + 7, j + 11))) score += 40;
    }
  }

  // 规则 4：深色占比偏离 50%
  let dark = 0;
  for (let r = 0; r < size; r += 1) {
    for (let c = 0; c < size; c += 1) if (darkAt(r, c)) dark += 1;
  }
  const ratio = (dark * 100) / (size * size);
  score += Math.floor(Math.abs(ratio - 50) / 5) * 10;

  return score;
}

function writeFormatInfo(matrix: QrMatrix, ec: EcLevel, mask: number): void {
  const { size, modules } = matrix;
  const data = (EC_LEVEL_BITS[ec] << 3) | mask;

  let rem = data;
  for (let i = 0; i < 10; i += 1) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
  const bits = ((data << 10) | rem) ^ 0x5412;
  const bit = (i: number) => ((bits >>> i) & 1) === 1;
  const put = (r: number, c: number, v: boolean) => {
    (modules[r] as boolean[])[c] = v;
  };

  // 左上角
  for (let i = 0; i <= 5; i += 1) put(8, i, bit(i));
  put(8, 7, bit(6));
  put(8, 8, bit(7));
  put(7, 8, bit(8));
  for (let i = 9; i <= 14; i += 1) put(14 - i, 8, bit(i));

  // 右上 / 左下
  for (let i = 0; i <= 7; i += 1) put(size - 1 - i, 8, bit(i));
  for (let i = 8; i <= 14; i += 1) put(8, size - 15 + i, bit(i));

  put(size - 8, 8, true); // 固定深色模块
}

function writeVersionInfo(matrix: QrMatrix, version: number): void {
  if (version < 7) return;
  const { size, modules } = matrix;

  let rem = version;
  for (let i = 0; i < 12; i += 1) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
  const bits = (version << 12) | rem;

  for (let i = 0; i < 18; i += 1) {
    const dark = ((bits >>> i) & 1) === 1;
    const r = Math.floor(i / 3);
    const c = size - 11 + (i % 3);
    (modules[r] as boolean[])[c] = dark;
    (modules[c] as boolean[])[r] = dark;
  }
}

/** 编码为模块矩阵 */
/**
 * 【留档】自研的编码实现 —— 已不再参与渲染（矩阵改由 {@code qrcode} 库生成，见文件头）。
 *
 * <p>保留的原因：它记录了当初按 ISO/IEC 18004 实现时踩过的坑（定位图案、格式信息、掩码择优），
 * 以及为什么最终放弃自研。直接删掉会让"这里曾经为什么这么写"彻底失忆。
 */
export function encodeToMatrix(text: string, ec: EcLevel): QrMatrix {
  const bytes = Array.from(new TextEncoder().encode(text));
  const spec = pickVersion(bytes.length, ec);

  // --- 数据码字 ---
  const buffer = new BitBuffer();
  buffer.put(0b0100, 4); // Byte 模式
  buffer.put(bytes.length, spec.version <= 9 ? 8 : 16);
  for (const byte of bytes) buffer.put(byte, 8);

  const capacityBits = spec.dataCodewords * 8;
  if (buffer.length > capacityBits) throw new Error('二维码内容过长');
  buffer.put(0, Math.min(4, capacityBits - buffer.length));
  if (buffer.length % 8 !== 0) buffer.put(0, 8 - (buffer.length % 8));

  const dataBytes: number[] = [];
  for (let i = 0; i < buffer.length; i += 8) {
    let byte = 0;
    for (let j = 0; j < 8; j += 1) byte = (byte << 1) | (buffer.bits[i + j] as number);
    dataBytes.push(byte);
  }
  // 填充码字
  let padToggle = true;
  while (dataBytes.length < spec.dataCodewords) {
    dataBytes.push(padToggle ? 0xec : 0x11);
    padToggle = !padToggle;
  }

  // --- 分块 + 纠错 ---
  const shortLen = Math.floor(spec.dataCodewords / spec.blocks);
  const longCount = spec.dataCodewords % spec.blocks;
  const dataBlocks: number[][] = [];
  const ecBlocks: number[][] = [];
  let offset = 0;

  for (let b = 0; b < spec.blocks; b += 1) {
    const len = shortLen + (b >= spec.blocks - longCount ? 1 : 0);
    const block = dataBytes.slice(offset, offset + len);
    offset += len;
    dataBlocks.push(block);
    ecBlocks.push(rsEncode(block, spec.ecPerBlock));
  }

  // --- 交错 ---
  const interleaved: number[] = [];
  const maxDataLen = dataBlocks.reduce((m, b) => Math.max(m, b.length), 0);
  for (let i = 0; i < maxDataLen; i += 1) {
    for (const block of dataBlocks) {
      const v = block[i];
      if (v !== undefined) interleaved.push(v);
    }
  }
  for (let i = 0; i < spec.ecPerBlock; i += 1) {
    for (const block of ecBlocks) {
      const v = block[i];
      if (v !== undefined) interleaved.push(v);
    }
  }

  // --- 每种掩码算一遍惩罚分，取最优 ---
  let best: QrMatrix | null = null;
  let bestScore = Number.POSITIVE_INFINITY;

  for (let mask = 0; mask < 8; mask += 1) {
    const candidate = buildMatrix(spec.version);
    placeData(candidate, interleaved);
    writeVersionInfo(candidate, spec.version);
    writeFormatInfo(candidate, ec, mask);

    const { size, modules, reserved } = candidate;
    for (let r = 0; r < size; r += 1) {
      for (let c = 0; c < size; c += 1) {
        if (!(reserved[r] as boolean[])[c] && maskFn(mask, r, c)) {
          (modules[r] as boolean[])[c] = !(modules[r] as boolean[])[c];
        }
      }
    }

    const score = penaltyScore(modules, size);
    if (score < bestScore) {
      bestScore = score;
      best = candidate;
    }
  }

  if (!best) {
    // 理论不可达，保留兜底以防将来改动引入意外
    const fallback = buildMatrix(spec.version);
    placeData(fallback, interleaved);
    writeVersionInfo(fallback, spec.version);
    writeFormatInfo(fallback, ec, 0);
    return fallback;
  }
  return best;
}

// ---------------- React 组件 ----------------

export interface QRCodeProps {
  value: string;
  /** 渲染边长（像素） */
  size?: number;
  ecLevel?: EcLevel;
  className?: string;
  alt?: string;
}

export const QRCode: React.FC<QRCodeProps> = ({
  value,
  size = 200,
  ecLevel = 'M',
  className,
  alt,
}) => {
  const matrix = useMemo(() => {
    if (!value) return null;
    try {
      // 二维码矩阵由成熟库生成（自研实现输出不合标，见文件头说明）
      const qr = QR.create(value, { errorCorrectionLevel: ecLevel });
      const size = qr.modules.size;
      const bits = qr.modules.data as Uint8Array;
      const modules: boolean[][] = [];
      for (let r = 0; r < size; r += 1) {
        const row: boolean[] = [];
        for (let c = 0; c < size; c += 1) {
          row.push(bits[r * size + c] === 1);
        }
        modules.push(row);
      }
      return { size, modules } as QrMatrix;
    } catch {
      return null;
    }
  }, [value, ecLevel]);

  if (!matrix) {
    return (
      <div
        className={className}
        style={{
          width: size,
          minHeight: 80,
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          border: '1px dashed var(--border-strong)',
          borderRadius: 8,
          fontSize: 12,
          padding: 12,
          textAlign: 'center',
        }}
      >
        二维码内容过长，无法生成。请改用下方按钮完成支付。
      </div>
    );
  }

  const moduleCount = matrix.size;
  const quiet = 4; // 静默区（规范要求 ≥4 模块）
  const total = moduleCount + quiet * 2;
  const cell = size / total;

  const rects: React.ReactNode[] = [];
  for (let r = 0; r < moduleCount; r += 1) {
    for (let c = 0; c < moduleCount; c += 1) {
      if (!(matrix.modules[r] as boolean[])[c]) continue;
      rects.push(
        <rect
          key={`${r}-${c}`}
          x={(c + quiet) * cell}
          y={(r + quiet) * cell}
          width={cell}
          height={cell}
        />,
      );
    }
  }

  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox={`0 0 ${size} ${size}`}
      role="img"
      aria-label={alt ?? '支付二维码'}
      shapeRendering="crispEdges"
      style={{ background: '#ffffff', borderRadius: 8, display: 'block', margin: '0 auto' }}
    >
      <rect width={size} height={size} fill="#ffffff" />
      <g fill="#000000">{rects}</g>
    </svg>
  );
};
