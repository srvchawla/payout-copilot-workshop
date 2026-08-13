# Student Lab Guide

Goal: implement a secure, idempotent payout webhook endpoint using Copilot
Plan Mode, Custom Instructions, and Agent Mode — until `./mvnw test` is fully
green.

## 0. Confirm your environment

```bash
./scripts/verify-env.sh
./scripts/dev-up.sh
```

Start the app in one terminal and leave it running:

```bash
./mvnw spring-boot:run
```

In a second terminal, hit the health endpoint:

```bash
curl -s http://localhost:8080/actuator/health
```

Or just open [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)
in your browser for a quick visual check.

You should see `"status":"UP"` with `db` and `redis` both `UP` - this confirms
Postgres, Redis, and the app itself are all wired up correctly *before* you
touch any code. Stop the app (Ctrl+C) once confirmed, then run the test suite:

```bash
./mvnw test
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

## 5. Debugging lab

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
