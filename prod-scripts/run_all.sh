#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/config.sh"

echo "=== PHASE 1: BASELINE ==="
$SCRIPT_DIR/01_start_cluster.sh
$SCRIPT_DIR/02_ingest_data.sh
$SCRIPT_DIR/03_run_benchmark.sh "baseline"
$SCRIPT_DIR/04_kill_cluster.sh
$SCRIPT_DIR/05_backup_index.sh "$BASELINE_BACKUPS"

echo "=== PHASE 2: KMEANS ==="
$SCRIPT_DIR/06_restore_index.sh "$BASELINE_BACKUPS"
$SCRIPT_DIR/07_reorder_index.sh kmeans
$SCRIPT_DIR/01_start_cluster.sh
$SCRIPT_DIR/03_run_benchmark.sh "kmeans"
$SCRIPT_DIR/04_kill_cluster.sh
$SCRIPT_DIR/05_backup_index.sh "$KMEANS_BACKUPS"

echo "=== PHASE 3: BP ==="
$SCRIPT_DIR/06_restore_index.sh "$BASELINE_BACKUPS"
$SCRIPT_DIR/07_reorder_index.sh bp
$SCRIPT_DIR/01_start_cluster.sh
$SCRIPT_DIR/03_run_benchmark.sh "bp"
$SCRIPT_DIR/04_kill_cluster.sh
$SCRIPT_DIR/05_backup_index.sh "$BP_BACKUPS"

echo "=== ALL PHASES COMPLETE ==="
echo "Results in: $SCRIPT_DIR/results/"
