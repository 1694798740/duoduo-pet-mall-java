/**
 * 售后仲裁（A3）三端共用件。
 *
 * 买家端、卖家端、运营端都要用同一份「状态页签 + 类型文案 + 状态色」，
 * 各写一份必然漂移 —— 而「同一状态在两个页面显示成两种说法」在用户眼里就是系统坏了。
 * 文案本身仍以服务端下发的 `statusLabel` / `typeLabel` 为准（这里只提供筛选页签与配色）。
 */

import React from 'react';
import type { AfterSaleType } from '../api/types';

/** 状态筛选页签（值直接对齐后端的 AfterSaleStatus 枚举名） */
export const AFTER_SALE_STATUS_TABS: Array<{ value: string; label: string }> = [
  { value: '', label: '全部' },
  { value: 'PENDING', label: '待卖家处理' },
  { value: 'SELLER_REJECTED', label: '卖家已拒绝' },
  { value: 'ARBITRATING', label: '平台仲裁中' },
  { value: 'REFUNDED', label: '已退款' },
  { value: 'CLOSED', label: '已关闭' },
];

/**
 * 售后类型选项。
 *
 * hint 不是装饰：类型一旦选错，后面卖家按哪种方式处理、买家要不要寄回都跟着变，
 * 用户在提交前必须看懂两者的区别。
 */
export const AFTER_SALE_TYPE_OPTIONS: Array<{ value: AfterSaleType; label: string; hint: string }> = [
  {
    value: 'REFUND_ONLY',
    label: '仅退款',
    hint: '钱退给你，商品不用寄回 —— 适合货损、缺件、少发这类「寄回去也没意义」的情况',
  },
  {
    value: 'RETURN_REFUND',
    label: '退货退款',
    hint: '需要把商品寄回给卖家 —— 适合货不对板、不想要了这类情况（本期系统不跟踪退货物流）',
  },
];

/** 举证图张数上限（与后端 duoduo.aftersale.max-images 默认值一致） */
export const AFTER_SALE_MAX_IMAGES = 6;

/** 状态配色：只影响视觉分级，不参与任何业务判断 */
export function afterSaleTagClass(status: string): string {
  switch (status) {
    case 'PENDING':
      return 'tag tag-warn';
    case 'SELLER_REJECTED':
      return 'tag tag-danger';
    case 'REFUNDED':
      return 'tag tag-success';
    default:
      return 'tag';
  }
}

export const AfterSaleStatusTag: React.FC<{ status: string; label: string }> = ({ status, label }) => (
  <span className={afterSaleTagClass(status)}>{label}</span>
);

/**
 * 进度步骤：提交申请 → 卖家处理 → 平台仲裁 → 结果。
 *
 * 刻意不用「按状态一一对应」的做法：`SELLER_REJECTED` 是「卖家已处理完」而不是独立一阶段，
 * 硬映射会画出一条现实中不存在的路径。
 */
const STEPS = ['提交申请', '卖家处理', '平台仲裁', '处理结果'];

function stepReached(status: string): number {
  switch (status) {
    case 'PENDING':
      return 2;
    case 'SELLER_REJECTED':
      return 2;
    case 'ARBITRATING':
      return 3;
    case 'REFUNDED':
      return 4;
    case 'CLOSED':
      return 4;
    default:
      return 1;
  }
}

export const AfterSaleSteps: React.FC<{ status: string }> = ({ status }) => {
  const reached = stepReached(status);
  return (
    <div className="steps" style={{ margin: '12px 0' }}>
      {STEPS.map((label, index) => (
        <React.Fragment key={label}>
          {index > 0 ? <div className={`step-line${index < reached ? ' done' : ''}`} /> : null}
          <div className={`step${index < reached ? ' done' : ''}`}>
            <div className="step-dot">{index + 1}</div>
            <div className="small">{label}</div>
          </div>
        </React.Fragment>
      ))}
    </div>
  );
};

/** 把多行文本域里的图片直链拆成数组（一行一条，去空、去重） */
export function splitImageLines(text: string): string[] {
  return Array.from(
    new Set(
      text
        .split('\n')
        .map((line) => line.trim())
        .filter((line) => line.length > 0),
    ),
  );
}
