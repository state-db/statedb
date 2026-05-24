\pset pager off
\x auto

\echo ''
\echo '== bootstrap identity =='
SELECT
  o.name AS org,
  o.display_name AS org_display,
  u.email AS admin_email,
  t.description AS token_description,
  t.kind AS token_kind,
  t.expires_at AS token_expires_at
FROM organizations o
JOIN api_tokens t ON t.org_id = o.id
JOIN users u ON u.id = t.user_id
ORDER BY o.name, t.created_at;

\echo ''
\echo '== stacks =='
SELECT
  o.name AS org,
  p.name AS project,
  s.name AS stack,
  w.kind AS workspace_kind,
  s.version,
  s.active_update_id,
  s.tags,
  s.created_at,
  s.updated_at
FROM stacks s
JOIN projects p ON p.id = s.project_id
JOIN organizations o ON o.id = s.org_id
JOIN workspaces w ON w.id = s.workspace_id
ORDER BY o.name, p.name, s.name;

\echo ''
\echo '== stack state =='
SELECT
  p.name AS project,
  s.name AS stack,
  ss.base_snapshot_version,
  ss.journal_seq,
  ss.journal_entries_since_compaction,
  ss.cache_valid,
  ss.cached_at,
  jsonb_typeof(ss.cached_checkpoint) AS cached_checkpoint_type
FROM stack_state ss
JOIN stacks s ON s.id = ss.stack_id
JOIN projects p ON p.id = s.project_id
ORDER BY p.name, s.name;

\echo ''
\echo '== updates =='
SELECT
  p.name AS project,
  s.name AS stack,
  su.version,
  su.kind,
  su.status,
  su.pulumi_version,
  su.message,
  su.started_at,
  su.ended_at,
  su.id AS update_id
FROM stack_updates su
JOIN stacks s ON s.id = su.stack_id
JOIN projects p ON p.id = s.project_id
ORDER BY su.started_at DESC;

\echo ''
\echo '== journal summary =='
SELECT
  su.kind AS update_kind,
  su.status AS update_status,
  count(je.id) AS entries,
  min(je.sequence_id) AS first_sequence,
  max(je.sequence_id) AS last_sequence,
  count(DISTINCT je.resource_urn) FILTER (WHERE je.resource_urn IS NOT NULL) AS resource_urns
FROM stack_updates su
LEFT JOIN journal_entries je ON je.update_id = su.id
GROUP BY su.id, su.kind, su.status, su.started_at
ORDER BY su.started_at DESC;

\echo ''
\echo '== journal resource urns =='
SELECT DISTINCT
  je.resource_urn
FROM journal_entries je
WHERE je.resource_urn IS NOT NULL
ORDER BY je.resource_urn;

\echo ''
\echo '== events summary =='
SELECT
  su.kind AS update_kind,
  su.status AS update_status,
  ue.kind AS event_kind,
  ue.severity,
  count(*) AS events
FROM update_events ue
JOIN stack_updates su ON su.id = ue.update_id
GROUP BY su.id, su.kind, su.status, su.started_at, ue.kind, ue.severity
ORDER BY su.started_at DESC, events DESC;

\echo ''
\echo '== pending operations =='
SELECT
  resource_urn,
  resource_type,
  operation_type,
  begin_journal_seq,
  created_at
FROM pending_operations
ORDER BY created_at;
