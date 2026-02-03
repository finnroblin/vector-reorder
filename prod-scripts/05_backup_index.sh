#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/config.sh"

BACKUP_DIR=${1:?Usage: $0 <backup_dir>}

FOLDER=$(ls --color=never "$NODE_DIR" | grep -v '^\.' | head -1)
INDEX_PATH="${NODE_DIR}/${FOLDER}/0/index"

echo "Backing up index to $BACKUP_DIR..."
mkdir -p "$BACKUP_DIR"
rm -rf "$BACKUP_DIR/index"
cp -r "$INDEX_PATH" "$BACKUP_DIR/index"
echo "Backup complete:"
ls -la "$BACKUP_DIR/index"
