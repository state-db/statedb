-- Pending operations in Pulumi checkpoints are reconstructed by ordered journal
-- replay. This write-only projection was never used by crash recovery or export.
DROP TABLE pending_operations;
