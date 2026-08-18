#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

CONFIGURED_SECRET=$(awk '
	$0 == "webhook:" { in_webhook = 1; next }
	in_webhook && /^[^[:space:]]/ { exit }
	in_webhook && /^[[:space:]]+secret:/ {
		sub(/^[[:space:]]*secret:[[:space:]]*/, "")
		gsub(/^['\''"]|['\''"]$/, "")
		print
		exit
	}
' "$REPO_ROOT/src/main/resources/application.yml")
SECRET=${WEBHOOK_SECRET:-$CONFIGURED_SECRET}
EVENT_ID=${EVENT_ID:-evt-manual-$(date +%s)-$$}
ACCOUNT_ID=acct-manual-1
STARTING_BALANCE=100.0000
EXPECTED_BALANCE=125.0000
WEBHOOK_URL=http://localhost:8080/webhooks/payout-status

fail() {
	echo "FAIL - $1" >&2
	exit 1
}

query_balance() {
	docker compose exec -T postgres psql \
		-U workshop \
		-d payout_workshop \
		-Atc "SELECT balance FROM account_balance WHERE account_id = '$ACCOUNT_ID';" \
		| tr -d '[:space:]'
}

send_webhook() {
	curl -sS -o /dev/null -w '%{http_code}' "$WEBHOOK_URL" \
		-H 'Content-Type: application/json' \
		-H "Payout-Transmission-Sig: $SIGNATURE" \
		--data-raw "$BODY"
}

wait_for_balance() {
	local expected=$1
	local attempts=0
	local balance

	while [ "$attempts" -lt 50 ]; do
		balance=$(query_balance)
		if [ "$balance" = "$expected" ]; then
			return 0
		fi
		sleep 0.1
		attempts=$((attempts + 1))
	done

	fail "balance did not reach $expected; current balance is ${balance:-missing}"
}

command -v docker >/dev/null 2>&1 || fail "docker is required"
command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v openssl >/dev/null 2>&1 || fail "openssl is required"
command -v od >/dev/null 2>&1 || fail "od is required"
[ -n "$SECRET" ] || fail "webhook.secret is not configured"

cd "$REPO_ROOT"

echo "Restarting the workshop stack from the current source..."
./scripts/dev-down.sh
./scripts/dev-up.sh

echo "Seeding $ACCOUNT_ID with a $STARTING_BALANCE USD balance..."
docker compose exec -T postgres psql \
	-U workshop \
	-d payout_workshop \
	-v ON_ERROR_STOP=1 \
	-c "INSERT INTO account_balance (account_id, currency, balance, version)
VALUES ('$ACCOUNT_ID', 'USD', $STARTING_BALANCE, 0)
ON CONFLICT (account_id)
DO UPDATE SET balance = EXCLUDED.balance, currency = EXCLUDED.currency, version = 0;"

BODY=$(cat <<EOF
{
  "eventId": "$EVENT_ID",
  "payoutId": "payout-manual-001",
  "accountId": "$ACCOUNT_ID",
  "status": "COMPLETED",
  "amount": 25.00,
  "currency": "USD",
  "occurredAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
)

SIGNATURE=$(printf '%s' "$BODY" \
	| openssl dgst -sha256 -hmac "$SECRET" -binary \
	| od -An -vtx1 \
	| tr -d ' \n')

echo
echo "Webhook payload:"
printf '%s\n' "$BODY"

echo
echo "Sending the first delivery..."
HTTP_STATUS=$(send_webhook)
[ "$HTTP_STATUS" = "200" ] || fail "first delivery returned HTTP $HTTP_STATUS"
wait_for_balance "$EXPECTED_BALANCE"
echo "OK   - first delivery changed the balance to $EXPECTED_BALANCE"

echo "Sending the duplicate delivery with event ID $EVENT_ID..."
HTTP_STATUS=$(send_webhook)
[ "$HTTP_STATUS" = "200" ] || fail "duplicate delivery returned HTTP $HTTP_STATUS"
sleep 1

FINAL_BALANCE=$(query_balance)
[ "$FINAL_BALANCE" = "$EXPECTED_BALANCE" ] \
	|| fail "duplicate delivery changed the balance to $FINAL_BALANCE"
echo "OK   - duplicate delivery left the balance at $FINAL_BALANCE"

IDEMPOTENCY_KEY_EXISTS=$(docker compose exec -T redis redis-cli \
	EXISTS "payout-webhook:processed:$EVENT_ID" | tr -d '[:space:]')
[ "$IDEMPOTENCY_KEY_EXISTS" = "1" ] || fail "Redis idempotency key was not found"
echo "OK   - Redis contains the idempotency key for $EVENT_ID"

echo
docker compose exec -T postgres psql \
	-U workshop \
	-d payout_workshop \
	-c "SELECT id, account_id, currency, balance, version
FROM account_balance
WHERE account_id = '$ACCOUNT_ID';"

echo "Manual webhook verification passed."
echo "Services remain running; stop them with ./scripts/dev-down.sh"
