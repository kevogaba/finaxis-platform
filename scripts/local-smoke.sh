#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_URL="${FINAXIS_APP_URL:-http://localhost:8081}"
KEYCLOAK_URL="${FINAXIS_KEYCLOAK_URL:-http://localhost:8080}"
REALM="${FINAXIS_KEYCLOAK_REALM:-finaxis}"
CLIENT_ID="${FINAXIS_KEYCLOAK_CLIENT_ID:-finaxis-platform}"
USERNAME="${FINAXIS_LOCAL_USERNAME:-local.admin}"
PASSWORD="${FINAXIS_LOCAL_PASSWORD:-local-admin}"
ORGANISATION_ID="${FINAXIS_LOCAL_ORGANISATION_ID:-22222222-2222-2222-2222-222222222222}"
BRANCH_ID="${FINAXIS_LOCAL_BRANCH_ID:-33333333-3333-3333-3333-333333333333}"
PLATFORM_ORGANISATION_ID="${FINAXIS_PLATFORM_ORGANISATION_ID:-00000000-0000-0000-0000-000000000000}"

cd "$ROOT_DIR"

json_field() {
  python3 -c 'import json,sys; print(json.load(sys.stdin)["'"$1"'"])'
}

new_uuid() {
  python3 -c 'import uuid; print(uuid.uuid4())'
}

docker compose up -d postgres redis rabbitmq keycloak

echo "Waiting for Keycloak realm..."
for _ in {1..90}; do
  if curl -fsS \
    "$KEYCLOAK_URL/realms/$REALM/.well-known/openid-configuration" >/dev/null; then
    break
  fi
  sleep 2
done

TOKEN_RESPONSE="$(
  curl -fsS \
    -X POST "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode "grant_type=password" \
    --data-urlencode "client_id=$CLIENT_ID" \
    --data-urlencode "username=$USERNAME" \
    --data-urlencode "password=$PASSWORD"
)"

ACCESS_TOKEN="$(json_field access_token <<<"$TOKEN_RESPONSE")"

if ! curl -fsS "$APP_URL/actuator/health" >/dev/null; then
  cat <<EOF
Infrastructure is ready and a Keycloak token was issued.

Start the application in another shell, then rerun this script:

  SPRING_PROFILES_ACTIVE=local ./gradlew bootRun

Expected app URL: $APP_URL
EOF
  exit 0
fi

echo "== Selecting tenant organisation and branch context =="

SELECT_ORG_RESPONSE="$(
  curl -fsS \
    -X POST "$APP_URL/api/v1/auth/select-organisation" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Idempotency-Key: $(new_uuid)" \
    -H 'Content-Type: application/json' \
    -d "{\"organisation_id\":\"$ORGANISATION_ID\"}"
)"

CONTEXT_TOKEN="$(json_field context_token <<<"$SELECT_ORG_RESPONSE")"

SELECT_BRANCH_RESPONSE="$(
  curl -fsS \
    -X POST "$APP_URL/api/v1/auth/select-branch" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "X-Active-Organisation-Context: $CONTEXT_TOKEN" \
    -H "Idempotency-Key: $(new_uuid)" \
    -H 'Content-Type: application/json' \
    -d "{\"branch_id\":\"$BRANCH_ID\"}"
)"

BRANCH_CONTEXT_TOKEN="$(json_field context_token <<<"$SELECT_BRANCH_RESPONSE")"

curl -fsS \
  "$APP_URL/api/v1/auth/me" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Active-Organisation-Context: $BRANCH_CONTEXT_TOKEN"
echo

echo "== Calling a representative paginated tenant endpoint (users) =="
curl -fsS \
  "$APP_URL/api/v1/tenant/users?page=0&size=5" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Active-Organisation-Context: $BRANCH_CONTEXT_TOKEN"
echo

echo "== Selecting platform organisation context for tenant onboarding =="

PLATFORM_SELECTION_RESPONSE_FILE="$(mktemp)"
PLATFORM_SELECTION_STATUS="$(
  curl -sS \
    -o "$PLATFORM_SELECTION_RESPONSE_FILE" \
    -w '%{http_code}' \
    -X POST "$APP_URL/api/v1/auth/select-organisation" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Idempotency-Key: $(new_uuid)" \
    -H 'Content-Type: application/json' \
    -d "{\"organisation_id\":\"$PLATFORM_ORGANISATION_ID\"}"
)"
SELECT_PLATFORM_RESPONSE="$(<"$PLATFORM_SELECTION_RESPONSE_FILE")"
rm -f "$PLATFORM_SELECTION_RESPONSE_FILE"

if [[ "$PLATFORM_SELECTION_STATUS" != 2* ]]; then
  echo "Platform organisation selection failed with HTTP $PLATFORM_SELECTION_STATUS." >&2
  echo "$SELECT_PLATFORM_RESPONSE" >&2
  if [[ "$PLATFORM_SELECTION_STATUS" == "403" ]]; then
    cat >&2 <<'EOF'

The platform smoke identity is seeded only when the application runs with the local
profile. Restart the application with:

  SPRING_PROFILES_ACTIVE=local ./gradlew bootRun

Then rerun this script. Do not add the platform membership manually or enable this
local seeder in production.
EOF
  fi
  exit 1
fi

PLATFORM_CONTEXT_TOKEN="$(json_field context_token <<<"$SELECT_PLATFORM_RESPONSE")"

echo "== Platform organisation context selected, token: $PLATFORM_CONTEXT_TOKEN =="

echo "== Creating a tenant draft with a mandatory initial administrator =="

TENANT_CODE="smoke-$(date +%s 2>/dev/null || echo local)"
CREATE_DRAFT_KEY="$(new_uuid)"
CREATE_DRAFT_BODY="$(
  cat <<EOF
{
  "tenant_code": "$TENANT_CODE",
  "display_name": "Local Smoke Tenant",
  "country_code": "KE",
  "base_currency_code": "KES",
  "timezone": "Africa/Nairobi",
  "admin": {
    "email": "smoke-admin@example.test",
    "username": "smoke-admin",
    "display_name": "Smoke Admin",
    "phone_e164": "+254700000000",
    "send_application_invite": false
  }
}
EOF
)"

DRAFT_HEADERS="$(mktemp)"
DRAFT_RESPONSE="$(
  curl -fsS -D "$DRAFT_HEADERS" \
    -X POST "$APP_URL/api/v1/platform/tenants" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "X-Active-Organisation-Context: $PLATFORM_CONTEXT_TOKEN" \
    -H "Idempotency-Key: $CREATE_DRAFT_KEY" \
    -H 'Content-Type: application/json' \
    -d "$CREATE_DRAFT_BODY"
)"
echo "$DRAFT_RESPONSE"
echo

echo "== Replaying the same Idempotency-Key must return the identical draft response =="
REPLAY_HEADERS="$(mktemp)"
REPLAY_RESPONSE="$(
  curl -fsS -D "$REPLAY_HEADERS" \
    -X POST "$APP_URL/api/v1/platform/tenants" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "X-Active-Organisation-Context: $PLATFORM_CONTEXT_TOKEN" \
    -H "Idempotency-Key: $CREATE_DRAFT_KEY" \
    -H 'Content-Type: application/json' \
    -d "$CREATE_DRAFT_BODY"
)"
if [[ "$DRAFT_RESPONSE" != "$REPLAY_RESPONSE" ]]; then
  echo "FAIL: replayed idempotent response did not match the original response" >&2
  exit 1
fi
if ! grep -qi '^Idempotency-Replayed: *true' "$REPLAY_HEADERS"; then
  echo "FAIL: replayed request did not set Idempotency-Replayed: true" >&2
  cat "$REPLAY_HEADERS" >&2
  exit 1
fi
echo "OK: idempotent replay returned the same response and Idempotency-Replayed: true"
rm -f "$DRAFT_HEADERS" "$REPLAY_HEADERS"

TENANT_ID="$(json_field organisation_id <<<"$DRAFT_RESPONSE")"

echo "== Submitting the tenant draft for approval =="
curl -fsS \
  -X POST "$APP_URL/api/v1/platform/tenants/$TENANT_ID/submit" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Active-Organisation-Context: $PLATFORM_CONTEXT_TOKEN" \
  -H "Idempotency-Key: $(new_uuid)" >/dev/null
echo "OK: submitted"

echo "== Verifying the maker (same actor who created the draft) cannot approve it =="
MAKER_APPROVE_STATUS="$(
  curl -s -o /dev/null -w '%{http_code}' \
    -X POST "$APP_URL/api/v1/platform/tenants/$TENANT_ID/approve" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "X-Active-Organisation-Context: $PLATFORM_CONTEXT_TOKEN" \
    -H "Idempotency-Key: $(new_uuid)"
)"
if [[ "$MAKER_APPROVE_STATUS" != "403" ]]; then
  echo "FAIL: expected 403 when the maker attempts to approve their own draft," \
    "got $MAKER_APPROVE_STATUS" >&2
  exit 1
fi
echo "OK: maker self-approval correctly rejected with 403"

cat <<'EOF'
NOTE: approving as a distinct checker identity is not exercised live by this script. The
default local development seed provisions exactly one platform-context Keycloak identity
(local.admin), so a live approval by a different actor cannot be demonstrated without
provisioning a second local identity. This exact scenario (approval by a distinct checker,
and the durable asynchronous bootstrap pipeline it triggers) is covered by the automated
Spring integration test suite instead - see PlatformTenantController's tests and
OrganisationProvisioningServiceTests.
EOF

echo
echo "== Polling bootstrap status on the tenant detail endpoint =="
curl -fsS \
  "$APP_URL/api/v1/platform/tenants/$TENANT_ID" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Active-Organisation-Context: $PLATFORM_CONTEXT_TOKEN"
echo

echo "== Calling a representative paginated platform endpoint (tenants) =="
curl -fsS \
  "$APP_URL/api/v1/platform/tenants?page=0&size=5" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Active-Organisation-Context: $PLATFORM_CONTEXT_TOKEN"
echo
