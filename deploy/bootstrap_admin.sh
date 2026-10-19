#!/usr/bin/env bash
# One-time administrator bootstrap. The password is read silently and is never
# placed in command arguments, source files, the compose environment, or logs.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ACCOUNT="nemo_admin"
BASE_URL="http://127.0.0.1"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"
ENV_FILE="$SCRIPT_DIR/.env"

usage() {
  cat <<'EOF'
Usage: bootstrap_admin.sh [--account nemo_admin] [--base-url http://127.0.0.1]
                          [--compose-file path] [--env-file path]
The account must not already exist. The script registers it through the public
API, promotes exactly that account in MySQL, then verifies login and admin API access.
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --account) ACCOUNT="$2"; shift 2 ;;
    --base-url) BASE_URL="${2%/}"; shift 2 ;;
    --compose-file) COMPOSE_FILE="$2"; shift 2 ;;
    --env-file) ENV_FILE="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'Unknown argument: %s\n' "$1" >&2; usage >&2; exit 1 ;;
  esac
done

[[ "$ACCOUNT" =~ ^[A-Za-z0-9_]{4,64}$ ]] || {
  printf 'Account must contain 4-64 letters, digits, or underscores.\n' >&2; exit 1;
}
command -v curl >/dev/null 2>&1 || { printf 'curl is required.\n' >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { printf 'python3 is required.\n' >&2; exit 1; }
command -v docker >/dev/null 2>&1 || { printf 'docker is required.\n' >&2; exit 1; }
[ -f "$COMPOSE_FILE" ] || { printf 'Compose file not found: %s\n' "$COMPOSE_FILE" >&2; exit 1; }

read -r -s -p "Password for $ACCOUNT (at least 8 characters): " PASSWORD; printf '\n'
read -r -s -p "Confirm password: " CONFIRM; printf '\n'
[ ${#PASSWORD} -ge 8 ] || { printf 'Password is too short.\n' >&2; exit 1; }
[ "$PASSWORD" = "$CONFIRM" ] || { printf 'Passwords do not match.\n' >&2; exit 1; }
unset CONFIRM

TMP_DIR="$(mktemp -d)"
trap 'unset PASSWORD INVITATION_CODE INVITATION_HASH; rm -rf -- "$TMP_DIR"' EXIT
COOKIE_JAR="$TMP_DIR/cookies"

compose=(docker compose)
if [ -f "$ENV_FILE" ]; then compose+=(--env-file "$ENV_FILE"); fi
compose+=(-f "$COMPOSE_FILE")
existing="$("${compose[@]}" exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --skip-column-names nemo_oj' <<SQL
SELECT COUNT(*) FROM user WHERE userAccount='$ACCOUNT';
SQL
)"
[ "$(printf '%s\n' "$existing" | tail -n 1 | tr -d '\r')" = "0" ] || {
  printf 'Account already exists; no password or role was changed.\n' >&2; exit 1;
}
# Host-side bootstrap does not expose an invitation-free HTTP registration path.
INVITATION_CODE="$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
INVITATION_HASH="$(printf '%s' "$INVITATION_CODE" | python3 -c 'import hashlib,sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())')"
"${compose[@]}" exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot nemo_oj' <<SQL
INSERT INTO registration_invitation (codeHash,note,createdBy,expiresAt,createTime)
VALUES ('$INVITATION_HASH','host administrator bootstrap',NULL,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 15 MINUTE),UTC_TIMESTAMP());
SQL

csrf_json="$(curl --fail --silent --show-error -c "$COOKIE_JAR" "$BASE_URL/api/user/csrf")"
csrf_token="$(printf '%s' "$csrf_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["token"])')"

register_response="$(printf '%s\n%s\n%s\n' "$ACCOUNT" "$PASSWORD" "$INVITATION_CODE" | python3 -c '
import json,sys
account=sys.stdin.readline().rstrip("\n")
password=sys.stdin.readline().rstrip("\n")
invitation=sys.stdin.readline().rstrip("\n")
print(json.dumps({"userAccount":account,"userPassword":password,"checkPassword":password,"invitationCode":invitation}))
' | curl --fail --silent --show-error -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
    -H 'Content-Type: application/json' -H "X-CSRF-Token: $csrf_token" \
    --data-binary @- "$BASE_URL/api/user/register")"
printf '%s' "$register_response" | python3 -c '
import json,sys
r=json.load(sys.stdin)
if r.get("code") != 0:
    raise SystemExit("Registration failed: " + str(r.get("message","unknown error")))
'

unset INVITATION_CODE INVITATION_HASH
updated="$("${compose[@]}" exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --skip-column-names nemo_oj' <<SQL
START TRANSACTION;
UPDATE user SET userRole='admin' WHERE userAccount='$ACCOUNT' AND isDelete=0 AND userRole='user';
SELECT ROW_COUNT();
COMMIT;
SQL
)"
[ "$(printf '%s\n' "$updated" | tail -n 1 | tr -d '\r')" = "1" ] || {
  printf 'Promotion failed; the account was not in the expected new-user state.\n' >&2; exit 1;
}

# Use a fresh session so role verification reads the database after promotion.
rm -f "$COOKIE_JAR"
csrf_json="$(curl --fail --silent --show-error -c "$COOKIE_JAR" "$BASE_URL/api/user/csrf")"
csrf_token="$(printf '%s' "$csrf_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["token"])')"
login_response="$(printf '%s\n%s\n' "$ACCOUNT" "$PASSWORD" | python3 -c '
import json,sys
print(json.dumps({"userAccount":sys.stdin.readline().rstrip("\n"),"userPassword":sys.stdin.readline().rstrip("\n")}))
' | curl --fail --silent --show-error -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
    -H 'Content-Type: application/json' -H "X-CSRF-Token: $csrf_token" \
    --data-binary @- "$BASE_URL/api/user/login")"
printf '%s' "$login_response" | python3 -c '
import json,sys
r=json.load(sys.stdin)
if r.get("code") != 0 or (r.get("data") or {}).get("userRole") != "admin":
    raise SystemExit("Administrator login verification failed")
'

csrf_json="$(curl --fail --silent --show-error -b "$COOKIE_JAR" -c "$COOKIE_JAR" "$BASE_URL/api/user/csrf")"
csrf_token="$(printf '%s' "$csrf_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["token"])')"
admin_response="$(printf '%s' '{"current":1,"pageSize":1}' | curl --fail --silent --show-error \
  -b "$COOKIE_JAR" -H 'Content-Type: application/json' -H "X-CSRF-Token: $csrf_token" \
  --data-binary @- "$BASE_URL/api/question/admin/problems/list/page")"
printf '%s' "$admin_response" | python3 -c '
import json,sys
r=json.load(sys.stdin)
if r.get("code") != 0: raise SystemExit("Admin API verification failed")
'

printf 'Administrator %s was created and verified successfully.\n' "$ACCOUNT"
