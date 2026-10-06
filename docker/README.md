# Container deployment

From the repository root, with Docker's Linux engine running:

```sh
docker compose config --quiet
docker compose up --build -d
```

The service is exposed on `127.0.0.1:5000`. Put a TLS reverse proxy in front of
it for public deployment. Set `PERMMATH_HOST` to the public origin so `/url`
returns usable permalinks. `PORT` changes the host-side Compose port; the
container listens on 5000. For example:

```sh
PERMMATH_HOST=https://math.example.com PORT=5000 docker compose up --build -d
```

## Image controls

The build stage runs Maven verification and installs the Chromium version
selected by the project's Playwright dependency. The final Ubuntu Noble image
contains a Temurin 25 JRE, the application jar, Chromium and its system
dependencies. Maven, source code, test dependencies and the Maven repository
are not copied into it. BuildKit is required for the installer dependency mount.

The runtime user is UID/GID 10001. The jar and browser installation are owned by
root and are not writable by that account. Browser downloads are disabled at
runtime. The entrypoint creates a private temporary home, sets a restrictive
umask and replaces itself with Java so shutdown signals reach the JVM.

`PERMMATH_RENDERING_CHROMIUM_SANDBOX=true` explicitly enables Playwright's
Chromium sandbox. Local development keeps the previous default of false;
containers enable it through the image environment. A sandbox launch failure
fails rendering; there is no automatic fallback to an unsandboxed browser.

Base images select Java 25 and Ubuntu Noble and are pinned by digest. Update
those digests through a regular patch process; pinning does not replace security
updates. Browser compatibility follows `pom.xml`; update the bundled
seccomp profile deliberately when upgrading Playwright.

## Runtime controls

Use `compose.yaml` to apply the controls that a Dockerfile cannot enforce:

- Read-only root filesystem, all capabilities dropped, no privilege escalation.
- Private `/tmp` tmpfs limited to 512 MiB and private shared memory of 256 MiB.
- Two CPUs, 2 GiB memory with no additional swap, and 256 processes/threads.
- Core dumps disabled, bounded file descriptors and rotating container logs.
- An init process to reap child processes and a 20-second shutdown grace period.
- A seccomp profile that permits Chromium's user-namespace sandbox.

The JVM heap is limited to 50% of available container memory and exits on a JVM
out-of-memory error. The container cache defaults to 256 MB. These are starting
budgets for load testing, not per-request resource guarantees. `tmpfs` and browser
native memory also consume the container memory budget.

`/tmp` must allow execution: Playwright extracts its Node driver and native
components there. It is `nosuid,nodev`, but intentionally not `noexec`. The
application home resides inside the same temporary mount. No persistent volume
or host home directory is required.

The Linux host must allow unprivileged user namespaces. Host AppArmor/SELinux
policies and container-runtime settings may require a narrowly scoped policy
adjustment. Verify the sandbox on the actual host. Do not work around failures
with privileged mode, `SYS_ADMIN`, unconfined seccomp, host IPC or disabling the
sandbox in production. [Playwright container guidance](https://playwright.dev/java/docs/docker).

## Smoke checks before release

The configuration was tested on Docker Desktop's Linux engine on 2026-10-06:
the image build and Java tests passed, formula/HTML rendering returned SVG,
external Bootstrap styles applied, and runtime/process inspection confirmed the
configured restrictions and a Chromium launch without `--no-sandbox`. Repeat
these checks on the deployment host; kernel security policy and load can differ.

```sh
docker compose exec permmath id
docker compose exec permmath sh -c 'test ! -w /app/service.jar && test ! -w /opt/permmath/browsers && test -w /tmp'
curl --fail --get --data-urlencode 'formula=sqrt(x^2+y^2)' http://localhost:5000/img --output formula.svg
curl --fail --get --data-urlencode 'formula=<p>Styled `x^2`</p>' --data-urlencode 'style=p { color: red; }' http://localhost:5000/url
```

Request the permalink returned by `/url` and check that `/image` renders correctly.
Also test a supported public HTTPS `styleRefs` URL. Check the effective runtime
configuration with `docker inspect`, and inspect Chromium's command line after
an HTML render: `--no-sandbox` must be absent. Validate sandbox state on the host,
not just the environment variable. Exercise shutdown and limits under load.

## Remaining deployment boundaries

The API, controlled stylesheet fetcher, formula renderer and Chromium still share
one container/account. This setup does not isolate a compromised browser from
the API's files, memory, network access or supplied secrets. Do not inject
unneeded credentials or mount sensitive files/Docker sockets into this container.

External styles remain supported, so the container still needs DNS and public
HTTPS egress. Dockerfile/Compose do not implement a destination firewall. Apply
host/platform egress rules to reject internal, loopback-external-to-the-container,
link-local and metadata destinations where applicable. A separate browser worker
is still needed to deny its egress independently while allowing the controlled
fetcher access to public HTTPS. Do not set `network_mode: none` on the current
combined service, which would break external stylesheet fetching.

Container limits bound aggregate consumption; they do not fix unbounded GZIP
expansion, input size, queues, native hangs or unsafe SVG output. Those application
issues remain tracked in `sec.md`.

## Seccomp provenance

`seccomp-chromium.json` is based on Playwright v1.59.0's
[profile](https://github.com/microsoft/playwright/blob/v1.59.0/utils/docker/seccomp_profile.json),
licensed under Apache 2.0 (see `LICENSE.playwright`). Its upstream SHA-256 is
`cc3e61cabda6bbc1e53e54d27ba4d55a9d3be829b6dd1a596f4a7b31b1cc7849`.
Local modifications return `ENOSYS` (38) for `clone3`, allowing libc to fall
back to `clone` for JVM/browser thread creation without permitting `clone3`.
The profile also permits `chroot`, needed inside Chromium's user namespace when
the container drops all host capabilities. The upstream conditional allowance
otherwise requires a container-level `SYS_CHROOT` capability. Permitting the
syscall does not grant that host capability. The default action remains deny;
the upstream user-namespace allowances are retained. Review this profile with
Docker/kernel updates.
