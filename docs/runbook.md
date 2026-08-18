# Operations Runbook

Day-to-day operating instructions for the payout webhook service: start the
stack, verify health, exercise the webhook by hand, inspect Postgres and
Redis, find logs, and shut everything down gracefully.

Every command below is run from the repository root and assumes Rancher
Desktop is running with the **`dockerd (moby)`** container engine (see
[`docs/setup.md`](setup.md)).

| Fact | Value |
| --- | --- |
| Application port | `8080` |
| Health endpoint | `http://localhost:8080/actuator/health` |
| Webhook endpoint | `POST http://localhost:8080/webhooks/payout-status` |
| Signature header | `Payout-Transmission-Sig` (hex HMAC-SHA256 of the raw body) |
| Shared secret | `webhook.secret` in [`src/main/resources/application.yml`](../src/main/resources/application.yml) (workshop value: `ABCXYZ`) |
| Postgres | `localhost:5432`, database `payout_workshop`, user/password `workshop` |
| Redis | `localhost:6379` |
| Idempotency key | `payout-webhook:processed:<eventId>`, TTL `webhook.idempotency-ttl-hours` (default 24h) |
| Application log | `.run/payout-service.log` |

> The webhook, ledger, and idempotency classes ship as `TODO(workshop)` stubs
> on `main`. Until they are implemented, startup and health checks work, but
> `POST /webhooks/payout-status` returns HTTP 500 and no balance changes.

## 1. Startup

Confirm the toolchain first — it fails fast on a missing prerequisite:

```bash
./scripts/verify-env.sh
```

Start Postgres, Redis, and Spring Boot:

```bash
./scripts/dev-up.sh
```

`dev-up.sh` starts the Compose services, waits for both to report a healthy
Docker health check, builds the application with `./mvnw -q -DskipTests
package`, and launches the JAR in the background. It is safe to rerun: healthy
services are left alone and missing ones are recovered.

Timeouts and targets can be overridden with environment variables:

```bash
APP_PORT=8080 \
HEALTH_URL=http://localhost:8080/actuator/health \
DEPENDENCY_TIMEOUT=60 \
APP_STARTUP_TIMEOUT=90 \
./scripts/dev-up.sh
```

To run only the dependencies (for example when starting the app from the IDE
or running `./mvnw spring-boot:run` yourself):

```bash
docker compose up -d
```

## 2. Health verification

Application health, including the `db` and `redis` components:

```bash
curl -s http://localhost:8080/actuator/health
```

Expect `"status":"UP"` with `db`, `ping`, and `redis` all `UP`
(`management.endpoint.health.show-details: always` is set, so details are
always rendered). Only `health` and `info` are exposed:

```bash
curl -s http://localhost:8080/actuator/info
```

Container state and dependency-level checks:

```bash
docker compose ps
docker compose exec -T postgres pg_isready -U workshop -d payout_workshop
docker compose exec -T redis redis-cli ping        # expect PONG
```

The managed application process:

```bash
cat .run/payout-service.pid          # PID launched by dev-up.sh
ps -p "$(cat .run/payout-service.pid)" -o pid,etime,command
lsof -nP -iTCP:8080 -sTCP:LISTEN     # who owns port 8080
```

## 3. Manual webhook testing

The scripted end-to-end check restarts the stack, seeds an account, sends a
delivery plus a duplicate, and asserts the balance and the Redis key:

```bash
./scripts/manual-webhook-test.sh
```

It leaves the services running and honours two overrides:

```bash
WEBHOOK_SECRET=ABCXYZ EVENT_ID=evt-demo-1 ./scripts/manual-webhook-test.sh
```

To drive the endpoint by hand, sign the **exact** bytes you send — the
signature is computed over the raw request body:

```bash
SECRET=ABCXYZ
BODY='{"eventId":"evt-manual-1","payoutId":"payout-manual-001","accountId":"acct-manual-1","status":"COMPLETED","amount":25.00,"currency":"USD","occurredAt":"2026-01-01T00:00:00Z"}'
SIGNATURE=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -binary | od -An -vtx1 | tr -d ' \n')

curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:8080/webhooks/payout-status \
  -H 'Content-Type: application/json' \
  -H "Payout-Transmission-Sig: $SIGNATURE" \
  --data-raw "$BODY"
```

Expected results once the lab implementation is in place:

- Valid signature, first delivery → `200`, and the account is credited
  asynchronously (allow a moment before querying Postgres).
- Replaying the same `eventId` → `200` with **no** further balance change.
- Tampered or missing signature → `401`:

  ```bash
  curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:8080/webhooks/payout-status \
    -H 'Content-Type: application/json' \
    -H 'Payout-Transmission-Sig: deadbeef' \
    --data-raw "$BODY"
  ```

Only `COMPLETED` payouts credit the ledger; `PENDING` and `FAILED` are
accepted and ignored. The target account must already exist — seed one first:

```bash
docker compose exec -T postgres psql -U workshop -d payout_workshop -v ON_ERROR_STOP=1 \
  -c "INSERT INTO account_balance (account_id, currency, balance, version)
      VALUES ('acct-manual-1', 'USD', 100.0000, 0)
      ON CONFLICT (account_id)
      DO UPDATE SET balance = EXCLUDED.balance, currency = EXCLUDED.currency, version = 0;"
```

## 4. Postgres inspection

Open an interactive session:

```bash
docker compose exec postgres psql -U workshop -d payout_workshop
```

Useful one-liners (`-A` unaligned, `-t` tuples only, `-c` command):

```bash
docker compose exec -T postgres psql -U workshop -d payout_workshop -c '\dt'

docker compose exec -T postgres psql -U workshop -d payout_workshop \
  -c "SELECT id, account_id, currency, balance, version FROM account_balance ORDER BY account_id;"

docker compose exec -T postgres psql -U workshop -d payout_workshop \
  -Atc "SELECT balance FROM account_balance WHERE account_id = 'acct-manual-1';"
```

The `account_balance` table is created by Hibernate (`ddl-auto: update`) from
[`AccountBalance`](../src/main/java/com/payout/workshop/payout/ledger/AccountBalance.java),
so it only exists after the application has started at least once. `balance`
is `numeric(19,4)` and `version` is the optimistic-locking counter — it
increments on every successful credit, which makes it a quick way to confirm
that a duplicate delivery really was suppressed.

Reset the ledger between runs:

```bash
docker compose exec -T postgres psql -U workshop -d payout_workshop \
  -c "DELETE FROM account_balance WHERE account_id LIKE 'acct-manual-%';"
```

## 5. Redis idempotency inspection

Idempotency locks are stored as `payout-webhook:processed:<eventId>` by
[`IdempotencyService`](../src/main/java/com/payout/workshop/payout/webhook/IdempotencyService.java).

```bash
docker compose exec -T redis redis-cli KEYS 'payout-webhook:processed:*'
docker compose exec -T redis redis-cli EXISTS 'payout-webhook:processed:evt-manual-1'   # 1 = seen
docker compose exec -T redis redis-cli TTL    'payout-webhook:processed:evt-manual-1'   # seconds left
```

`TTL` should return a value at or below `webhook.idempotency-ttl-hours × 3600`
(86400 seconds by default). A `-1` means the key was written without a TTL —
that is a bug, the lock must be set atomically *with* an expiry.

To replay an event during testing, drop its key first:

```bash
docker compose exec -T redis redis-cli DEL 'payout-webhook:processed:evt-manual-1'
```

Clear every lock (development only — `FLUSHALL` wipes the whole instance):

```bash
docker compose exec -T redis redis-cli FLUSHALL
```

Interactive session and live command feed:

```bash
docker compose exec redis redis-cli
docker compose exec redis redis-cli MONITOR    # Ctrl-C to stop
```

## 6. Logs

| Source | Command |
| --- | --- |
| Spring Boot (started by `dev-up.sh`) | `tail -f .run/payout-service.log` |
| Startup failure triage | `tail -n 60 .run/payout-service.log` |
| Postgres | `docker compose logs -f postgres` |
| Redis | `docker compose logs -f redis` |
| Both dependencies | `docker compose logs -f` |

`.run/` is git-ignored and also holds `payout-service.pid` and
`payout-service.jar` (the path of the JAR `dev-up.sh` launched).

Application logging is at `DEBUG` for `com.payout.workshop`
(see `logging.level` in `application.yml`). Signature failures and duplicate
deliveries are logged at `WARN` with the event/transaction ID; secrets,
signature headers, and customer PII must never be logged.

Grep for a specific delivery:

```bash
grep 'evt-manual-1' .run/payout-service.log
```

## 7. Graceful shutdown

Stop everything the workshop scripts manage:

```bash
./scripts/dev-down.sh
```

`dev-down.sh` sends `SIGTERM` to the managed Spring Boot PID and waits up to
20 seconds before escalating to `SIGKILL`, then runs `docker compose down
--remove-orphans --timeout 20`. It refuses to kill a PID that is not the
process it started.

Graceful shutdown is enabled server-side (`server.shutdown: graceful` with
`spring.lifecycle.timeout-per-shutdown-phase: 20s`), so in-flight requests are
allowed to finish before the JVM exits.

To stop only the dependencies and keep them for the next run:

```bash
docker compose stop
```

To remove the containers **and** the Postgres data (a full reset):

```bash
docker compose down --volumes
```

## 8. Troubleshooting

| Symptom | Action |
| --- | --- |
| `dev-up.sh` reports `port 8080 is occupied by an unhealthy, unmanaged process` | Find the owner with `lsof -nP -iTCP:8080 -sTCP:LISTEN` and stop it, or rerun with `APP_PORT`/`HEALTH_URL` pointing at another port. |
| `dev-up.sh` reports `PID file points to a different process` | Check the PID, then delete `.run/payout-service.pid` and rerun. |
| `postgres did not become healthy within 60s` | `docker compose logs postgres`; confirm nothing else is bound to 5432 and raise `DEPENDENCY_TIMEOUT`. |
| Health shows `db` or `redis` `DOWN` | `docker compose ps`, then rerun `./scripts/dev-up.sh` to recover the missing dependency. |
| Webhook returns `500` | Expected on `main` — the `TODO(workshop)` stubs throw `UnsupportedOperationException`. See [`docs/lab-guide.md`](lab-guide.md). |
| Webhook returns `401` | The signature was computed over different bytes or with the wrong secret; re-sign the exact `--data-raw` body with `webhook.secret`. |
| Balance did not change | Confirm `status` is `COMPLETED`, the account row exists, and the `eventId` is new (`redis-cli EXISTS ...`); crediting is asynchronous, so allow a moment. |
| Testcontainers fails against Rancher Desktop | Already worked around in `pom.xml`; see the known-issue note in [`docs/setup.md`](setup.md). |
