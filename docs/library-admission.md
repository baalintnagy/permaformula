# Library resource admission

The public and authenticated admin library share the same data, components and
anonymous SVG/JPG links. Admin adds per-item best-effort cache eviction.

The public `/library-items` endpoint returns `{items, nextCursor}`. Each entry
contains the ID, editor kind, creation time and at most 256 Unicode code points
of source excerpt. **When the combined serialized UTF-8 source descriptors of
the returned page total at most 256 KiB, all full descriptors are included.**
Above that limit, the whole page uses excerpts without descriptors. The budget
includes formula/HTML, CSS, stylesheet references, layout and JSON encoding;
it excludes summary/envelope metadata. A full-source page therefore has at most
256 KiB of stored descriptor JSON plus bounded metadata, not a 256 KiB total
HTTP-response guarantee. Parsed/reserialized JSON can differ from storage.

Descriptor byte counts and excerpts are computed once when source metadata is
stored. Excerpts count toward the cache byte budget. A single SQL statement first
selects at most 24 metadata entries plus one lookahead row, computes the aggregate
from stored byte counts, then conditionally selects descriptors only for an
eligible page. The lookahead never loads a descriptor or spends source budget.
Oversized pages transfer only summary columns; SQL also truncates excerpts
defensively. Unknown byte counts fail closed to excerpts. No SVG payload is
selected for listing. This avoids fetching all sources and truncating afterward,
and prevents a race between separate budget and source queries.

Cursor pagination follows `(created_at, cache_key)` descending, using a matching
partial index on source-bearing rows. A cursor contains the last returned time
and key, not a database offset. It is strictly validated and bound as SQL
parameters. Zero offset remains accepted for first-page compatibility; nonzero
offset is rejected. Inserts and deleted cursor rows do not shift subsequent pages.
This is a live list, not a database snapshot across requests.

The UI immediately shows included full sources, with CSS/layout controls. On
excerpt pages it fetches `/library-item` only for an explicit “Show full source”
action or when restoring the Builder. Only one on-demand source is expanded at
a time; switching or leaving aborts its request and releases retained UI data.
Failed/expired source requests show an error and allow retry. Every list item now
includes its stored UBCQ independently of the decoded-source excerpt budget.
The combined UBCQ allowance is 2 MiB per page; an oversized first entry is returned
alone. Stored lengths select the page before payload strings are loaded. Lookahead
loads neither token nor descriptor, and short pages still provide a next cursor
when more entries exist. The decoded-source budget applies only to visible entries.
Previews/downloads use the normal UBCQ image endpoints; `/library-svg` is removed.
See [UBCQ library delivery](ubcq-library.md).

List and source-detail requests share two global nonblocking Java semaphore
permits per JVM. A Spring MVC interceptor identifies the controller methods,
rather than matching raw URL spelling, and releases permits in `afterCompletion`,
after synchronous serialization and response writes, including failures. There
is no waiting queue. Rejection returns 429, Retry-After: 1 and no-store. Image
preview requests and other application routes do not spend these permits. General
per-client request quotas remain independent. Library SQL queries also use the
[shared persistence budget](cache-availability.md) and request a JDBC timeout of
at most two seconds or the remaining request deadline (driver-dependent). This is not an
end-to-end response deadline.

Schema changes use new, ordered Liquibase changesets. Applied changesets and the
initial baseline remain immutable. Liquibase manages migration history, locking
and checksums; startup applies pending changes without automatically resetting
cache or tracking tables. Migration failures stop startup and require an explicit
migration or operator resolution. Existing data is preserved by default.
