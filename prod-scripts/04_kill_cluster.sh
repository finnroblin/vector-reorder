#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/config.sh"

echo "Stopping OpenSearch cluster..."
pkill -f "opensearch" || true
sleep 5
echo "Cluster stopped."
