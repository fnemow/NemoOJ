#!/usr/bin/env bash
set -euo pipefail

# Operational membership maintenance. This does not change user.userRole and
# deliberately has no SQL password argument, so credentials never enter argv.
usage() {
    echo "用法: $0 grant <userAccount> <valid-until: YYYY-MM-DDTHH:MM:SS> <reason>"
    echo "      $0 revoke <userAccount> <reason>"
}
fail() { echo "ERROR: $*" >&2; exit 1; }

operation="${1:-}"
account="${2:-}"
case "$operation" in grant|revoke) ;; *) usage; exit 2 ;; esac
[[ "$account" =~ ^[A-Za-z0-9_.-]{1,64}$ ]] || fail "账号格式不正确"
reason="${3:-}"
[[ -n "$reason" && ${#reason} -le 500 ]] || fail "必须提供不超过 500 字的操作原因"

valid_until=""
if [[ "$operation" == grant ]]; then
    valid_until="${3:-}"
    reason="${4:-}"
    [[ "$valid_until" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}$ ]] || fail "到期时间须为 YYYY-MM-DDTHH:MM:SS"
    date -d "$valid_until" >/dev/null 2>&1 || fail "到期时间无效"
    [[ -n "$reason" && ${#reason} -le 500 ]] || fail "必须提供不超过 500 字的操作原因"
fi

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_NAME="${DB_NAME:-nemo_oj}"
if [[ -z "${MYSQL_PWD:-}" ]]; then
    read -r -s -p "MySQL password: " MYSQL_PWD
    echo
    export MYSQL_PWD
fi

mysql_cmd=(mysql --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USERNAME" \
    --database="$DB_NAME" --batch --skip-column-names --default-character-set=utf8mb4)
# Account characters are constrained above; resolve target and role before any write.
record="$("${mysql_cmd[@]}" --execute="SELECT CONCAT(id, '|', userRole) FROM \`user\` WHERE userAccount='${account}' AND isDelete=0 LIMIT 1")"
[[ -n "$record" ]] || fail "账号不存在"
user_id="${record%%|*}"
role="${record#*|}"
[[ "$role" == user ]] || fail "仅允许为未封禁普通用户开通或撤销 VIP"

sql_reason="${reason//\\/\\\\}"
sql_reason="${sql_reason//\'/\'\'}"
actor="${USER:-ops}"
sql_actor="${actor//\'/\'\'}"
if [[ "$operation" == grant ]]; then
    valid_until_mysql="${valid_until/T/ }"
    sql="START TRANSACTION;
INSERT INTO user_membership (userId,tier,validFrom,validUntil,updatedBy,rowVersion)
VALUES (${user_id},'VIP',NOW(),'${valid_until_mysql}','${sql_actor}',0)
ON DUPLICATE KEY UPDATE tier='VIP',validFrom=NOW(),validUntil=VALUES(validUntil),updatedBy=VALUES(updatedBy),rowVersion=rowVersion+1;
INSERT INTO ai_action_audit (actorType,actorId,action,resourceType,resourceId,outcome,detail)
VALUES ('OPS','${sql_actor}','VIP_GRANT','USER','${user_id}','SUCCEEDED','${sql_reason}');
COMMIT;"
else
    sql="START TRANSACTION;
INSERT INTO user_membership (userId,tier,validFrom,validUntil,updatedBy,rowVersion)
VALUES (${user_id},'FREE',NULL,NULL,'${sql_actor}',0)
ON DUPLICATE KEY UPDATE tier='FREE',validFrom=NULL,validUntil=NULL,updatedBy=VALUES(updatedBy),rowVersion=rowVersion+1;
INSERT INTO ai_action_audit (actorType,actorId,action,resourceType,resourceId,outcome,detail)
VALUES ('OPS','${sql_actor}','VIP_REVOKE','USER','${user_id}','SUCCEEDED','${sql_reason}');
COMMIT;"
fi
"${mysql_cmd[@]}" --execute="$sql"
"${mysql_cmd[@]}" --execute="SELECT CONCAT('userId=', userId, ', tier=',tier,', validUntil=',COALESCE(validUntil,'NULL')) FROM user_membership WHERE userId=${user_id}"
