# Proposal: Replace In-Process Spring Events with a Transactional Outbox

Status: proposed (design only — no production code changes in this document)

## 1. Context

Today the webhook ingestion path is:

1. `WebhookController` verifies the HMAC signature.
2. `IdempotencyService` acquires a Redis `SET NX` lock keyed on `eventId`.
3. The controller publishes a `PayoutStatusReceivedEvent`.
4. `PayoutEventListener` handles it `@Async` and calls `LedgerService`, which
   converts currency via `ConversionService` and credits `account_balance`.

`ApplicationEventPublisher` with `@Async` is an **in-memory, in-process**
hand-off. That gives us a fast 200 response, but it has three structural
weaknesses:

- **Silent loss on crash.** Once the controller returns 200, the event lives
  only in the executor's queue. A pod restart, deploy, OOM kill, or graceful
  shutdown timeout drops every queued event. The provider already got a 200,
  so it will never redeliver.
- **Loss is invisible.** There is no durable record that an event was accepted
  but never applied, so we cannot detect, alert on, or replay the gap.
- **Redis lock hides the loss.** `IdempotencyService` marks the `eventId` as
  seen *before* ledger work happens, with a 24h TTL. If the async listener
  dies, a provider retry within the TTL is swallowed as a "duplicate" — a
  permanently lost credit.

The result is **at-most-once** delivery to the ledger on a path that handles
money. We want **at-least-once delivery plus effectively-once application**.

## 2. Proposal

Introduce a **transactional outbox**: the webhook request writes the accepted
event into an `outbox_event` table in the *same database transaction* as any
other state it touches. A separate poller reads unprocessed rows and dispatches
them to the ledger handler. Because the write is transactional and the poller
is durable, an event is either fully recorded or fully absent — never accepted
but forgotten.

```mermaid
sequenceDiagram
    participant Provider as Payout Provider
    participant Controller as WebhookController
    participant PG as Postgres
    participant Poller as Outbox Poller
    participant Ledger as LedgerService

    Provider->>Controller: POST payload + signature
    Controller->>Controller: Verify HMAC
    Controller->>PG: BEGIN; INSERT outbox_event (event_id UNIQUE); COMMIT
    alt Duplicate event_id
        PG-->>Controller: unique violation
        Controller-->>Provider: 200 OK (already accepted)
    else First delivery
        Controller-->>Provider: 200 OK
    end

    loop every poll interval
        Poller->>PG: SELECT ... WHERE status='PENDING' AND next_attempt_at<=now()<br/>ORDER BY id FOR UPDATE SKIP LOCKED LIMIT N
        Poller->>Ledger: handle(payload)
        Ledger->>PG: credit account_balance
        Poller->>PG: mark PROCESSED (or schedule retry / DEAD)
    end
```

### 2.1 Delivery guarantees

| Stage | Guarantee | Mechanism |
| --- | --- | --- |
| Provider → controller | at-least-once (provider retries on non-2xx) | HTTP semantics |
| Controller → outbox | exactly one row per `event_id` | `UNIQUE (event_id)` + single transaction |
| Outbox → ledger handler | at-least-once | durable rows, retried until PROCESSED or DEAD |
| Ledger effect | effectively-once | handler idempotency (§2.5) |

The system is **at-least-once end to end with idempotent application**.
Exactly-once delivery is not achievable across a process boundary; exactly-once
*effect* is, and that is what we target.

The critical inversion versus today: the dedupe marker becomes the durable
outbox row itself (committed with the work item), not a Redis key with a TTL
that can expire or be set before the work is durable.

### 2.2 Schema

```sql
CREATE TABLE outbox_event (
    id              BIGSERIAL PRIMARY KEY,
    event_id        VARCHAR(100) NOT NULL,
    event_type      VARCHAR(80)  NOT NULL,   -- e.g. 'payout.status'
    aggregate_id    VARCHAR(100) NOT NULL,   -- payoutId, for tracing/partitioning
    payload         JSONB        NOT NULL,   -- verified webhook body
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING|PROCESSING|PROCESSED|DEAD
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,
    CONSTRAINT uq_outbox_event_id UNIQUE (event_id)
);

-- Drives the poll query; partial index keeps it small as the table grows.
CREATE INDEX idx_outbox_due
    ON outbox_event (next_attempt_at, id)
    WHERE status = 'PENDING';

-- Supports retention/archival sweeps.
CREATE INDEX idx_outbox_processed_at
    ON outbox_event (processed_at)
    WHERE status = 'PROCESSED';
```

Notes:

- `payload` stores the parsed, signature-verified body. Monetary fields stay
  numeric strings in JSON and are deserialised to `BigDecimal` — never
  `float`/`double`.
- The payload is customer data: it must never be written to logs, and the table
  falls under the same retention rules as the ledger.
- Schema is introduced as a versioned migration (§2.7), not `ddl-auto: update`.

### 2.3 Write path

`WebhookController` keeps its current shape (thin, returns in milliseconds):

1. Verify signature → 401 on failure (unchanged).
2. Parse the raw body.
3. In one transaction, `INSERT INTO outbox_event (...)`.
   - On unique violation of `event_id`, treat as a duplicate delivery: log at
     WARN with the event/payout ID and return 200.
4. Return 200.

The insert is a single indexed write — comfortably within the millisecond
budget the webhook instructions require. No ledger or FX work happens on the
request thread.

**Where Redis fits after this change.** The outbox unique constraint is the
authoritative dedupe. Redis becomes an optional *fast-path filter* that lets us
short-circuit obvious duplicate storms without touching Postgres. If we keep
it, the ordering must be "Redis miss → attempt insert → DB decides", and a
Redis outage must degrade to the DB path rather than reject traffic. Dropping
Redis from the ingest path entirely is also acceptable and simpler; that
decision can be made independently of this proposal.

### 2.4 Polling and dispatch

A scheduled poller (`@Scheduled(fixedDelay = 200ms)`, or a short blocking loop)
claims a batch:

```sql
SELECT * FROM outbox_event
 WHERE status = 'PENDING' AND next_attempt_at <= now()
 ORDER BY id
 FOR UPDATE SKIP LOCKED
 LIMIT 100;
```

- `FOR UPDATE SKIP LOCKED` makes the poller safe to run on every replica: two
  instances never claim the same row, and a slow row never blocks the queue.
- Batch size and interval are configurable (`outbox.poll-interval`,
  `outbox.batch-size`, `outbox.max-attempts`).
- Each row is handled in its own transaction: apply the ledger effect, then
  mark `PROCESSED` with `processed_at = now()`. Both commit together, so a
  crash mid-handler rolls back to `PENDING` and the row is retried.
- **Ordering:** `ORDER BY id` gives approximate global ordering, but concurrent
  workers mean no strict ordering guarantee. Ledger credits are commutative, so
  this is acceptable today. If a future event type needs per-payout ordering,
  add a claim predicate that skips rows whose `aggregate_id` has an older
  unfinished row.
- **Latency:** polling adds up to one poll interval of latency versus the
  in-memory listener. At 200ms this is invisible next to the provider's own
  delivery jitter. A Postgres `LISTEN/NOTIFY` nudge on insert can cut it
  further if measurement ever justifies it.

### 2.5 Handler idempotency

Because dispatch is at-least-once, the ledger handler must tolerate replays
(crash after credit, before the `PROCESSED` commit). Options, in order of
preference:

1. **Ledger entry table with `UNIQUE (event_id)`** — the credit and its
   provenance row are written in one transaction; a replay hits the constraint
   and becomes a no-op. This also gives us an auditable ledger, which a payout
   system wants regardless.
2. Same-transaction status update — narrows but does not close the window if
   the ledger ever moves to a different datastore.

Option 1 is the recommendation and is the only material addition this proposal
makes beyond the outbox itself.

### 2.6 Retries, failure recovery, and observability

- **Classification.** Transient failures (DB deadlock, FX provider timeout,
  optimistic-lock conflict on `AccountBalance`) → retry. Permanent failures
  (unknown currency, unknown account, unparseable payload) → straight to
  `DEAD`; retrying cannot help.
- **Backoff.** `attempts++`, then
  `next_attempt_at = now() + min(base * 2^attempts, cap)` with jitter
  (e.g. base 1s, cap 5m). `last_error` records a redacted message — never the
  payload, secret, or PII.
- **Dead-letter.** After `outbox.max-attempts` (default 10) the row becomes
  `DEAD`. `DEAD` rows are never auto-retried; they are alerted on and replayed
  by an operator action (reset to `PENDING`, `attempts = 0`) once the root
  cause is fixed. The event is still fully preserved, which is precisely what
  the current design cannot offer.
- **Stuck `PROCESSING` rows.** If a worker dies holding a claim, the row's
  transaction aborts and the row returns to `PENDING` automatically. A
  belt-and-braces sweeper resets rows stuck in `PROCESSING` beyond a lease
  timeout for the case where status is committed separately.
- **Metrics/alerts.** `outbox.pending.count`, `outbox.oldest.pending.age`,
  `outbox.dead.count`, `outbox.dispatch.duration`. Alert on oldest-pending age
  and any non-zero `DEAD` count — those are the signals that were structurally
  unavailable before.
- **Retention.** Archive/delete `PROCESSED` rows after N days (default 30) via
  a scheduled sweep, so the hot table and its partial index stay small.

### 2.7 Migration plan

Introduce Flyway (or Liquibase) and move off `ddl-auto: update`, which cannot
express this safely. Then:

1. **Ship the schema.** Migration creates `outbox_event` (+ the ledger entry
   table from §2.5). No behaviour change; nothing reads or writes it yet.
2. **Dual-write, single-dispatch.** The controller writes the outbox row *and*
   still publishes the Spring event. The poller runs in "shadow" mode: it
   claims rows and marks them `PROCESSED` without applying ledger effects, so
   we can measure throughput, lag, and contention against real traffic with
   zero risk.
3. **Cut over dispatch.** Behind a flag (`outbox.dispatch.enabled`), the poller
   becomes the applier and the `@Async` listener is disabled. Roll out per
   environment. Rollback is flipping the flag back.
4. **Remove the in-process path.** Delete `PayoutEventListener`'s `@Async`
   `@EventListener` wiring and the `PayoutStatusReceivedEvent` publish once
   step 3 has been stable for a full release cycle.
5. **Reconsider the Redis lock** (§2.3) as a separate, smaller change.

Backfill is not required: in-flight events at cutover are still covered by the
old path in step 3's rollout window, and any genuinely lost pre-migration event
was already unrecoverable.

## 3. Alternatives considered

- **Keep `@Async` and add a bounded, persistent executor queue.** Cheaper, but
  any at-least-once story still needs a durable store — which is the outbox,
  just less explicit and without replay tooling.
- **Publish directly to Kafka/SQS from the controller.** Reintroduces the dual-
  write problem (DB commit and broker publish can diverge). The outbox is the
  standard fix for exactly this, and remains the correct first step even if we
  later add a broker: the poller simply publishes outbox rows to it (or is
  replaced by CDC/Debezium reading the same table).
- **Provider-side retries only.** Requires returning non-2xx when downstream
  work fails, which forces synchronous ledger work in the request path — the
  opposite of what the webhook standards require, and it makes ingest
  availability depend on ledger availability.

## 4. Trade-offs

| Gain | Cost |
| --- | --- |
| No silent event loss on restart/deploy | One extra table + migration tooling |
| Replayable, auditable event history | Up to one poll interval of added latency |
| Failures become visible and alertable | Poller lifecycle, retention sweep to operate |
| Safe horizontal scaling (`SKIP LOCKED`) | Handler must be genuinely idempotent |
| No broker dependency introduced | Poll load on Postgres (bounded by partial index) |

## 5. Open questions

- Do we adopt the ledger-entry table (§2.5) now, or accept the narrower
  same-transaction window for the first release?
- Does Redis stay on the ingest path as a fast-path filter, or is the outbox
  unique constraint sufficient on its own?
- Flyway or Liquibase, given `ddl-auto: update` must go either way?
- Target for `outbox.max-attempts` and the dead-letter alert threshold —
  driven by the provider's own retry window.
