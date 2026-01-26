# E2E testing of the reordering

1. Set up cluster -- 
```
./gradlew run --data-dir=/Users/finnrobl/Documents/k-NN-2/e2e_data
```

2. Ingest data - /Users/finnrobl/Documents/daily/ingest_sift.ipynb

Should see data at 
!ls -la /Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/HzFmppDdSvCAwh0eDafpcg/0/index
(index name might be different due to mangling.)

3. kill the cluster

4. run the reorder script to grab some temporary files

(general step should be copy .faiss -> .faiss.original (only if .original doesn't already exist), grab the original data , also do that for .vec). we odn't need to do it for .vemf 


5. replace the temporary files with the reordered files.

---

## Running the E2E Test

### Pre-restart script

Run this after killing the cluster, before restarting:

```bash
cd /Users/finnrobl/Documents/k-NN-2/vector-reorder
./gradlew runE2E -PindexDir=<path-to-index-dir>
```

Or manually:

```bash
# Set variables
INDEX_DIR="/Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/<index-uuid>/0/index"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="/Users/finnrobl/Documents/k-NN-2/file-backups/${TIMESTAMP}-backups"

# Find the .faiss and .vec files
FAISS_FILE=$(ls "$INDEX_DIR"/*.faiss 2>/dev/null | head -1)
VEC_FILE=$(ls "$INDEX_DIR"/*_NativeEngines990KnnVectorsFormat_0.vec 2>/dev/null | head -1)

# Create backup directory and backup originals
mkdir -p "$BACKUP_DIR"
cp "$FAISS_FILE" "$BACKUP_DIR/"
cp "$VEC_FILE" "$BACKUP_DIR/"
echo "Backed up to: $BACKUP_DIR"

# Run reorder tool
cd /Users/finnrobl/Documents/k-NN-2/vector-reorder


./gradlew reorder \
  -PvecFile="$VEC_FILE" \
  -PoutputFaiss="${FAISS_FILE%.faiss}_reordered.faiss" \
  -PoutputVec="${VEC_FILE%.vec}_reordered.vec" \
  -Pclusters=1000

# Swap files
mv "$FAISS_FILE" "${FAISS_FILE}.old"
mv "${FAISS_FILE%.faiss}_reordered.faiss" "$FAISS_FILE"
mv "$VEC_FILE" "${VEC_FILE}.old"
mv "${VEC_FILE%.vec}_reordered.vec" "$VEC_FILE"

# Clean up .old files (OpenSearch will delete them anyway)
rm -f "${FAISS_FILE}.old" "${VEC_FILE}.old"

echo ""
echo "=== DONE ==="
echo "Backups at: $BACKUP_DIR"
echo ""
echo "Now kill and restart cluster."
```

---

## Jan 15 test #1

### Step 4: Run reorder tool

```bash
cd /Users/finnrobl/Documents/k-NN-2/vector-reorder

./gradlew reorder \
  -PvecFile=/Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/HzFmppDdSvCAwh0eDafpcg/0/index/_z_NativeEngines990KnnVectorsFormat_0.vec \
  -PoutputFaiss=/Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/HzFmppDdSvCAwh0eDafpcg/0/index/_z_165_train_reordered.faiss \
  -PoutputVec=/Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/HzFmppDdSvCAwh0eDafpcg/0/index/_z_NativeEngines990KnnVectorsFormat_0_reordered.vec \
  -Pclusters=1000
```

Output:
```
=== Vector Reorder Tool ===
Input .vec:    /Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/HzFmppDdSvCAwh0eDafpcg/0/index/_z_NativeEngines990KnnVectorsFormat_0.vec
Output .faiss: /Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/HzFmppDdSvCAwh0eDafpcg/0/index/_z_165_train_reordered.faiss
Output .vec:   /Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/HzFmppDdSvCAwh0eDafpcg/0/index/_z_NativeEngines990KnnVectorsFormat_0_reordered.vec
Clusters: 1000

Loaded 1000000 vectors of dim 128 in 1104 ms
Clustering took 5172 ms
Sorting by cluster...
Building FAISS index...
Index build took 23873 ms
Writing reordered .vec file...
Vec file write took 2875 ms

SUCCESS!
  .faiss: ... (664258407 bytes)
  .vec:   ... (512000108 bytes)
  Structure: FaissStructure{type=IxMp, hnsw=IHNf, flat=IxF2, dim=128, n=1000000, maxLevel=5, entry=118295}
```

### Step 5: Swap files

```bash
INDEX_DIR="/Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/HzFmppDdSvCAwh0eDafpcg/0/index"

# Backup originals
mv "$INDEX_DIR/_z_165_train.faiss" "$INDEX_DIR/_z_165_train.faiss.original"
mv "$INDEX_DIR/_z_NativeEngines990KnnVectorsFormat_0.vec" "$INDEX_DIR/_z_NativeEngines990KnnVectorsFormat_0.vec.original"

# Swap in reordered files
mv "$INDEX_DIR/_z_165_train_reordered.faiss" "$INDEX_DIR/_z_165_train.faiss"
mv "$INDEX_DIR/_z_NativeEngines990KnnVectorsFormat_0_reordered.vec" "$INDEX_DIR/_z_NativeEngines990KnnVectorsFormat_0.vec"
```

### Verify files

```bash
ls -la "$INDEX_DIR"/*.faiss* "$INDEX_DIR"/*.vec*
```

Output:
```
-rw-r--r--@ 1 finnrobl  staff  664258407 Jan 15 10:56 _z_165_train.faiss
-rw-r--r--@ 1 finnrobl  staff  664258423 Jan 15 10:47 _z_165_train.faiss.original
-rw-r--r--@ 1 finnrobl  staff  512000108 Jan 15 10:56 _z_NativeEngines990KnnVectorsFormat_0.vec
-rw-r--r--@ 1 finnrobl  staff  512000108 Jan 15 10:47 _z_NativeEngines990KnnVectorsFormat_0.vec.original
```

### Restart cluster and test

```bash
cd /Users/finnrobl/Documents/k-NN-2/k-NN
./gradlew run --data-dir=/Users/finnrobl/Documents/k-NN-2/e2e_data
```

## Notes after Jan 15 test #2:

- Original files were deleted upon cluster restart (.original not present). Action item: in E2E test we need to move original files to a new directory /Users/finnrobl/Documents/k-NN-2/file-backups/${<test-timestamp>-backups}
ls -la /Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/HzFmppDdSvCAwh0eDafpcg/0/index/
> total 2334256
-rw-r--r--@  1 finnrobl  staff  664258407 Jan 15 10:56 _z_165_train.faiss
-rw-r--r--@  1 finnrobl  staff         79 Jan 15 10:44 _z_Lucene103_0.doc
-rw-r--r--@  1 finnrobl  staff        104 Jan 15 10:44 _z_Lucene103_0.psm
-rw-r--r--@  1 finnrobl  staff    3091288 Jan 15 10:44 _z_Lucene103_0.tim
-rw-r--r--@  1 finnrobl  staff     147523 Jan 15 10:44 _z_Lucene103_0.tip
-rw-r--r--@  1 finnrobl  staff        178 Jan 15 10:44 _z_Lucene103_0.tmd
-rw-r--r--@  1 finnrobl  staff    2181364 Jan 15 10:44 _z_Lucene90_0.dvd
-rw-r--r--@  1 finnrobl  staff        312 Jan 15 10:44 _z_Lucene90_0.dvm
-rw-r--r--@  1 finnrobl  staff  512000108 Jan 15 10:56 _z_NativeEngines990KnnVectorsFormat_0.vec
-rw-r--r--@  1 finnrobl  staff        152 Jan 15 10:47 _z_NativeEngines990KnnVectorsFormat_0.vemf
-rw-r--r--@  1 finnrobl  staff        159 Jan 15 10:44 _z.fdm
-rw-r--r--@  1 finnrobl  staff    4201918 Jan 15 10:44 _z.fdt
-rw-r--r--@  1 finnrobl  staff       3488 Jan 15 10:44 _z.fdx
-rw-r--r--@  1 finnrobl  staff        883 Jan 15 10:47 _z.fnm
-rw-r--r--@  1 finnrobl  staff    1079206 Jan 15 10:44 _z.kdd
-rw-r--r--@  1 finnrobl  staff       9227 Jan 15 10:44 _z.kdi
-rw-r--r--@  1 finnrobl  staff        149 Jan 15 10:44 _z.kdm
-rw-r--r--@  1 finnrobl  staff        687 Jan 15 10:47 _z.si
drwxr-xr-x@ 22 finnrobl  staff        704 Jan 15 10:59 .
drwxr-xr-x@  5 finnrobl  staff        160 Jan 15 10:58 ..
-rw-r--r--@  1 finnrobl  staff        372 Jan 15 10:47 segments_4
-rw-r--r--@  1 finnrobl  staff          0 Jan 15 10:43 write.lock

- Recall@k is lower than expected  (Mean recall@k,prod-queries,0.56,)


```
!opensearch-benchmark execute-test --workload-path=/Users/finnrobl/Documents/opensearch-benchmark-workloads/vectorsearch --target-hosts localhost:9200 --workload-params /Users/finnrobl/Documents/opensearch-benchmark-workloads/vectorsearch/params/faiss-sift-128-l2.json --pipeline benchmark-only --test-procedure=search-only --kill-running-processes --results-format=csv

{
  "target_index_name": "sift-index",
  "target_field_name": "train",
  "target_index_body": "indices/faiss-index.json",
  "target_index_primary_shards": 1,
  "target_index_dimension": 128,
  "target_index_space_type": "l2",
  "target_index_bulk_size": 100,
  "target_index_bulk_index_data_set_format": "hdf5",
  "target_index_bulk_index_data_set_path": "/Users/finnrobl/Downloads/sift-128-euclidean.hdf5",
  "target_index_bulk_indexing_clients": 10,
  "target_index_max_num_segments": 1,
  "target_index_force_merge_timeout": 300,
  "query_k": 100,
  "query_body": {
    "docvalue_fields": [
      "_id"
    ],
    "stored_fields": "_none_"
  },
  "query_data_set_format": "hdf5",
  "query_data_set_path": "/Users/finnrobl/Downloads/sift-128-euclidean.hdf5",
  "query_count": 100
}


....
Metric,Task,Value,Unit
Cumulative indexing time of primary shards,,0,min
Min cumulative indexing time across primary shards,,0,min
Median cumulative indexing time across primary shards,,0,min
Max cumulative indexing time across primary shards,,0,min
Cumulative indexing throttle time of primary shards,,0,min
Min cumulative indexing throttle time across primary shards,,0,min
Median cumulative indexing throttle time across primary shards,,0,min
Max cumulative indexing throttle time across primary shards,,0,min
Cumulative merge time of primary shards,,0,min
Cumulative merge count of primary shards,,0,
Min cumulative merge time across primary shards,,0,min
Median cumulative merge time across primary shards,,0,min
Max cumulative merge time across primary shards,,0,min
Cumulative merge throttle time of primary shards,,0,min
Min cumulative merge throttle time across primary shards,,0,min
Median cumulative merge throttle time across primary shards,,0,min
Max cumulative merge throttle time across primary shards,,0,min
Cumulative refresh time of primary shards,,0,min
Cumulative refresh count of primary shards,,2,
Min cumulative refresh time across primary shards,,0,min
Median cumulative refresh time across primary shards,,0,min
Max cumulative refresh time across primary shards,,0,min
Cumulative flush time of primary shards,,0,min
Cumulative flush count of primary shards,,1,
Min cumulative flush time across primary shards,,0,min
Median cumulative flush time across primary shards,,0,min
Max cumulative flush time across primary shards,,0,min
Total Young Gen GC time,,0.008,s
Total Young Gen GC count,,1,
Total Old Gen GC time,,0,s
Total Old Gen GC count,,0,
Store size,,1.1054571755230427,GB
Translog size,,5.122274160385132e-08,GB
Heap used for segments,,0,MB
Heap used for doc values,,0,MB
Heap used for terms,,0,MB
Heap used for norms,,0,MB
Heap used for points,,0,MB
Heap used for stored fields,,0,MB
Segment count,,1,
Min Throughput,warmup-indices,0.70,ops/s
Mean Throughput,warmup-indices,0.70,ops/s
Median Throughput,warmup-indices,0.70,ops/s
Max Throughput,warmup-indices,0.70,ops/s
100th percentile latency,warmup-indices,1434.2656660010107,ms
100th percentile service time,warmup-indices,1434.2656660010107,ms
error rate,warmup-indices,0.00,%
Min Throughput,prod-queries,67.91,ops/s
Mean Throughput,prod-queries,67.91,ops/s
Median Throughput,prod-queries,67.91,ops/s
Max Throughput,prod-queries,67.91,ops/s
50th percentile latency,prod-queries,2.3632499942323193,ms
90th percentile latency,prod-queries,3.2537502993363887,ms
99th percentile latency,prod-queries,8.257694497153343,ms
100th percentile latency,prod-queries,358.63379199872725,ms
50th percentile service time,prod-queries,2.3632499942323193,ms
90th percentile service time,prod-queries,3.2537502993363887,ms
99th percentile service time,prod-queries,8.257694497153343,ms
100th percentile service time,prod-queries,358.63379199872725,ms
error rate,prod-queries,0.00,%
Mean recall@k,prod-queries,0.56,
Mean recall@1,prod-queries,0.91,


--------------------------------
[INFO] SUCCESS (took 17 seconds)
--------------------------------
```
- Action item: make a new E2E script for pre-restart run . At end of script print out the phrase "now kill and restart cluster."


# Test Jan 15 #2

Pre preorder result:
```
Metric,Task,Value,Unit
Cumulative indexing time of primary shards,,0.8112666666666667,min
Min cumulative indexing time across primary shards,,0.8112666666666667,min
Median cumulative indexing time across primary shards,,0.8112666666666667,min
Max cumulative indexing time across primary shards,,0.8112666666666667,min
Cumulative indexing throttle time of primary shards,,0,min
Min cumulative indexing throttle time across primary shards,,0,min
Median cumulative indexing throttle time across primary shards,,0,min
Max cumulative indexing throttle time across primary shards,,0,min
Cumulative merge time of primary shards,,4.433516666666667,min
Cumulative merge count of primary shards,,3,
Min cumulative merge time across primary shards,,4.433516666666667,min
Median cumulative merge time across primary shards,,4.433516666666667,min
Max cumulative merge time across primary shards,,4.433516666666667,min
Cumulative merge throttle time of primary shards,,0.6223333333333334,min
Min cumulative merge throttle time across primary shards,,0.6223333333333334,min
Median cumulative merge throttle time across primary shards,,0.6223333333333334,min
Max cumulative merge throttle time across primary shards,,0.6223333333333334,min
Cumulative refresh time of primary shards,,0.9559833333333334,min
Cumulative refresh count of primary shards,,34,
Min cumulative refresh time across primary shards,,0.9559833333333334,min
Median cumulative refresh time across primary shards,,0.9559833333333334,min
Max cumulative refresh time across primary shards,,0.9559833333333334,min
Cumulative flush time of primary shards,,0.07481666666666667,min
Cumulative flush count of primary shards,,2,
Min cumulative flush time across primary shards,,0.07481666666666667,min
Median cumulative flush time across primary shards,,0.07481666666666667,min
Max cumulative flush time across primary shards,,0.07481666666666667,min
Total Young Gen GC time,,0,s
Total Young Gen GC count,,0,
Total Old Gen GC time,,0,s
Total Old Gen GC count,,0,
Store size,,1.105479613877833,GB
Translog size,,5.122274160385132e-08,GB
Heap used for segments,,0,MB
Heap used for doc values,,0,MB
Heap used for terms,,0,MB
Heap used for norms,,0,MB
Heap used for points,,0,MB
Heap used for stored fields,,0,MB
Segment count,,1,
Min Throughput,warmup-indices,0.77,ops/s
Mean Throughput,warmup-indices,0.77,ops/s
Median Throughput,warmup-indices,0.77,ops/s
Max Throughput,warmup-indices,0.77,ops/s
100th percentile latency,warmup-indices,1302.9750840069028,ms
100th percentile service time,warmup-indices,1302.9750840069028,ms
error rate,warmup-indices,0.00,%
Min Throughput,prod-queries,120.74,ops/s
Mean Throughput,prod-queries,120.74,ops/s
Median Throughput,prod-queries,120.74,ops/s
Max Throughput,prod-queries,120.74,ops/s
50th percentile latency,prod-queries,2.380229503614828,ms
90th percentile latency,prod-queries,3.606475298875012,ms
99th percentile latency,prod-queries,7.359795912926727,ms
100th percentile latency,prod-queries,221.69112499977928,ms
50th percentile service time,prod-queries,2.380229503614828,ms
90th percentile service time,prod-queries,3.606475298875012,ms
99th percentile service time,prod-queries,7.359795912926727,ms
100th percentile service time,prod-queries,221.69112499977928,ms
error rate,prod-queries,0.00,%
Mean recall@k,prod-queries,0.93,
Mean recall@1,prod-queries,0.99,
```

Then run 
```
./gradlew runE2E -PindexDir=/Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/h_kdtEHFT6-YZavadAzzUA/0/index
```

> Backups at: /Users/finnrobl/Documents/k-NN-2/file-backups/20260115_132646-backups

ls -la /Users/finnrobl/Documents/k-NN-2/file-backups/20260115_132646-backups | pbcopy
total 2297392
-rw-r--r--@ 1 finnrobl  staff  664258423 Jan 15 13:26 _z_165_train.faiss
-rw-r--r--@ 1 finnrobl  staff  512000108 Jan 15 13:26 _z_NativeEngines990KnnVectorsFormat_0.vec
drwxr-xr-x@ 4 finnrobl  staff        128 Jan 15 13:26 .
drwxr-xr-x@ 3 finnrobl  staff         96 Jan 15 13:26 ..


ls -la /Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/h_kdtEHFT6-YZavadAzzUA/0/index
total 2352176
-rw-r--r--@  1 finnrobl  staff  664258407 Jan 15 13:27 _z_165_train.faiss
-rw-r--r--@  1 finnrobl  staff         79 Jan 15 13:21 _z_Lucene103_0.doc
-rw-r--r--@  1 finnrobl  staff        104 Jan 15 13:21 _z_Lucene103_0.psm
-rw-r--r--@  1 finnrobl  staff    3089987 Jan 15 13:21 _z_Lucene103_0.tim
-rw-r--r--@  1 finnrobl  staff     147523 Jan 15 13:21 _z_Lucene103_0.tip
-rw-r--r--@  1 finnrobl  staff        178 Jan 15 13:21 _z_Lucene103_0.tmd
-rw-r--r--@  1 finnrobl  staff    2205946 Jan 15 13:21 _z_Lucene90_0.dvd
-rw-r--r--@  1 finnrobl  staff        312 Jan 15 13:21 _z_Lucene90_0.dvm
-rw-r--r--@  1 finnrobl  staff  512000108 Jan 15 13:27 _z_NativeEngines990KnnVectorsFormat_0.vec
-rw-r--r--@  1 finnrobl  staff        152 Jan 15 13:23 _z_NativeEngines990KnnVectorsFormat_0.vemf
-rw-r--r--@  1 finnrobl  staff        159 Jan 15 13:21 _z.fdm
-rw-r--r--@  1 finnrobl  staff    4201500 Jan 15 13:21 _z.fdt
-rw-r--r--@  1 finnrobl  staff       3000 Jan 15 13:21 _z.fdx
-rw-r--r--@  1 finnrobl  staff        883 Jan 15 13:23 _z.fnm
-rw-r--r--@  1 finnrobl  staff    1080908 Jan 15 13:21 _z.kdd
-rw-r--r--@  1 finnrobl  staff       9227 Jan 15 13:21 _z.kdi
-rw-r--r--@  1 finnrobl  staff        149 Jan 15 13:21 _z.kdm
-rw-r--r--@  1 finnrobl  staff        687 Jan 15 13:23 _z.si
drwxr-xr-x@ 22 finnrobl  staff        704 Jan 15 13:27 .
drwxr-xr-x@  5 finnrobl  staff        160 Jan 15 13:19 ..
-rw-r--r--@  1 finnrobl  staff        372 Jan 15 13:23 segments_4
-rw-r--r--@  1 finnrobl  staff          0 Jan 15 13:19 write.lock

Then rerun cluster.

------------------------------------------------------
            
Metric,Task,Value,Unit
Cumulative indexing time of primary shards,,0,min
Min cumulative indexing time across primary shards,,0,min
Median cumulative indexing time across primary shards,,0,min
Max cumulative indexing time across primary shards,,0,min
Cumulative indexing throttle time of primary shards,,0,min
Min cumulative indexing throttle time across primary shards,,0,min
Median cumulative indexing throttle time across primary shards,,0,min
Max cumulative indexing throttle time across primary shards,,0,min
Cumulative merge time of primary shards,,0,min
Cumulative merge count of primary shards,,0,
Min cumulative merge time across primary shards,,0,min
Median cumulative merge time across primary shards,,0,min
Max cumulative merge time across primary shards,,0,min
Cumulative merge throttle time of primary shards,,0,min
Min cumulative merge throttle time across primary shards,,0,min
Median cumulative merge throttle time across primary shards,,0,min
Max cumulative merge throttle time across primary shards,,0,min
Cumulative refresh time of primary shards,,0,min
Cumulative refresh count of primary shards,,2,
Min cumulative refresh time across primary shards,,0,min
Median cumulative refresh time across primary shards,,0,min
Max cumulative refresh time across primary shards,,0,min
Cumulative flush time of primary shards,,0,min
Cumulative flush count of primary shards,,0,
Min cumulative flush time across primary shards,,0,min
Median cumulative flush time across primary shards,,0,min
Max cumulative flush time across primary shards,,0,min
Total Young Gen GC time,,0,s
Total Young Gen GC count,,0,
Total Old Gen GC time,,0,s
Total Old Gen GC count,,0,
Store size,,1.1054795989766717,GB
Translog size,,5.122274160385132e-08,GB
Heap used for segments,,0,MB
Heap used for doc values,,0,MB
Heap used for terms,,0,MB
Heap used for norms,,0,MB
Heap used for points,,0,MB
Heap used for stored fields,,0,MB
Segment count,,1,
Min Throughput,warmup-indices,0.66,ops/s
Mean Throughput,warmup-indices,0.66,ops/s
Median Throughput,warmup-indices,0.66,ops/s
Max Throughput,warmup-indices,0.66,ops/s
100th percentile latency,warmup-indices,1508.5974170069676,ms
100th percentile service time,warmup-indices,1508.5974170069676,ms
error rate,warmup-indices,0.00,%
Min Throughput,prod-queries,104.01,ops/s
Mean Throughput,prod-queries,104.01,ops/s
Median Throughput,prod-queries,104.01,ops/s
Max Throughput,prod-queries,104.01,ops/s
50th percentile latency,prod-queries,2.3448540014214814,ms
90th percentile latency,prod-queries,3.596741388901138,ms
99th percentile latency,prod-queries,9.04737840974283,ms
100th percentile latency,prod-queries,322.0032500103116,ms
50th percentile service time,prod-queries,2.3448540014214814,ms
90th percentile service time,prod-queries,3.596741388901138,ms
99th percentile service time,prod-queries,9.04737840974283,ms
100th percentile service time,prod-queries,322.0032500103116,ms
error rate,prod-queries,0.00,%
Mean recall@k,prod-queries,0.56,
Mean recall@1,prod-queries,0.89,

So we can see that the recall is much lower in this case. 
(running with k=1000 centroids).



/Users/finnrobl/Documents/k-NN-2/vector-reorder/bp_files



What we need to do next is call into the bp reorder. 
./gradlew bpReorder -PvecFile=<path> -PoutputFaiss=<path> -PoutputVec=<path>

INDEX_DIR="/Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/6rmnxTydThuknJeeqW6nEw/0/index"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="/Users/finnrobl/Documents/k-NN-2/file-backups/${TIMESTAMP}-backups"
FAISS_FILE=$(ls "$INDEX_DIR"/*.faiss 2>/dev/null | head -1)
VEC_FILE=$(ls "$INDEX_DIR"/*_NativeEngines990KnnVectorsFormat_0.vec 2>/dev/null | head -1)

# Create backup directory and backup originals
mkdir -p "$BACKUP_DIR"
cp "$FAISS_FILE" "$BACKUP_DIR/"
cp "$VEC_FILE" "$BACKUP_DIR/"
echo "Backed up to: $BACKUP_DIR"

# Run reorder tool
cd /Users/finnrobl/Documents/k-NN-2/vector-reorder
./gradlew bpReorder \
  -PvecFile="$VEC_FILE" \
  -PoutputFaiss="${FAISS_FILE%.faiss}_reordered.faiss" \
  -PoutputVec="${VEC_FILE%.vec}_reordered.vec"
# Swap files
mv "$FAISS_FILE" "${FAISS_FILE}.old"
mv "${FAISS_FILE%.faiss}_reordered.faiss" "$FAISS_FILE"
mv "$VEC_FILE" "${VEC_FILE}.old"
mv "${VEC_FILE%.vec}_reordered.vec" "$VEC_FILE"

# Clean up .old files (OpenSearch will delete them anyway)
rm -f "${FAISS_FILE}.old" "${VEC_FILE}.old"

echo ""
echo "=== DONE ==="
echo "Backups at: $BACKUP_DIR"
echo ""
echo "Now kill and restart cluster."
