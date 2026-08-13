# Pre-Work Setup Checklist

Send this 3–5 days before the session. Do not use session time for installs.

## 1. Rancher Desktop

1. Install Rancher Desktop: https://rancherdesktop.io/
2. **Preferences → Container Engine → select `dockerd (moby)`** (not
   `containerd`). This workshop relies on Testcontainers and Spring Boot's
   Docker Compose support, both of which expect a Docker-API-compatible
   socket.
3. Verify:
   ```bash
   docker version
   docker compose version
   docker run --rm hello-world
   ```

## 2. Java 17 + Maven

```bash
java -version    # must report 17 or higher (Temurin recommended)
./mvnw -v        # from inside the cloned repo
```

## 3. VS Code + Copilot

- Install the **GitHub Copilot** and **GitHub Copilot Chat** extensions.
- Sign in with your payout GitHub org account and confirm you have an active
  Copilot Business/Enterprise seat.

## 4. GitHub Copilot CLI

```bash
gh extension install github/gh-copilot   # or: install the standalone `copilot` CLI
gh auth status
copilot --version
```

## 5. Clone the repo & self-check

```bash
git clone <this-repo-url> payout-copilot-workshop
cd payout-copilot-workshop
./scripts/verify-env.sh
```

## 6. Verify the app itself boots (before touching any code)

```bash
./scripts/dev-up.sh          # starts Postgres + Redis
./mvnw spring-boot:run       # leave this running in its own terminal
```

In a second terminal:

```bash
curl -s http://localhost:8080/actuator/health | jq .
```

Or just open it in your browser (no extra setup needed):

```
http://localhost:8080/actuator/health
```

You should see `"status":"UP"` with `db`, `redis`, and `ping` all `UP` too:

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "ping": { "status": "UP" },
    "redis": { "status": "UP" }
  }
}
```

Stop the app with Ctrl+C once confirmed, then run `./scripts/dev-down.sh` to
stop this repository's Postgres and Redis services. If `db` or `redis` show
`DOWN`, re-run `./scripts/dev-up.sh` and check `docker ps` shows both
containers healthy.

Post a ✅ in the workshop Slack channel once `verify-env.sh` passes with no
`FAIL` lines. If you see a `WARN` for `gh`/`copilot`, install before the
session — the CLI and GitHub workflow capabilities can be introduced when they
fit the session plan.

## Known issue: Rancher Desktop + Testcontainers

Rancher Desktop ships Docker Engine 29+, which requires a minimum Docker API
version of 1.44 — but Testcontainers (as of 1.21.x) still falls back to a
hardcoded 1.32 default and Ryuk (its cleanup sidecar) can't bind-mount
Rancher Desktop's host-side socket path into its own containers. Both are
already worked around in this repo's `pom.xml` (`api.version=1.44` system
property + `TESTCONTAINERS_RYUK_DISABLED=true`) — you don't need to do
anything extra. If you fork/copy this project elsewhere, keep those two
surefire settings. See
[testcontainers-java#11212](https://github.com/testcontainers/testcontainers-java/issues/11212)
for background.

## What NOT to do

Even though this is a synthetic lab, do not paste real payout secrets,
production credentials, or real customer data into Copilot Chat at any point
— treat this repo like any other PCI-adjacent codebase.
