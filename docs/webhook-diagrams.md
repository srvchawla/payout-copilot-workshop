# Webhook Processing Diagrams

## 1) Simplified flow

```mermaid
flowchart LR
    A[Provider sends webhook] --> B[Controller validates signature]
    B --> C{Valid?}
    C -- No --> D[401 Unauthorized]
    C -- Yes --> E[Check Redis idempotency key]
    E --> F{Already processed?}
    F -- Yes --> G[Return 200, do nothing]
    F -- No --> H[Publish async event]
    H --> I[Async worker updates ledger]
    I --> J[Return 200 OK quickly]
```

## 2) Detailed webhook flow

```mermaid
sequenceDiagram
    participant Provider as Payout Provider
    participant Controller as Webhook Controller
    participant Redis
    participant Worker as Async Listener
    participant Postgres

    Provider->>Controller: POST payload + signature
    Controller->>Controller: Verify HMAC signature

    alt Invalid signature
        Controller-->>Provider: 401 Unauthorized
    else Valid signature
        Controller->>Redis: SET eventId NX with TTL
        alt Event already exists
            Redis-->>Controller: Key already present
            Controller-->>Provider: 200 OK
        else First delivery
            Redis-->>Controller: Key acquired
            Controller->>Worker: Publish PayoutStatusReceivedEvent
            Controller-->>Provider: 200 OK

            Worker->>Postgres: Load account by accountId
            Worker->>Worker: Convert amount to account currency if needed
            Worker->>Postgres: Update account_balance
        end
    end
```

## 3) Architecture view

```mermaid
flowchart TB
    subgraph External[External]
        P[Payment Provider]
    end

    subgraph App[Application]
        C[WebhookController]
        S[SignatureVerifier]
        I[IdempotencyService]
        E[Async Event Listener]
        L[LedgerService]
        FX[ConversionService]
    end

    subgraph Cache[Cache]
        R[(Redis)]
    end

    subgraph DB[Database]
        DBT[(Postgres\naccount_balance)]
    end

    P -->|HTTP POST + HMAC| C
    C --> S
    C --> I
    I -->|eventId + TTL| R

    C -->|publish event| E
    E --> L
    L --> FX
    L -->|read/write balance| DBT

    R -. dedupe / processed keys .-> I
    DBT -. account rows .-> L
```

## Why this flow matters

- Signature verification proves the request came from a trusted sender and was not tampered with.
- Redis deduplicates retries from at-least-once delivery by tracking a unique event ID with a TTL.
- The controller acknowledges the webhook quickly and does not block on slow ledger or FX work.
- The async listener performs the balance update safely and deterministically in the background.
