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

cd "$ROOT_DIR"

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

ACCESS_TOKEN="$(
  python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])' \
    <<<"$TOKEN_RESPONSE"
)"

if ! curl -fsS "$APP_URL/actuator/health" >/dev/null; then
  cat <<EOF
Infrastructure is ready and a Keycloak token was issued.

Start the application in another shell, then rerun this script:

  ./gradlew bootRun

Expected app URL: $APP_URL
EOF
  exit 0
fi

SELECT_ORG_RESPONSE="$(
  curl -fsS \
    -X POST "$APP_URL/api/v1/auth/select-organisation" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H 'Content-Type: application/json' \
    -d "{\"organisationId\":\"$ORGANISATION_ID\"}"
)"

CONTEXT_TOKEN="$(
  python3 -c 'import json,sys; print(json.load(sys.stdin)["contextToken"])' \
    <<<"$SELECT_ORG_RESPONSE"
)"

SELECT_BRANCH_RESPONSE="$(
  curl -fsS \
    -X POST "$APP_URL/api/v1/auth/select-branch" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "X-Active-Organisation-Context: $CONTEXT_TOKEN" \
    -H 'Content-Type: application/json' \
    -d "{\"branchId\":\"$BRANCH_ID\"}"
)"

BRANCH_CONTEXT_TOKEN="$(
  python3 -c 'import json,sys; print(json.load(sys.stdin)["contextToken"])' \
    <<<"$SELECT_BRANCH_RESPONSE"
)"

curl -fsS \
  "$APP_URL/api/v1/auth/me" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Active-Organisation-Context: $BRANCH_CONTEXT_TOKEN"

echo
