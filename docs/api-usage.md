# API Usage Guide

How to call the payout webhook endpoint by hand: build a payload, sign it with
HMAC-SHA256, send it from Bash, zsh, or PowerShell, and prove that a duplicate
delivery is ignored.

> The scripted end-to-end version of everything below is
> [`scripts/manual-webhook-test.sh`](../scripts/manual-webhook-test.sh).
> This guide is the manual, cross-shell equivalent.

## 1. Endpoint contract

| Item | Value |
| --- | --- |
| Method / path | `POST /webhooks/payout-status` |
| Base URL (local) | `http://localhost:8080` |
| Content type | `application/json` |
| Signature header | `Payout-Transmission-Sig` |
| Signature value | lowercase hex HMAC-SHA256 of the **raw request body** |
| `200 OK` | accepted (first delivery *or* duplicate delivery) |
| `401 Unauthorized` | missing, blank, or invalid signature |

Request body:

```json
{
  "eventId": "evt-1001",
  "payoutId": "payout-001",
  "accountId": "acct-usd-1",
  "status": "COMPLETED",
  "amount": 25.00,
  "currency": "USD",
  "occurredAt": "2026-01-01T12:00:00Z"
}
```

- `status` is one of `PENDING`, `COMPLETED`, `FAILED`; only `COMPLETED` moves
  the balance.
- `amount` is a decimal number — it is parsed into a `BigDecimal`.
- `eventId` is the idempotency key: replaying the same `eventId` must never
  move the balance twice.
- If `currency` differs from the account currency, the amount is converted by
  `ConversionService` before the ledger is credited.

## 2. Provide the shared secret

The service verifies signatures against `webhook.secret` from
[`src/main/resources/application.yml`](../src/main/resources/application.yml).
**Never paste a real secret into a document, a script, or your shell history.**
Export it as an environment variable instead, and use the workshop-only value
for local runs.

```bash
read -rs WEBHOOK_SECRET && export WEBHOOK_SECRET   # paste, then press Enter
```

```powershell
$env:WEBHOOK_SECRET = Read-Host -Prompt 'Webhook secret' -MaskInput   # PowerShell 7.1+
```

The signature must be computed over the **exact bytes** you send. Build the
body once, sign that string, and post that same string — reformatting or
re-serializing the JSON between signing and sending produces a `401`.

## 3. Send a signed webhook

### Bash

```bash
BODY='{"eventId":"evt-1001","payoutId":"payout-001","accountId":"acct-usd-1","status":"COMPLETED","amount":25.00,"currency":"USD","occurredAt":"2026-01-01T12:00:00Z"}'

SIGNATURE=$(printf '%s' "$BODY" \
  | openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" -binary \
  | od -An -vtx1 \
  | tr -d ' \n')

curl -sS -i http://localhost:8080/webhooks/payout-status \
  -H 'Content-Type: application/json' \
  -H "Payout-Transmission-Sig: $SIGNATURE" \
  --data-raw "$BODY"
```

`--data-raw` is important: plain `--data` strips newlines, which would change
the signed bytes.

### zsh

The Bash commands run unchanged in zsh. Two habits keep them portable:

```zsh
BODY='{"eventId":"evt-1002","payoutId":"payout-001","accountId":"acct-usd-1","status":"COMPLETED","amount":25.00,"currency":"USD","occurredAt":"2026-01-01T12:00:00Z"}'

SIGNATURE="$(printf '%s' "$BODY" \
  | openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" -binary \
  | od -An -vtx1 \
  | tr -d ' \n')"

curl -sS -i http://localhost:8080/webhooks/payout-status \
  -H 'Content-Type: application/json' \
  -H "Payout-Transmission-Sig: ${SIGNATURE}" \
  --data-raw "${BODY}"
```

- Always quote `"$BODY"` and `"$SIGNATURE"`; zsh does not word-split unquoted
  parameters the way Bash does, but quoting keeps the snippets identical
  across both shells.
- `setopt HIST_IGNORE_SPACE` and a leading space keep secret-bearing commands
  out of `~/.zsh_history`.

### PowerShell

```powershell
$body = '{"eventId":"evt-1003","payoutId":"payout-001","accountId":"acct-usd-1","status":"COMPLETED","amount":25.00,"currency":"USD","occurredAt":"2026-01-01T12:00:00Z"}'

$bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
$hmac = [System.Security.Cryptography.HMACSHA256]::new([System.Text.Encoding]::UTF8.GetBytes($env:WEBHOOK_SECRET))
try {
    $hash = $hmac.ComputeHash($bodyBytes)
    $signature = ([System.BitConverter]::ToString($hash) -replace '-', '').ToLowerInvariant()
} finally {
    $hmac.Dispose()
}

Invoke-WebRequest -Uri 'http://localhost:8080/webhooks/payout-status' `
    -Method Post `
    -ContentType 'application/json' `
    -Headers @{ 'Payout-Transmission-Sig' = $signature } `
    -Body $bodyBytes
```

Passing `$bodyBytes` (not `$body`) guarantees PowerShell sends exactly the
bytes that were signed, regardless of the console encoding.

### Expected responses

| Scenario | Response |
| --- | --- |
| Valid signature, new `eventId` | `200 OK`, balance credited asynchronously |
| Valid signature, replayed `eventId` | `200 OK`, balance unchanged |
| Tampered body or wrong secret | `401 Unauthorized` |
| Missing `Payout-Transmission-Sig` | `401 Unauthorized` |

To see the rejection path, resend the request above with a bad signature:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:8080/webhooks/payout-status \
  -H 'Content-Type: application/json' \
  -H 'Payout-Transmission-Sig: not-a-real-signature' \
  --data-raw "$BODY"   # expect 401
```

## 4. Verify duplicate delivery (idempotency)

Providers retry. The same `eventId` must be accepted with `200` but must move
the balance only once.

1. Seed a known balance:

   ```bash
   docker compose exec -T postgres psql -U workshop -d payout_workshop -v ON_ERROR_STOP=1 \
     -c "INSERT INTO account_balance (account_id, currency, balance, version)
   VALUES ('acct-usd-1', 'USD', 100.0000, 0)
   ON CONFLICT (account_id)
   DO UPDATE SET balance = EXCLUDED.balance, currency = EXCLUDED.currency, version = 0;"
   ```

2. Send the signed request from step 3 **twice, byte-for-byte identically**
   (same `BODY`, same `SIGNATURE`). Both calls must return `200`.

3. Confirm the balance moved exactly once — `100.0000 + 25.00` = `125.0000`:

   ```bash
   docker compose exec -T postgres psql -U workshop -d payout_workshop \
     -Atc "SELECT balance FROM account_balance WHERE account_id = 'acct-usd-1';"
   ```

   In PowerShell:

   ```powershell
   docker compose exec -T postgres psql -U workshop -d payout_workshop `
     -Atc "SELECT balance FROM account_balance WHERE account_id = 'acct-usd-1';"
   ```

   Processing is asynchronous, so retry the query for a second or two before
   concluding the first delivery failed.

4. Confirm the idempotency key exists in Redis (it carries the configured
   `webhook.idempotency-ttl-hours` TTL):

   ```bash
   docker compose exec -T redis redis-cli EXISTS "payout-webhook:processed:evt-1001"
   docker compose exec -T redis redis-cli TTL    "payout-webhook:processed:evt-1001"
   ```

A duplicate delivery is also logged at `WARN` with the event ID. If the balance
reaches `150.0000`, idempotency is broken — see
[`WebhookControllerTest`](../src/test/java/com/payout/workshop/payout/webhook/WebhookControllerTest.java),
which asserts the same behaviour automatically.

## 5. Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| `401` on a request you signed | Body changed after signing (pretty-printing, trailing newline, `--data` instead of `--data-raw`) or `WEBHOOK_SECRET` does not match `webhook.secret` |
| `401` with an empty signature | `WEBHOOK_SECRET` is unset in the current shell |
| `200` but no balance change | Account row missing for `accountId`, or `status` is not `COMPLETED` |
| `500` / `UnsupportedOperationException` | The lab stubs are still unimplemented — see [`docs/lab-guide.md`](lab-guide.md) |
| Connection refused | The stack is not running — `./scripts/dev-up.sh`, then check `/actuator/health` |
