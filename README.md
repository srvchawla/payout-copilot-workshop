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

| Exercise | Backend outcome | Copilot capability |
|---|---|---|
| Plan the webhook flow | Define the files, boundaries, and risks before coding | **Plan Mode** |
| Apply webhook guardrails | Enforce signature verification, atomic idempotency, and safe logging conventions | **Custom Instructions** |
| Implement the webhook | Build and test the multi-file feature from the executable specification | **Agent Mode in the IDE** |
| Repair a seeded race condition | Reproduce duplicate processing and fix it with a concurrent test | **Agent Mode for debugging** |
| Diagnose failed resilience tests | Investigate logs and make targeted fixes from the terminal | **Copilot CLI** |
| Extend the compliance rules engine | Coordinate a larger feature across packages and tests | **Reusable prompts and custom agents** |
| Deliver a reconciliation feature | Turn a GitHub issue into a pull request with tests | **Copilot Cloud Agent** |
| Review the pull request | Catch security, correctness, and test-coverage gaps | **Copilot Code Review** |

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
