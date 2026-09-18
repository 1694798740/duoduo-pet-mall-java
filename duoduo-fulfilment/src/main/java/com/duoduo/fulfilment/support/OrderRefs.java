package com.duoduo.fulfilment.support;

/**
 * 订单号 → 本域稳定内部标识。
 *
 * <p>履约域有多个子域（评价、售后）需要把「交易域的订单号」变成自己的唯一键的一部分。
 * 为什么不用订单号原样做键：
 * <ul>
 *   <li>订单号是字符串，索引体积大、比较慢；</li>
 *   <li>它是交易域的**对外标识**，对方若调整生成规则（加前缀 / 换长度），
 *       本域的唯一键会跟着漂移；</li>
 *   <li>更要紧的是<b>一致性</b>：如果评价与售后各自写一份哈希算法，
 *       将来有人改了一处，同一张订单在两个子域里会得到两个不同的 id ——
 *       这种不一致不会报错，只会在某次对账时变成一个查不清的谜。</li>
 * </ul>
 *
 * <p>所以哈希只留一处实现，两个子域共用。算法本身不要求密码学强度
 * （它只用于「同一订单判定」），但要求**确定**：同样的输入永远同样的输出。
 */
public final class OrderRefs {

    /** 质数种子，避免低位出现规律性。 */
    private static final long SEED = 1125899906842597L;

    private OrderRefs() {
    }

    /**
     * 由订单号派生稳定的订单标识。
     *
     * @param orderNo 交易域订单号，不可为空
     * @return 非负的稳定 bigint
     */
    public static Long orderIdOf(String orderNo) {
        if (orderNo == null) {
            throw new IllegalArgumentException("orderNo 不能为空");
        }
        long hash = SEED;
        for (int i = 0; i < orderNo.length(); i++) {
            hash = 31 * hash + orderNo.charAt(i);
        }
        return Math.abs(hash);
    }
}
