#!/usr/bin/env bash
# PocketPrint Production Backup & Restore Script
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/var/backups/pocketprint}"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
DB_NAME="${DB_NAME:-pocketprint}"
DB_USER="${DB_USER:-pocketprint_user}"
STORAGE_DIR="${STORAGE_DIR:-/var/www/pocketprint/storage}"

mkdir -p "$BACKUP_DIR"

backup() {
    echo "[+] Starting PocketPrint backup at ${TIMESTAMP}..."
    local db_backup="${BACKUP_DIR}/pocketprint_db_${TIMESTAMP}.sql.gz"
    local storage_backup="${BACKUP_DIR}/pocketprint_storage_${TIMESTAMP}.tar.gz"

    echo "[+] Dumping PostgreSQL database '${DB_NAME}'..."
    pg_dump -U "$DB_USER" -d "$DB_NAME" --no-owner --no-acl | gzip > "$db_backup"

    echo "[+] Archiving storage files from '${STORAGE_DIR}'..."
    tar -czf "$storage_backup" -C "$(dirname "$STORAGE_DIR")" "$(basename "$STORAGE_DIR")"

    echo "[+] Backup successfully completed:"
    echo "    Database: ${db_backup} ($(du -h "$db_backup" | cut -f1))"
    echo "    Storage:  ${storage_backup} ($(du -h "$storage_backup" | cut -f1))"
}

restore() {
    local target_db_file="${1:-}"
    local target_storage_file="${2:-}"

    if [[ -z "$target_db_file" || -z "$target_storage_file" ]]; then
        echo "Usage: $0 restore <db_backup.sql.gz> <storage_backup.tar.gz>"
        exit 1
    fi

    echo "[!] Restoring database from ${target_db_file}..."
    gunzip -c "$target_db_file" | psql -U "$DB_USER" -d "$DB_NAME"

    echo "[!] Restoring storage files from ${target_storage_file}..."
    tar -xzf "$target_storage_file" -C "$(dirname "$STORAGE_DIR")"

    echo "[+] Restore completed successfully."
}

case "${1:-backup}" in
    backup)
        backup
        ;;
    restore)
        shift
        restore "$@"
        ;;
    *)
        echo "Usage: $0 {backup|restore <db_file> <storage_file>}"
        exit 1
        ;;
esac
