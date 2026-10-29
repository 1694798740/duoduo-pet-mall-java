package com.duoduo.ai.route;

/**
 * 路由的访问级别。
 *
 * <p>AI 管家给出的「跳转」按钮可能指向需要登录的页面：PUBLIC 页面可以直接跳，
 * LOGIN 页面必须先确认登录态，否则用户点进去只会撞上登录墙，还得自己找回原意图。
 * 这里只区分「公开」与「需要登录」两档，不细化到权限点 —— 权限由目标服务自己把关，
 * AI 侧只负责把 needLogin 如实告诉前端。
 */
public enum AuthLevel {

    /** 无需登录即可访问。 */
    PUBLIC,

    /** 需要登录；未登录时应返回 needLogin + loginUrl，而不是硬跳。 */
    LOGIN
}
