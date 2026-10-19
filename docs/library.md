# Public image library

Commands and source paths below are relative to the repository root.

Open **Image library** from the Builder navigation to browse persisted SVGs and
original sources. Selecting an item restores the simple-expression or HTML/CSS
editor, including stylesheet order and canvas width. Successful SVG rendering
queues source metadata for bounded background publication alongside the cached image; JPEG conversions use that
same library entry. Descriptor creation alone does not publish an item.

`GET /library-items?limit=12` returns `{items, nextCursor}` with newest entries
first (maximum 24 per page). Items contain ID, kind, creation time and an excerpt
of at most 256 Unicode code points. When the combined serialized UTF-8 descriptors
for the returned page total **256 KiB or less**, full descriptors are included
and displayed immediately, including CSS/layout. If the page exceeds that budget,
all entries use excerpts and full sources are loaded on request. The database
decides using stored descriptor byte counts before returning any full sources;
the extra pagination lookahead row does not count. Pass `nextCursor`
unchanged as `cursor` on the next request; null means the end. Nonzero `offset`
is rejected. The UI keeps a cursor history for Previous/Next navigation.
`GET /library-item?id=...` returns a source descriptor and editor kind.
Every list item includes the UBCQ stored with its validated image, even when decoded
sources use excerpts. Pages have a **2 MiB combined UBCQ budget**, returning fewer
items when necessary; one oversized entry is returned alone, with its complete
token. Follow `nextCursor` even when a page contains fewer than the requested count.
JSON metadata and the independent 256 KiB decoded-source allowance are additional.

HTML previews and **Download SVG/JPG** links use the stored token directly on
`/html-svg` or `/html-jpg`. Formula entries recover their original source from the
descriptor, locally decoding its stored token when necessary, and use `formula` on
`/exp-svg` or `/exp-jpg`. Both download links open in a new tab. No link-preparation
request, new endpoint or download flag is needed. Formula endpoints do not accept UBCQ.
Matching HTML token digests skip descriptor decoding; misses use full input validation.
See the [UBCQ cache record](../docs/ubcq-library.md). `/library-svg` is removed.
Expired, evicted, and metadata-free entries are excluded. Memory-only deployments
have an empty library. Source metadata counts toward the persistent cache budget.
The library is public: rendered source content is visible to other visitors.
New entries may appear shortly after rendering. Publication is best effort under
load or database failure; later image requests retry deferred/failed publication.
On excerpt pages, full source is fetched when requested or opened in the Builder,
and at most one source is expanded at a time. Small pages show all sources. List and
source-detail responses share two global Java permits, held through response
serialization/writing; excess requests receive 429 and Retry-After. Normal image
previews do not consume these permits. Library queries also share the persistent
work budget and request a timeout of at most two seconds or the remaining request
deadline; cursor queries use the partial creation-time/cache-key index.

Schema changes use new, ordered Liquibase changesets. Applied changesets and the
initial baseline remain immutable. Liquibase manages migration history, locking
and checksums; startup applies pending changes without automatically resetting
cache or tracking tables. Migration failures stop startup and require an explicit
migration or operator resolution. Existing data is preserved by default. See [cache storage](../docs/cache-storage.md).
