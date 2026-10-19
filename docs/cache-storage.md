# Cache storage

The default persistent cache budget is 2 GiB and 10,000 rows. These are logical
admission limits, not physical disk quotas or statements of available host capacity.
Configure them under `permaformula.image-cache.persistence` using `maximum-size` and
`maximum-entries` (1–1,000,000).

Before inserting or replacing an image or publishing its source, Java checks aggregate
payload bytes plus a 1 KiB per-row estimate capped at 10% of the byte budget.
PostgreSQL advisory transaction locks and SQLite writer locks serialize budget decisions.
Rejected persistence writes do not fail an otherwise successful generated image response.

Five-minute cleanup retains recently accessed entries within 80% of the byte budget
and 90% of the row cap, leaving admission headroom. Indexes, WAL, temporary files and
database free space are additional physical storage costs. Deleting cached entries
does not guarantee that database files shrink.

Local SQLite checks its filesystem with a 128 MiB reserve. PostgreSQL deployments
can configure a pressure file for disk monitoring. Missing, malformed, future-dated
or stale pressure signals pause image/source writes and visit batches. Reads,
memory hits and generation continue; a new image may need a later request before
it appears in the library. Admin eviction remains best effort.

Schema changes use new, ordered Liquibase changesets. Applied changesets and the
initial baseline remain immutable. Liquibase manages migration history, locking
and checksums; startup applies pending changes without automatically resetting
cache or tracking tables. Migration failures stop startup and require an explicit
migration or operator resolution. Existing data is preserved by default.

Test fresh installation, incremental upgrades and repeated startup against SQLite
and a disposable PostgreSQL database. Legacy databases that conflict with the
tracked baseline require an explicit reconciliation plan; startup does not erase them.

See [container configuration](../docker/README.md#persistent-image-cache),
[cache availability](cache-availability.md) and [library delivery](ubcq-library.md).
