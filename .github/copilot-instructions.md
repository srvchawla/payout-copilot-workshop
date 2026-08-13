# Repository Instructions — Payout Webhook Service

This is a **Java 17 / Spring Boot 3** backend service used for a GitHub Copilot
workshop series. It models a payout-style payout ledger that ingests
asynchronous webhook status updates.

## Conventions

- Java 17, Spring Boot 3.3.x, Maven (`./mvnw`).
- Local infra (Postgres + Redis) runs via Rancher Desktop using the `dockerd`
  (moby) container engine — `docker compose up -d` before running the app or
  integration tests.
- Integration tests use Testcontainers; do not mock Postgres/Redis away in
  tests under `src/test/java/**/webhook/**` — they exist to prove real
  idempotency/locking behavior.
- Monetary values are always `BigDecimal`, never `float`/`double`.
- Prefer constructor injection; no field injection (`@Autowired` on fields).
- Keep controllers thin — no business logic in `@RestController` classes
  beyond request/response shaping and delegating to services.
- When a class/method is marked `TODO(workshop)`, that is intentional lab
  scaffolding for the current exercise — implement it, don't delete the TODO
  comment style used elsewhere without reason.
- Run `./mvnw test` after any change touching `webhook`, `ledger`, or `fx`
  packages before considering a task complete.
