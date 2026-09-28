package com.haizhuo.brain.api.session;

import java.util.List;

/**
 * 会话事件分页响应（P2）：带出保留窗口下界与游标是否失效。
 * {@code cursorExpired} 为真时客户端必须丢弃本地游标、清空本地时间线并从 0 重新补读，
 * 否则会永久停在过期快照上。
 */
public record SessionEventPageResponse(List<StreamEvent> events, long cursorFloor, boolean cursorExpired) {
}
