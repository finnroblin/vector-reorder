#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/config.sh"

echo "Starting OpenSearch cluster..."
cd "$OPENSEARCH_HOME"
./bin/opensearch -d -Epath.data="$DATA_DIR"
sleep 15

# Wait for cluster to be ready
until curl -s "$OS_URL" > /dev/null 2>&1; do
    echo "Waiting for cluster..."
    sleep 2
done
echo "Cluster started."
