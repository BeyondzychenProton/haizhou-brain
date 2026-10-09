ALTER TABLE platform_run_artifact
    ADD KEY idx_platform_run_artifact_blob_ref (blob_ref);
