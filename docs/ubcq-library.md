# Library delivery through permanent UBCQ URLs

The cache stores the compressed UBCQ and its indexed digest alongside the validated
image and source metadata. They are published atomically by the existing bounded
background publication worker, once per entry. Publication failure/backoff behavior
is unchanged. The compressed string is included directly in every visible library
list item, including excerpt-only entries. The existing 256 KiB budget still applies
to decoded descriptors. Pages additionally admit a contiguous prefix of entries
whose combined stored UBCQ fits **2 MiB**, while preserving the requested item-count
maximum. The first entry is always admitted, even when it exceeds 2 MiB, so large
tokens remain accessible one per page. Tokens are never truncated or omitted from
visible entries. JSON metadata and decoded descriptors are outside this token budget.

`ubcq_bytes` is stored atomically with the token; Base64 is ASCII, so its character
count equals its UTF-8 byte count. One SQL statement ranks at most `limit + 1`
metadata rows, computes cumulative token sizes, and loads payloads only for admitted
entries. Exactly one excluded metadata row serves as lookahead, with no token or
descriptor loaded. The next cursor follows the last visible entry, including on
short pages. Unknown token sizes fail closed to a single-entry page.
List pagination and the existing two-request serialization admission remain.

The frontend constructs HTML SVG/JPG URLs from `ubcq`. Formula links use `formula`,
recovering the original source from the supplied descriptor or locally decoding its
stored token. Previews keep their
serial queue, viewport loading, cancellation and temporary 429/503 retries. Download
links are ordinary anchors opening new tabs. They do not call `/ubcq`, fetch a
source descriptor, or prepare another link. `/library-item` remains unchanged and
is used only to retrieve omitted sources for viewing/editing. `/library-svg` and
its nginx/Vite routes are removed; no replacement image or link endpoint is added.

Both formula endpoints accept only plain `formula`; UBCQ is not an expression
endpoint parameter. Stored formula descriptors remain available as library source metadata.
HTML UBCQ routes keep their existing format and content-key semantics. Different
compressed spellings still fall back to the same canonical semantic output cache.

## Cached HTML token execution path

1. Within bounded preparation work, reject a missing/oversized token and hash its
   exact characters together with kind, renderer/security versions, complexity
   policy fingerprint and configured default width.
2. Look for a digest-to-content-key memory mapping, then the indexed digest on the
   stored cache entry if needed. No client-provided cache ID establishes trust.
3. If the corresponding output exists, serve it without decompressing the UBCQ,
   parsing its descriptor, repeating input/complexity validation, fetching remote
   stylesheets or generating another SVG. Cold database output still passes the
   existing SVG/JPEG storage-boundary validation; normal delivery headers remain.
4. If the token/output lookup misses, run bounded decompression, JSON parsing,
   descriptor validation, complexity checks and the existing semantic cache/render
   path. Only a successful request can establish a memory shortcut. Failed
   publication continues on the normal path so it can retry source publication.

The memory mapping contains only fixed-size digest pairs, never raw UBCQ values.
Its allowance is deducted from the existing weighted cache budget: at most 4 MiB
or 1/64 of that budget; tiny test/development caches omit the mapping. The mapping
is an optimization, not persistent trust. Missing/expired/deleted outputs cannot
be served by a leftover mapping. Restart recovers canonical library tokens through
the database index. Alternative encodings establish their own memory shortcut
after successful validation. SHA-256 lookup does not authenticate arbitrary input;
the match must resolve to an existing, previously validated output.

JPG continues to use the source SVG plus the existing independently keyed JPEG
conversion cache and bounded validation. Its first conversion may generate a JPG;
later hits retrieve the saved conversion. Cache expiry can regenerate either
format, preserving self-contained permanent-link behavior.

## Schema and deployment

The initial schema includes `ubcq`, `ubcq_key`, `ubcq_bytes` and an index on
`ubcq_key`. UBCQ bytes count toward the logical cache budget.

Schema changes use new, ordered Liquibase changesets. Applied changesets and the
initial baseline remain immutable. Liquibase manages migration history, locking
and checksums; startup applies pending changes without automatically resetting
cache or tracking tables. Migration failures stop startup and require an explicit
migration or operator resolution. Existing data is preserved by default.

Deploy compatible frontend, API and ingress changes together.
