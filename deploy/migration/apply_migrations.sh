#!/usr/bin/env bash
#
# NemoOJ 数据库迁移执行器
#
# 设计取舍：本仓库只使用这一套迁移方式（纯序号 SQL + 版本记录表），
# 不引入 Flyway/Liquibase 等框架，也不与之并用。
# 原因：发布顺序要求"先应用数据库增量、后切换应用"（见 T11），
# 迁移应由运维显式执行并可见，而不是随应用启动自动跑。
#
# 用法：
#   ./apply_migrations.sh --host 127.0.0.1 --user root --database nemo_oj [--password xxx]
#   ./apply_migrations.sh ... --dry-run                    # 只显示将要执行的内容
#   ./apply_migrations.sh ... --baseline-through V1        # 存量库：把 V1 标记为已应用而不执行
#   ./apply_migrations.sh ... --list                       # 查看各迁移状态
#
# 口令可通过环境变量 MYSQL_PWD 提供，避免出现在命令行与进程列表里。
#
# 退出码：0 成功；1 参数/环境错误；2 存在未处理的数据冲突；3 迁移执行失败
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKS_DIR="$SCRIPT_DIR/checks"

DB_HOST=""
DB_PORT="3306"
DB_USER=""
DB_NAME=""
DRY_RUN=0
DO_LIST=0
BASELINE_THROUGH=""

log()  { printf '%s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --host)             DB_HOST="$2"; shift 2 ;;
        --port)             DB_PORT="$2"; shift 2 ;;
        --user)             DB_USER="$2"; shift 2 ;;
        --database)         DB_NAME="$2"; shift 2 ;;
        --password)         export MYSQL_PWD="$2"; shift 2 ;;
        --baseline-through) BASELINE_THROUGH="$2"; shift 2 ;;
        --dry-run)          DRY_RUN=1; shift ;;
        --list)             DO_LIST=1; shift ;;
        -h|--help)          sed -n '2,25p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *)                  fail "未知参数: $1（用 --help 查看用法）" ;;
    esac
done

[ -n "$DB_HOST" ] || fail "缺少 --host"
[ -n "$DB_USER" ] || fail "缺少 --user"
[ -n "$DB_NAME" ] || fail "缺少 --database"
command -v mysql >/dev/null 2>&1 || fail "找不到 mysql 客户端"

MYSQL=(mysql --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" --database="$DB_NAME"
       --batch --skip-column-names --default-character-set=utf8mb4)

# 避免把口令写进进程参数；MYSQL_PWD 由客户端读取
if [ -n "${MYSQL_PWD:-}" ]; then
    :
else
    log "提示：可用 MYSQL_PWD 环境变量或 --password 提供口令（避免写入历史记录）"
fi

query() { "${MYSQL[@]}" --execute="$1"; }
query_multi() { "${MYSQL[@]}" --table --execute="$1"; }

# ---------- 版本记录表 ----------
ensure_tracking_table() {
    query "CREATE TABLE IF NOT EXISTS schema_migration (
        version     VARCHAR(64)  NOT NULL COMMENT '迁移版本号，如 V1',
        description VARCHAR(255) NOT NULL COMMENT '描述',
        checksum    CHAR(64)     NOT NULL COMMENT '迁移文件 SHA-256，用于发现被改动过的脚本',
        appliedAt   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '应用时间',
        appliedBy   VARCHAR(128) NOT NULL COMMENT '执行者',
        PRIMARY KEY (version)
    ) COMMENT '数据库迁移记录' COLLATE = utf8mb4_unicode_ci;"
}

applied_checksum() {
    query "SELECT checksum FROM schema_migration WHERE version = '$1';"
}

is_applied() {
    [ -n "$(applied_checksum "$1")" ]
}

record_applied() {
    local version="$1" description="$2" checksum="$3"
    query "INSERT INTO schema_migration (version, description, checksum, appliedBy)
           VALUES ('$version', '$description', '$checksum',
                   '${USER:-unknown}@$(hostname)');"
}

checksum_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

# ---------- 前置数据校验 ----------
# V2 会为 userAccount 加唯一约束；存在重复时必须在执行前拦下来，
# 并给出可操作的报告，而不是让运维面对一条 1062 报错。
precheck_for() {
    local version="$1"
    case "$version" in
        V2)
            local dup
            dup="$(query "SELECT COUNT(*) FROM (SELECT userAccount FROM \`user\`
                          GROUP BY userAccount HAVING COUNT(*) > 1) AS dup;")"
            if [ "$dup" != "0" ]; then
                printf '\n' >&2
                printf '检测到 %s 个重复账号，无法建立唯一约束。\n' "$dup" >&2
                printf '未对数据库做任何修改。冲突明细如下：\n\n' >&2
                "${MYSQL[@]}" --table < "$CHECKS_DIR/duplicate_accounts_report.sql" >&2 || true
                printf '\n处理办法见 deploy/migration/README.md「重复账号处理」。\n' >&2
                printf '本脚本不会自动删除或合并账号。\n' >&2
                exit 2
            fi
            ;;
    esac
}

# ---------- 迁移列表 ----------
# shellcheck disable=SC2207
MIGRATIONS=($(find "$SCRIPT_DIR" -maxdepth 1 -name 'V[0-9]*__*.sql' | sort -V))
[ ${#MIGRATIONS[@]} -gt 0 ] || fail "在 $SCRIPT_DIR 下没有找到迁移文件"

version_of() { basename "$1" | sed 's/__.*//'; }
desc_of()    { basename "$1" | sed 's/^[^_]*__//; s/\.sql$//'; }

ensure_tracking_table

if [ "$DO_LIST" = "1" ]; then
    printf '%-6s %-34s %s\n' "版本" "描述" "状态"
    printf '%s\n' "----------------------------------------------------------------------"
    for f in "${MIGRATIONS[@]}"; do
        v="$(version_of "$f")"
        if is_applied "$v"; then
            state="已应用 $(query "SELECT appliedAt FROM schema_migration WHERE version='$v';")"
        else
            state="待应用"
        fi
        printf '%-6s %-34s %s\n' "$v" "$(desc_of "$f")" "$state"
    done
    exit 0
fi

# ---------- 存量库基线 ----------
if [ -n "$BASELINE_THROUGH" ]; then
    for f in "${MIGRATIONS[@]}"; do
        v="$(version_of "$f")"
        if is_applied "$v"; then
            log "跳过 $v（已记录）"
        else
            record_applied "$v" "$(desc_of "$f")" "$(checksum_of "$f")"
            log "已标记 $v 为已应用（未执行 SQL）"
        fi
        [ "$v" = "$BASELINE_THROUGH" ] && break
    done
    log "基线完成，之后的迁移可正常执行。"
    exit 0
fi

# ---------- 执行 ----------
for f in "${MIGRATIONS[@]}"; do
    v="$(version_of "$f")"

    if is_applied "$v"; then
        recorded="$(applied_checksum "$v")"
        current="$(checksum_of "$f")"
        if [ "$recorded" != "$current" ]; then
            fail "$v 已应用，但文件内容与记录不一致（记录的 checksum 为 $recorded，当前为 $current）。
      已应用的迁移不可再修改。请新增一个 V 版本而不是改动历史脚本。"
        fi
        log "跳过 $v（已应用）"
        continue
    fi

    log "→ 应用 $v：$(desc_of "$f")"
    precheck_for "$v"

    if [ "$DRY_RUN" = "1" ]; then
        log "  [dry-run] 将执行 $f"
        continue
    fi

    if ! "${MYSQL[@]}" < "$f"; then
        fail "$v 执行失败。数据库处于该迁移执行前的状态，请修复后重试：
      查看 $f，或先运行 checks/ 下的检查脚本。"
    fi
    record_applied "$v" "$(desc_of "$f")" "$(checksum_of "$f")"
    log "  完成"
done

log "全部迁移已是最新。"
