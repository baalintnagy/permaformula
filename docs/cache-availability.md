# Cache availability mitigation

This guide describes cache admission, background publication and visit batching.

## Work bounds

| Work | Bound |
| --- | --- |
| Image/source payload operations, writes, maintenance and cold SVG/JPEG validation | 2 permits per JdbcImageCache, no queue |
| Library page (256 KiB source budget), membership and publication-status queries | 2 separate metadata permits, no queue |
| Pending visit timestamps | 1024 keys; later visits replace timestamps |
| Visit batch interval | 30 seconds, reduced to TTL/4 for short TTLs (minimum 1 ms) |
| Visit batch size | At most 1024 updates; a single bounded JDBC batch |
| Source publication | 1 daemon worker, 1 queued job; lazy serialization |
| Publication retry | Next image request after 30 seconds on failure, 1 second on queue/lock pressure |
| Memory JPEG decode/validation | Existing bounded API preparation pool |

Warm memory hits only update an in-memory timestamp, not the database. No SVG
UTF-8 copy or descriptor serialization is needed to record the visit. The existing
source-key preparation still runs under its API budget; this change does not make
an entire GET request free of source processing or response allocation.

A Caffeine entry remembers whether its source is published. A cold load reads this
flag along with the validated stored image. If publication is needed, the worker
checks existing metadata before invoking its source supplier, restores a missing
output row with INSERT ... ON CONFLICT DO NOTHING, and publishes metadata without
replacing other source data. Queued jobs keep references to bounded source/output
payloads; the one-active/one-waiting limit also bounds that extra retention. The
weighted image-cache budget is not a total heap ceiling.

Aggregate transactional cache admission and disk-pressure degradation are also
applied before persistence; see [cache storage](cache-storage.md). A disk-pressure
signal pauses visit batches and source/image writes, while reads and memory hits
continue. This does not provide a database-free cold path.

Publication is best effort and may lag the image response. A deferred/failed job
is retried by a later image request; no permanently failed marker is stored and
no unbounded queue or synchronous fallback is used. Already published warm hits
skip metadata lookup and source serialization. Queue pressure and memory eviction
can delay or omit publication until a later request. Memory-only mode does not
serialize source metadata for persistence at all.

Publication and admin eviction coordinate through existing Guava striped locks.
A queued job checks that it still owns the current memory entry before writing;
eviction invalidates it under that same lock. Busy publication returns 503 to an
admin eviction rather than waiting. Touches only UPDATE, never INSERT. This avoids
adding background resurrection after deletion, but does not implement permanent
moderation or fix the separately documented race with in-flight rendering.

## Expiry semantics

Memory TTL remains expire-after-access. Persistent last_access_at uses the
database clock minus the monotonic age of the latest recorded visit, rather than
treating flush time as visit time. Batches remove a pending timestamp only if no
new visit replaced it during the write. Failed batches keep timestamps for retry.

Persisted expiry is last visit + configured TTL + twice the batch interval, up to
60 seconds of normal grace. Pending recent visits also allow a cold lookup even
if the row's persisted expiry has not caught up. Cleanup first flushes visits;
if that fails it skips eviction for that pass. This protects normal batching and
retained pending visits, not every visit during an arbitrarily long outage.

Pending state is lost on process crash. During ordinary healthy operation the
lag is roughly one interval plus database work. During outage, sustained database
saturation or pending-map overflow it can be longer. Full maps drop visits for
new keys while still updating existing keys; the next flush logs the number
dropped without logging content. Logical capacity eviction can still remove hot
entries. A restart reads only persisted state and does not extend expired rows.

## Deadlines and remaining limits

Image/source payload reads, writes, cleanup and validation share two fail-fast
permits. Library page, membership and publication-status queries use two separate
metadata permits, so expensive validation cannot consume their admission capacity.
Both classes still share the bounded JDBC pool and database; this is admission
isolation, not a guarantee against database contention. SQLite retains its single
connection. Full source detail remains on the payload budget. Preview image reads
still require payload capacity; the UI serializes previews and retries temporary
429/503 responses. Busy work returns 503 with Retry-After: 1 and no-store.
Background maintenance catches failures instead of terminating its schedule.
Each JDBC statement requests the smaller of two seconds or the remaining HTTP
deadline, rounded up to whole seconds; enforcement depends on the driver. Existing
pool acquisition/socket/lock limits remain. Request deadlines are checked before
and after stored-output validation; XML/ImageIO parsing on the calling thread and
response serialization/writes do not acquire a hard preemption guarantee.

Revalidation at the database boundary remains intact, including corrupt JPEG and
unsafe SVG rejection. The read permit remains held while validating, after the
JDBC connection is closed. Warm JPEG decoding uses the existing preparation
executor so cached raster output cannot create unbounded validation concurrency.
