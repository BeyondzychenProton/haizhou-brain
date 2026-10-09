-- FE-05-S2 read-only filters and stable cursor ordering over the existing Outbox.
CREATE INDEX idx_channel_delivery_binding_created_id
    ON platform_channel_delivery (binding_id, created_at, delivery_id);
CREATE INDEX idx_channel_delivery_provider_created_id
    ON platform_channel_delivery (provider, created_at, delivery_id);
CREATE INDEX idx_channel_delivery_run_created_id
    ON platform_channel_delivery (run_id, created_at, delivery_id);
CREATE INDEX idx_channel_delivery_state_created_id
    ON platform_channel_delivery (state, created_at, delivery_id);
