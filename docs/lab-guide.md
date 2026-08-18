# Lab Exercise Guide

Goal: implement a secure, idempotent payout webhook endpoint using Copilot
Plan Mode, Custom Instructions, and Agent Mode — until `./mvnw test` is fully
green.

## 0. Confirm your environment

```bash
./scripts/verify-env.sh
./scripts/dev-up.sh
```

Check the health endpoint:

```bash
curl -s http://localhost:8080/actuator/health
```

Or just open [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)
in your browser for a quick visual check.

You should see `"status":"UP"` with `db` and `redis` both `UP` - this confirms
Postgres, Redis, and the app itself are all wired up correctly *before* you
touch any code. Then run the test suite:

```bash
./mvnw test
```

If a managed service goes down, rerun `./scripts/dev-up.sh`. The script keeps
healthy services running and recovers missing ones. If Spring Boot cannot
start, inspect `.run/payout-service.log`.

When you finish working, stop all managed services gracefully:

```bash
./scripts/dev-down.sh
```

You should see failures — `SignatureVerifier`, `IdempotencyService`,
`PayoutEventListener`, `WebhookController`, and `LedgerService` are stubs that
throw `UnsupportedOperationException`. That's your starting point.

## 1. Read the spec (it's the test file)

Open [`WebhookControllerTest`](../src/test/java/com/payout/workshop/payout/webhook/WebhookControllerTest.java).
Don't edit it. It defines exactly what "done" means:

- `rejectsRequestWithInvalidSignature`
- `acceptsValidSignatureAndCreditsBalance`
- `convertsCurrencyBeforeCrediting`
- `duplicateDeliveryIsAppliedExactlyOnce`

## 2. Plan Mode

In Copilot Chat, switch to **Plan Mode** and ask:

```
We need to implement WebhookController, SignatureVerifier, IdempotencyService,
PayoutEventListener, and LedgerService so that WebhookControllerTest passes.
Propose the sequence of changes and flag any design risks before writing code.
```

Read the plan. Does it mention the atomic Redis lock? The async boundary?
Ask follow-up questions in Plan Mode before letting anything touch code.

## 3. Look at the guardrails already in the repo

Open [`.github/instructions/payout-webhook.instructions.md`](../.github/instructions/payout-webhook.instructions.md).
This applies automatically to files matching `**/*Webhook*.java` and
`**/webhook/**/*.java` — you don't have to repeat these rules in your prompts.

## 4. Agent Mode — build it

Switch to **Agent Mode** and give it the task:

```
Implement SignatureVerifier, IdempotencyService, PayoutEventListener,
WebhookController, and LedgerService so that every test in
WebhookControllerTest passes. Run ./mvnw test after each change and keep
iterating until all tests are green. Do not modify the test file.
```

Let it run. Watch what it does with the security/idempotency instructions
without being re-told. If it gets stuck on one test, you can narrow the ask:

```
Only rejectsRequestWithInvalidSignature is failing. Show me the current
SignatureVerifier implementation and fix just that test.
```

## 5. Verify the complete webhook flow manually

Passing the automated tests proves the implementation behaves correctly in the
Testcontainers environment. Now verify the complete workflow against the local
development stack:

```bash
./scripts/manual-webhook-test.sh
```

Run this command from the repository root.

> **Important:** The script restarts the local workshop stack. This removes the
> current local Postgres and Redis containers and their data before creating a
> clean environment. Do not run it against infrastructure containing data you
> need to preserve.

The script performs the following steps:

1. Stops and recreates the local Postgres and Redis services.
2. Builds and starts the Spring Boot application from the current source.
3. Seeds `acct-manual-1` with a `100.0000 USD` balance.
4. Creates a `COMPLETED` payout webhook for `25.00 USD`.
5. Signs the exact raw JSON body using the configured webhook secret.
6. Sends the webhook and waits for the asynchronous ledger update.
7. Sends the same event again to simulate a duplicate delivery.
8. Confirms that the duplicate does not credit the account twice.
9. Confirms that Redis contains the event's idempotency key.
10. Displays the final Postgres account row.

Expected final output includes:

```text
OK   - first delivery changed the balance to 125.0000
OK   - duplicate delivery left the balance at 125.0000
OK   - Redis contains the idempotency key
Manual webhook verification passed.
```

The final database row should show a balance of `125.0000` and a version of
`1`. A version of `1` confirms that only the first delivery updated the row.

### Why this step matters

`WebhookControllerTest` uses temporary Testcontainers instances that are
separate from the local development database and Redis service. A green test
suite does not populate or verify the Compose services running on your machine.

The manual script verifies the boundaries that the integration test alone does
not make visible to the participant:

- The packaged application starts successfully from the current source.
- Spring Boot connects to the local Postgres and Redis services.
- The sender and server calculate the same HMAC over the exact request body.
- The controller acknowledges the request before asynchronous ledger work
  completes.
- The balance update is persisted in the local database.
- Redis retains the idempotency key.
- A real duplicate HTTP delivery is acknowledged but not applied twice.

The application and its dependencies remain running after the script finishes.
You can inspect the final account directly:

```bash
docker compose exec -T postgres psql \
  -U workshop \
  -d payout_workshop \
  -c "SELECT account_id, currency, balance, version
      FROM account_balance
      WHERE account_id = 'acct-manual-1';"
```

Application logs are available at:

```bash
tail -f .run/payout-service.log
```

When you are finished, stop the local services gracefully:

```bash
./scripts/dev-down.sh
```

## 6. Debugging lab

Once you're green, pick one:

**Signature bypass:**
```
Refactor SignatureVerifier to compare the computed and provided signatures
using String#equals instead of a constant-time comparison.
```
Then:
```
Security review flagged a timing-attack risk in our signature comparison.
Find it and fix it, and add a test that would have caught this.
```

**Idempotency race condition:**
```
Refactor IdempotencyService#tryAcquire to first check if the key exists with
a GET, and only call SET if it's absent, instead of using an atomic
setIfAbsent call.
```
Then:
```
This webhook occasionally double-credits a payout when payout redelivers the
same event within milliseconds of the first delivery. Write a test that fires
two concurrent requests with the same payload, reproduce the bug, and fix it.
```

## Done criteria

- `./mvnw test` is green.
- You can explain, in one sentence each: why the signature comparison must be
  constant-time, and why the idempotency lock must be a single atomic
  operation.
