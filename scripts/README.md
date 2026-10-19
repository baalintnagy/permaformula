# Scripts

Run commands from the repository root using Bash 4+ on Linux or Git Bash on Windows.
Node.js 24 is used for frontend builds and HTTP/socket checks; JDK 25 and Maven are
needed for Java development. Docker checks require Docker's Linux engine and Compose.

| Workflow | Guide | Commands |
| --- | --- | --- |
| Local development | [Local setup](local/README.md) | `start-local`, `stop-local`, `start-ui`, `start-tunnel`, `install-rendering` |
| Docker regressions | [Docker checks](tests/docker/README.md) | `test-production-ingress`, `test-ingress-limits`, `test-egress`, `test-image-delivery`, `test-rate-limits`, `test-render-queue` |
| Optional Java tests | [Test switches](../docs/optional-tests.md) | Browser, PostgreSQL and delivery tests |

```bash
bash scripts/local/start-local.sh --admin-password 'your-admin-password' --no-tunnels
bash scripts/test.sh
```

`test.sh` runs offline syntax, HTTP-helper, line-ending, Node-discovery and local-launcher
fixtures. It does not start Docker workloads or contact a deployment host.
Use each entry point's `--help` for its options.

## Line endings

```bash
bash scripts/normalize-line-endings.sh
```

Normalization operates on the Git repository containing the current working directory.
It converts CRLF/mixed text to LF and skips ignored files, binary files, symbolic links
and files marked `-text`. It includes tracked files and non-ignored untracked files.

Docker integration tests use disposable local fixtures. Some require a separately
started smoke project, which the caller must remove afterward; see the Docker guide.
Frontend browser checks additionally require the frontend Playwright dependencies.
