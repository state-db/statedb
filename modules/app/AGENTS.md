# modules/app agent guide

## Test database and transactions

- Every `@QuarkusTest` that reads or writes domain tables extends
  `app.statedb.app.DatabaseTest` and declares `@QuarkusTest` directly on the
  concrete test class so Quarkus ArC registers it as a test bean.
- `DatabaseTest`'s `@AfterEach` delegates to `TestDb.reset()`, which commits a
  wipe of test-owned tables while preserving startup-seeded
  `service_secrets_keyset`, reloads `PolicySetCache` when policy grants were
  removed, and resets `InMemoryGitHubAppManager`. Do not write per-test
  `@AfterEach` SQL cleanups or duplicate token-hashing helpers.
- Use `db.seed*` outside active transactions for committed fixtures (HTTP
  requests, `requiringNew()` services, and concurrent tests). Use
  `@TestTransaction` for single-transaction rollback tests when nothing under
  test opens its own transaction.
- Use `QuarkusTransaction.requiringNew()` directly for mid-test committed
  mutations, worker threads, and fresh-session entity reads (`findById`, `find`)
  after HTTP or `requiringNew()` writes. Unwrapped reads on the test thread are
  safe only for scalar `count()`, projections, and native `SELECT`s.
- Use `Races.holdThenContend` for deterministic PostgreSQL lock-wait races
  instead of `Thread.sleep` barriers.
