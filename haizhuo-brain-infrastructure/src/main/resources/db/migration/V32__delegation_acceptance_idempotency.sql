-- Freeze the complete native tool request for receipt reuse within the same Run attempt.
-- Historical receipts remain NULL: operation/agentKey/label cannot be reconstructed reliably.
ALTER TABLE platform_run_delegation_invocation
    ADD COLUMN request_sha256 CHAR(64) NULL;
