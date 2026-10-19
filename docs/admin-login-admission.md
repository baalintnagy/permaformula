# Admin login admission

Implemented in Java using the existing Bucket4j dependency and bounded Caffeine
client registry. No additional proxy, service or library is needed. The limits apply per API instance.

`AdminLoginController` calls `ClientRateLimits.authenticate` immediately around
Spring Security authentication, before any password verification. It applies to
all usernames and both successful and failed attempts:

| Budget | Limit |
| --- | --- |
| Per literal client IP | 5 attempts/minute, burst 3 |
| Entire API instance | 10 attempts/minute, burst 3 |
| Simultaneous password verifications | 1, no waiting queue |

Bucket4j uses monotonic time and greedy refill: one new per-IP attempt every
12 seconds and one global attempt every 6 seconds. These are token buckets, not
fixed minute windows. Denials return HTTP 429, `Retry-After` rounded up to whole
seconds and `Cache-Control: no-store`. Busy verification returns a one-second
retry hint. Quota attempts do not reach the AuthenticationManager. Exceptions
release the concurrency slot. Quotas are not reset on successful login.

The existing trusted-proxy identity rules are reused; arbitrary forwarding
headers cannot create new identities. Per-IP login buckets are created lazily,
inside the existing capacity-limited client registry. Login clients cannot expire
before their 36-second full refill time, even with a shorter configured general
client expiry. Active verification pins its client entry. The general HTTP
request budget still applies independently. Invalid bodies rejected before
authentication do not spend login tokens and cannot trigger BCrypt.

Residual risk: public callers can exhaust the shared login allowance and delay
legitimate administration. There is no permanent account lockout. Existing JWTs
and public image requests do not spend this allowance. Restricting administration
to a VPN or trusted network is a stronger optional availability control. Limits
are per JVM and reset on restart; multiple replicas would require a shared budget
or deliberately divided quotas. Existing password strength and token policies
are unchanged.
