package com.haizhuo.brain.platform.audit;

/** 用于界面安全展示的变更；字段值为标签，不包含原始审计 JSON。 */
public record AuditChange(String field, String oldLabel, String newLabel) {
}
