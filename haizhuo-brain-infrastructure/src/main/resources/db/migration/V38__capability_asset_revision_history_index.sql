CREATE INDEX idx_capability_asset_revision_history
    ON capability_asset_revision (capability_code, reviewed_at, capability_revision_id);
