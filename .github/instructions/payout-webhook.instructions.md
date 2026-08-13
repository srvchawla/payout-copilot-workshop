---
applyTo: "**/*Webhook*.java,**/webhook/**/*.java"
---
# Webhook Security & Idempotency Standards

- Never accept a webhook payload without verifying the `Payout-Transmission-Sig`
  header using HMAC-SHA256 over the raw request body against the configured
  shared secret (`WebhookProperties#getSecret()`). Reject with HTTP 401 if
  verification fails or the header is missing/blank.
- Compare signatures with a constant-time comparison (`MessageDigest.isEqual`).
  Never use `String#equals`, `==`, or `Arrays.equals` for signature comparison.
- Every webhook handler must be idempotent: derive an idempotency key from the
  event's unique ID and acquire a Redis-backed lock in a single ATOMIC
  operation (e.g. `setIfAbsent` with a TTL). Never implement idempotency as a
  separate "check" followed by a separate "set" — that reintroduces the race
  condition this rule exists to prevent.
- Do not perform balance updates synchronously in the controller. Publish an
  event/message and handle it asynchronously; the controller must return 200
  within milliseconds regardless of downstream processing time.
- All monetary values must use `BigDecimal`, never `float`/`double`.
- Log signature failures and duplicate-delivery events at WARN with the event
  ID / transaction ID, but never log the raw secret, the full signature
  header, or any customer PII.
- When writing or modifying tests in this area, do not weaken existing
  assertions to make a test pass — fix the implementation instead.
