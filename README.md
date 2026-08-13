# Payout Copilot Workshop — Payout Webhook Service

A hands-on GitHub Copilot workshop for backend engineers
(Java 17 / Spring Boot 3) built around a realistic scenario: ingesting payout
status webhooks securely and idempotently.

## Prerequisites

Complete these steps before the workshop:

- Java 17 or later installed and available on `PATH`.
- Rancher Desktop installed and running with the **`dockerd (moby)`** container
  engine selected.
- Docker and Docker Compose available: `docker version` and
  `docker compose version` should work.
- VS Code with the GitHub Copilot and GitHub Copilot Chat extensions installed.
- An active GitHub Copilot Business or Enterprise seat signed in through the
  GitHub organization used for the workshop.
- GitHub CLI installed and authenticated with `gh auth status`.
- Copilot CLI installed if participating in the CLI exercises.
- At least 4 GB of free memory for the local PostgreSQL and Redis containers.

Run the repository readiness check after cloning:

```bash
./scripts/verify-env.sh
```

## Learning Topics

- Secure and idempotent webhook ingestion
- Async processing, currency conversion, resilience, and retries
- Compliance rules and larger multi-file Agent Mode tasks
- GitHub workflow with Copilot Cloud Agent and Copilot Code Review

See [`docs/`](docs) for the facilitator guide, student lab guide, and setup
checklist.

## Copilot Concepts Taught

Mark an item complete by changing `[ ]` to `[x]` in this file as you walk
through the lab.

- [ ] Plan the webhook flow with **Plan Mode**: define the files, boundaries,
  and risks before coding.
- [ ] Apply webhook guardrails with **Custom Instructions**: enforce signature
  verification, atomic idempotency, and safe logging conventions.
- [ ] Implement the webhook with **Agent Mode in the IDE**: build and test the
  multi-file feature from the executable specification.
- [ ] Repair a seeded race condition with **Agent Mode for debugging**:
  reproduce duplicate processing and fix it with a concurrent test.
- [ ] Diagnose failed resilience tests with **Copilot CLI**: investigate logs
  and make targeted fixes from the terminal.
- [ ] Extend the compliance rules engine with **reusable prompts and custom
  agents**: coordinate a larger feature across packages and tests.
- [ ] Deliver a reconciliation feature with **Copilot Cloud Agent**: turn a
  GitHub issue into a pull request with tests.
- [ ] Review the pull request with **Copilot Code Review**: catch security,
  correctness, and test-coverage gaps.

## Exercise Map

### Confirm the environment

**Open:** [`scripts/verify-env.sh`](scripts/verify-env.sh),
[`docker-compose.yml`](docker-compose.yml), and
[`src/main/resources/application.yml`](src/main/resources/application.yml).

**Look for:** Java, Docker, PostgreSQL, Redis, and the application
configuration being available.

### Read the executable specification

**Open:** [`WebhookControllerTest.java`](src/test/java/com/payout/workshop/payout/webhook/WebhookControllerTest.java).

**Look for:** the four tests defining invalid-signature rejection, successful
crediting, FX conversion, and duplicate-delivery behavior. Do not edit this
file during the lab.

### Plan the implementation

**Open:** [`WebhookController.java`](src/main/java/com/payout/workshop/payout/webhook/WebhookController.java),
[`SignatureVerifier.java`](src/main/java/com/payout/workshop/payout/webhook/SignatureVerifier.java),
[`IdempotencyService.java`](src/main/java/com/payout/workshop/payout/webhook/IdempotencyService.java),
[`PayoutEventListener.java`](src/main/java/com/payout/workshop/payout/webhook/PayoutEventListener.java),
and [`LedgerService.java`](src/main/java/com/payout/workshop/payout/ledger/LedgerService.java).

**Look for:** the `TODO(workshop)` blocks describing the contracts Copilot
must implement.

### Apply the guardrails

**Open:** [`.github/copilot-instructions.md`](.github/copilot-instructions.md)
and [`.github/instructions/payout-webhook.instructions.md`](.github/instructions/payout-webhook.instructions.md).

**Look for:** repository-wide conventions and path-specific webhook security
rules that should shape Copilot's suggestions automatically.

### Implement and validate the webhook

**Open:** the five TODO files listed in the planning step, then run
`./mvnw test`.

**Look for:** signature verification before parsing, one atomic Redis
idempotency operation, asynchronous event handling, and currency conversion
using `BigDecimal`.

### Investigate a seeded defect

**Open:** [`SignatureVerifier.java`](src/main/java/com/payout/workshop/payout/webhook/SignatureVerifier.java)
or [`IdempotencyService.java`](src/main/java/com/payout/workshop/payout/webhook/IdempotencyService.java).

**Look for:** Agent Mode reproducing a timing-safe comparison issue or a
check-then-set race, adding a regression test, and repairing the defect.

## Developer Walk-Through

Follow this sequence in order. The branch named `main` is the student starting
point; the `solution` branch is a facilitator reference after the exercise.

1. Run `./scripts/verify-env.sh`, then `./scripts/dev-up.sh`.
2. Start the service with `./mvnw spring-boot:run` and open
  [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health).
  Continue only when the application, PostgreSQL, and Redis report `UP`.
3. Run `./mvnw test` and observe the intentional RED state from the TODO
  implementations.
4. Read `WebhookControllerTest.java` and list the behavior each test requires.
5. Use **Plan Mode** to propose the implementation sequence and challenge its
  risks before allowing code changes.
6. Open the two instruction files and confirm which rules apply to webhook
  classes.
7. Use **Agent Mode** to implement the TODOs without changing the tests.
8. Re-run `./mvnw test` until all tests pass, reviewing the generated diff as
  you go.
9. Introduce one seeded defect, use Agent Mode to reproduce it, and verify the
  regression test before accepting the fix.
10. Use the remaining Copilot capabilities when they fit the session: Copilot
   CLI for terminal investigation, Cloud Agent for an issue-to-PR task, and
   Copilot Code Review for the resulting pull request.

## Stack

- Java 17, Spring Boot 3.3, Maven wrapper (`./mvnw`)
- Postgres + Redis via `docker-compose.yml`, run through **Rancher Desktop**
  with the **`dockerd` (moby)** container engine
- Testcontainers-backed integration tests (the tests ARE the spec — see
  [`WebhookControllerTest`](src/test/java/com/payout/workshop/payout/webhook/WebhookControllerTest.java))

## Quick Start

```bash
./scripts/verify-env.sh   # confirms Java/Docker/Copilot CLI are ready
./scripts/dev-up.sh       # starts Postgres + Redis
```

Verify the app itself boots before touching any code:
```bash
./mvnw spring-boot:run           # separate terminal, leave running
curl -s http://localhost:8080/actuator/health   # expect "status":"UP"
```
Or open http://localhost:8080/actuator/health directly in a browser.

Then run the test suite:
```bash
./mvnw test               # expect RED: webhook/ledger classes are TODO stubs
```

The lab is to make `./mvnw test` pass using Copilot Plan Mode, Custom
Instructions, and Agent Mode — see
[`docs/lab-guide.md`](docs/lab-guide.md).

## Repo Layout

```
src/main/java/com/payout/workshop/payout/
  webhook/   WebhookController, SignatureVerifier, IdempotencyService, PayoutEventListener  (TODO stubs — the lab)
  ledger/    AccountBalance, AccountRepository, LedgerService                                (LedgerService is a TODO stub)
  fx/        ConversionService                                                               (fully implemented stub, not the lesson)
.github/
  copilot-instructions.md                    repo-wide conventions
  instructions/payout-webhook.instructions.md path-scoped security/idempotency rules
docs/
  setup.md                    pre-work checklist sent to attendees
  facilitator-guide.md       run-of-show, timing, prompts, debug injection
  lab-guide.md               student-facing step-by-step lab
```
