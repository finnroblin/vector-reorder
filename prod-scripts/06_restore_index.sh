#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/config.sh"

BACKUP_DIR=${1:?Usage: $0 <backup_dir>}

FOLDER=$(ls --color=never "$NODE_DIR" | grep -v '^\.' | head -1)
INDEX_PATH="${NODE_DIR}/${FOLDER}/0/index"

echo "Restoring index from $BACKUP_DIR..."
rm -rf "$INDEX_PATH"
cp -r "$BACKUP_DIR/index" "$INDEX_PATH"
echo "Restore complete:"
ls -la "$INDEX_PATH"
