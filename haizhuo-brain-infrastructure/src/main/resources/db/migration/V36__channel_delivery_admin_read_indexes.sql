-- 为 FE-05-S2 的只读筛选和现有 Outbox 稳定游标排序提供索引。
CREATE INDEX idx_channel_delivery_binding_created_id
    ON platform_channel_delivery (binding_id, created_at, delivery_id);
CREATE INDEX idx_channel_delivery_provider_created_id
    ON platform_channel_delivery (provider, created_at, delivery_id);
CREATE INDEX idx_channel_delivery_run_created_id
    ON platform_channel_delivery (run_id, created_at, delivery_id);
CREATE INDEX idx_channel_delivery_state_created_id
    ON platform_channel_delivery (state, created_at, delivery_id);
