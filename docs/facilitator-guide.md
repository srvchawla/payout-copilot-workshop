# Facilitator Guide (90 min)

**Scenario**: a marketplace triggers a payout status webhook
(`PENDING → COMPLETED`). The service must verify the signature, apply FX
conversion if needed, and update the balance idempotently.

## Run of Show

| Time | Segment |
|---|---|
| 0:00–0:10 | Welcome, series overview, environment sanity check |
| 0:10–0:20 | Scenario briefing |
| 0:20–0:35 | Plan Mode — architecting the webhook feature |
| 0:35–0:40 | Custom Instructions walkthrough |
| 0:40–0:65 | Agent Mode hands-on lab (make `./mvnw test` pass) |
| 0:65–0:80 | Agent Mode debugging lab (seeded bug) |
| 0:80–0:88 | Recap + preview of CLI / coding agent / code review agent |
| 0:88–0:90 | Feedback + discussion of follow-up topics |

## 0:00–0:10 — Environment Check

Have everyone run, in order:
```bash
./scripts/verify-env.sh
./scripts/dev-up.sh
```

Then confirm the app itself boots and talks to Postgres/Redis correctly
*before* touching any code:
```bash
./mvnw spring-boot:run   # separate terminal, leave running
curl -s http://localhost:8080/actuator/health
```
Or open http://localhost:8080/actuator/health in a browser for a visual check.
Expect `"status":"UP"` with `db` and `redis` both `UP`. Stop with Ctrl+C, then:
```bash
./mvnw test
```
`test` should FAIL — that's expected (`webhook`/`ledger` classes throw
`UnsupportedOperationException`). This is the RED state the lab starts from.
Anyone still failing `verify-env.sh` pairs up with a neighbor rather than
blocking the room.

## 0:10–0:20 — Scenario Briefing

Walk through, verbally, without code:
- Why webhooks need signature verification (untrusted network caller).
- Why "at-least-once" delivery means the handler must be idempotent.
- Why balance updates shouldn't happen on the request thread.

Point at `WebhookControllerTest` on screen — explain that these four tests
**are the spec**: `rejectsRequestWithInvalidSignature`,
`acceptsValidSignatureAndCreditsBalance`, `convertsCurrencyBeforeCrediting`,
`duplicateDeliveryIsAppliedExactlyOnce`.

## 0:20–0:35 — Plan Mode

Everyone opens Copilot Chat → **Plan Mode** with:

```
We need to implement WebhookController, SignatureVerifier, IdempotencyService,
PayoutEventListener, and LedgerService so that WebhookControllerTest passes.
Propose the sequence of changes and flag any design risks before writing code.
```

Discuss as a group: does the plan call out the atomic Redis lock? Does it
flag the async boundary? This is the "catch design gaps before code exists"
moment — reinforce that Plan Mode output should be read, not rubber-stamped.

## 0:35–0:40 — Custom Instructions

Open `.github/instructions/payout-webhook.instructions.md` on screen. Explain
`applyTo` scoping — this file only auto-applies to `*Webhook*.java` and files
under `webhook/`. Ask: why doesn't it also cover `LedgerService`? (Answer:
it's ledger/FX correctness, not webhook security — a good moment to discuss
splitting instruction files by concern rather than one giant file.)

## 0:40–0:65 — Agent Mode Hands-On Lab

Everyone opens Agent Mode with:

```
Implement SignatureVerifier, IdempotencyService, PayoutEventListener,
WebhookController, and LedgerService so that every test in
WebhookControllerTest passes. Run ./mvnw test after each change and keep
iterating until all tests are green. Do not modify the test file.
```

Circulate. Things to point out live if they come up:
- Agent Mode picking up the `applyTo` instructions unprompted (constant-time
  compare, atomic Redis lock, `@Async` dispatch).
- Agent Mode re-running `./mvnw test` and fixing based on failures.

Time-box to ~20 min of actual agent-running time; anyone stuck can pull the
facilitator or peek at a neighbor's diff. Target: all 4 tests green.

## 0:65–0:80 — Debugging Lab (Agent Mode)

Split the room into two groups (or let people pick). Once their own tests are
green, have them ask Agent Mode to **introduce** one of the seeded bugs below,
then find and fix it — this proves the debugging workflow works against
*their own* implementation, not a canned diff.

### Group A — Signature bypass bug
```
Refactor SignatureVerifier to compare the computed and provided signatures
using String#equals instead of a constant-time comparison.
```
Then, in a fresh prompt:
```
Security review flagged a timing-attack risk in our signature comparison.
Find it and fix it, and add a test that would have caught this.
```

### Group B — Idempotency race condition
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

Facilitator note: both "introduce the bug" prompts are intentionally realistic
refactors a well-meaning engineer might make — use this to talk about why
code review + tests catch what looks like reasonable code.

## 0:80–0:88 — Recap & Preview

Preview only (no hands-on yet):
- **Copilot CLI** (`copilot` in the terminal) — triaging failing CI logs,
  scripting, git operations without opening the IDE.
- **Copilot coding agent (cloud)** — assign a GitHub issue to Copilot, it
  opens a PR autonomously.
- **Copilot Code Review** — request Copilot as a PR reviewer on the branch
  they just built; walk through inline comments live if time allows.

## 0:88–0:90 — Feedback

Quick pulse check + written feedback form. Share any follow-up reading
(such as FX resilience / Resilience4j) after the session if needed.

## Facilitator Safety Notes

- Keep a `git stash` / fresh-clone fallback ready in case someone's Agent
  Mode run leaves the tree in a broken state they can't recover from.
- Seed bugs via prompts (above), not committed branches — this keeps the
  exercise valid against each attendee's own (slightly different) solution.
- Confirm `dockerd (moby)` engine before the session; `containerd` will break
  Testcontainers/Docker Compose auto-config for several attendees.
