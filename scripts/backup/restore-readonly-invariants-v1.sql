BEGIN TRANSACTION READ ONLY;

-- The singleton paper account must remain a non-negative PAPER account.
SELECT CASE
  WHEN COUNT(*) = 1
    AND BOOL_AND(account.id = 1)
    AND BOOL_AND(account.mode = 'PAPER')
    AND BOOL_AND(account.initial_cash_jpy >= 0)
    AND BOOL_AND(account.cash_jpy >= 0)
    AND BOOL_AND(account.btc_quantity >= 0)
    AND BOOL_AND(account.total_equity_jpy >= 0)
  THEN 0 ELSE 1
END
FROM public.paper_account AS account;

-- Exactly one runtime configuration is active and every value references a version.
SELECT CASE WHEN COUNT(*) FILTER (WHERE version.status = 'ACTIVE') = 1 THEN 0 ELSE 1 END
FROM public.runtime_config_versions AS version;

SELECT COUNT(*)
FROM public.runtime_config_values AS value
LEFT JOIN public.runtime_config_versions AS version ON version.id = value.version_id
WHERE version.id IS NULL;

-- Legacy executions may have no lineage. Current lineage must be present as one complete tuple.
SELECT COUNT(*)
FROM public.executions AS execution
WHERE num_nonnulls(
  execution.account_epoch_id,
  execution.execution_semantics_version,
  execution.runtime_config_hash
) NOT IN (0, 3);

SELECT COUNT(*)
FROM public.executions AS execution
LEFT JOIN public.paper_account_epochs AS epoch ON epoch.id = execution.account_epoch_id
WHERE execution.account_epoch_id IS NOT NULL AND epoch.id IS NULL;

COMMIT;
