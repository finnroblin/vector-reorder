# Vector Reorder Benchmark Runbook

## Overview
Benchmarks baseline vs k-means vs BP reordering on SIFT-128 dataset.

## Prerequisites
- OpenSearch built at `$OPENSEARCH_HOME`
- SIFT-128 HDF5 file at configured path
- `opensearch-benchmark` installed
- Python3 with `h5py`, `requests`

## Configuration
Edit `config.sh` to set paths for your environment.

## Quick Start
```bash
cd prod-scripts
chmod +x *.sh
./run_all.sh
```

## Individual Scripts

| Script | Purpose |
|--------|---------|
| `01_start_cluster.sh` | Start OpenSearch with custom data dir |
| `02_ingest_data.sh` | Create index, bulk ingest SIFT vectors, force merge |
| `03_run_benchmark.sh <label>` | Run OSB search benchmark, saves to `results/<label>_<timestamp>.csv` |
| `04_kill_cluster.sh` | Stop OpenSearch |
| `05_backup_index.sh <dir>` | Copy index files to backup dir |
| `06_restore_index.sh <dir>` | Restore index files from backup dir |
| `07_reorder_index.sh <kmeans\|bp>` | Run reordering and swap files |

## Flow

### Phase 1: Baseline
```bash
./01_start_cluster.sh
./02_ingest_data.sh
./03_run_benchmark.sh baseline
./04_kill_cluster.sh
./05_backup_index.sh $BASELINE_BACKUPS
```

### Phase 2: K-Means
```bash
./06_restore_index.sh $BASELINE_BACKUPS
./07_reorder_index.sh kmeans
./01_start_cluster.sh
./03_run_benchmark.sh kmeans
./04_kill_cluster.sh
./05_backup_index.sh $KMEANS_BACKUPS
```

### Phase 3: BP
```bash
./06_restore_index.sh $BASELINE_BACKUPS
./07_reorder_index.sh bp
./01_start_cluster.sh
./03_run_benchmark.sh bp
./04_kill_cluster.sh
./05_backup_index.sh $BP_BACKUPS
```

## Results
CSV files saved to `prod-scripts/results/` with timestamps.
