package com.duoduo.fulfilment.support;

import java.util.Set;

/**
 * 物流节点字典（单一事实来源）。
 *
 * <p><b>为什么节点码要封闭成白名单</b>：轨迹是**外部系统往库里写**的数据
 * （真实形态是快递公司的推送，当前用模拟推送，报文形态与真实一致）。
 * 如果 node_code 接受任意字符串，一张订单的轨迹里很快就会同时出现
 * {@code PICKED_UP} / {@code pickedUp} / {@code 已揽收} / {@code YILANSHOU} 四种写法，
 * 前端想按节点类型上图标、或想判断「是否已签收」时，没有任何一处能写对。
 * 所以：**节点码由本域定义，推送方只能选用，不能自创**。
 *
 * <p>落库一律存**枚举名**（VARCHAR），不用数字码 —— 与售后单状态同一口径，
 * 线上 {@code SELECT node_code FROM t_logistics_track} 直接能看懂。
 *
 * <p>节点中文文案由服务端给出（{@link #labelOf}），前端不再维护第二份映射：
 * 两份映射迟早漂移，而「同一节点在买家页和卖家页叫法不同」在用户眼里就是系统坏了。
 */
public final class LogisticsNode {

    /** 已揽收：卖家把包裹交给快递，是「发货」在物流侧的第一个节点 */
    public static final String PICKED_UP = "PICKED_UP";
    /** 运输中：干线运输 */
    public static final String IN_TRANSIT = "IN_TRANSIT";
    /** 到达分拨中心 / 中转站 */
    public static final String ARRIVED_HUB = "ARRIVED_HUB";
    /** 派送中：已到末端网点，快递员正在送 */
    public static final String OUT_FOR_DELIVERY = "OUT_FOR_DELIVERY";
    /** 已签收：**终态**，买家在页面上最关心的就是它 */
    public static final String SIGNED = "SIGNED";
    /** 拒收（买家拒收 / 无人接收退回） */
    public static final String REJECTED = "REJECTED";
    /** 物流异常（破损、丢件、延误） */
    public static final String EXCEPTION = "EXCEPTION";

    /** 全部合法节点码。推送方只能从中选，见类注释 */
    private static final Set<String> ALL = Set.of(
            PICKED_UP, IN_TRANSIT, ARRIVED_HUB, OUT_FOR_DELIVERY, SIGNED, REJECTED, EXCEPTION);

    /**
     * 终态节点。
     *
     * <p>用途是**给出准确的业务判断**，不用于拦截写入 ——
     * 现实中「已签收」之后仍可能出现「拒收退回」这类纠偏节点（快递公司先签收后改写），
     * 把它当硬终态拦掉，等于让系统的记录与真实的包裹轨迹对不上。
     * 这里只把它当作「轨迹是否已走完」的判据。
     */
    private static final Set<String> TERMINAL = Set.of(SIGNED, REJECTED);

    /** 上报来源：卖方 / 快递公司 / 平台（写入 {@code raw} 报文里，不单独占列） */
    public static final String SOURCE_SELLER = "SELLER";
    public static final String SOURCE_COURIER = "COURIER";
    public static final String SOURCE_SYSTEM = "SYSTEM";

    private static final Set<String> SOURCES = Set.of(SOURCE_SELLER, SOURCE_COURIER, SOURCE_SYSTEM);

    private LogisticsNode() {
    }

    /** 是否合法节点码 */
    public static boolean isKnown(String code) {
        return code != null && ALL.contains(code);
    }

    /** 是否合法上报来源 */
    public static boolean isKnownSource(String source) {
        return source != null && SOURCES.contains(source);
    }

    /** 是否「轨迹已走完」的节点 */
    public static boolean isTerminal(String code) {
        return code != null && TERMINAL.contains(code);
    }

    /**
     * 节点中文文案。未知码<b>原样返回</b>而不抛异常 ——
     * 一条脏数据不该让整条轨迹打不开（与 {@code AfterSaleStatus#labelOf} 同一取舍）。
     */
    public static String labelOf(String code) {
        if (code == null) {
            return "";
        }
        return switch (code) {
            case PICKED_UP -> "已揽收";
            case IN_TRANSIT -> "运输中";
            case ARRIVED_HUB -> "到达分拨中心";
            case OUT_FOR_DELIVERY -> "派送中";
            case SIGNED -> "已签收";
            case REJECTED -> "已拒收";
            case EXCEPTION -> "物流异常";
            default -> code;
        };
    }

    /** 推送方未给节点描述时的兜底文案（就是节点名，不编造细节） */
    public static String defaultTextOf(String code) {
        return labelOf(code);
    }

    /** 全部合法节点码（供文档与冒烟脚本断言使用） */
    public static Set<String> all() {
        return ALL;
    }
}
