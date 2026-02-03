#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/config.sh"

LABEL=${1:-"benchmark"}
RESULTS_DIR="$SCRIPT_DIR/results"
mkdir -p "$RESULTS_DIR"
RESULTS_FILE="$RESULTS_DIR/${LABEL}_$(date +%Y%m%d_%H%M%S).csv"

echo "Running OSB search benchmark ($LABEL)..."

opensearch-benchmark execute-test \
    --workload-path="$OSB_WORKLOAD_PATH" \
    --target-hosts localhost:9200 \
    --workload-params "$OSB_PARAMS" \
    --pipeline benchmark-only \
    --test-procedure=search-only \
    --kill-running-processes \
    --results-format=csv \
    --results-file="$RESULTS_FILE"

echo "Benchmark complete: $RESULTS_FILE"
