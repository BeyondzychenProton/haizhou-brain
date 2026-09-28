package com.haizhuo.brain.platform.run;

import java.util.List;

/**
 * 会话事件分页结果（P2）：除事件本身外带出保留窗口信息。
 *
 * <p>{@code cursorFloor} 是该 Session 当前存在的最小 sessionCursor，0 表示从未裁剪，
 * 此时任何游标都不会失效。{@code cursorExpired} 为真表示请求游标早于窗口下界——
 * 中间事件已被裁剪，续传会静默丢事件，客户端必须丢弃本地游标并转全量快照恢复。
 * 该状态必须显式返回，因为"空批次"与"确实没有新事件"在载荷上无法区分。</p>
 */
public record SessionEventPage(List<SessionEvent> events, long cursorFloor, boolean cursorExpired) {
}
