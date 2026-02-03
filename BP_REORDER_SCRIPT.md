# BP Reorder for k-NN Segment

Reorders a k-NN segment using BP (Bipartite Graph Partitioning) algorithm for better cache locality during search.

## Build

```bash
cd /path/to/vector-reorder

# Build Java
./gradlew build

# Build JNI (requires FAISS)
cd jni && mkdir -p build && cd build && cmake .. && make -j4
```

## Run

```bash
./gradlew e2eBpReorder \
    -PsegmentDir=/path/to/index/0/index \
    -PvectorField=my_vector \
    -PoutputDir=/tmp/reordered
```

### Parameters

| Parameter | Description |
|-----------|-------------|
| `segmentDir` | Directory containing k-NN segment files |
| `vectorField` | Name of the vector field |
| `outputDir` | Output directory for reordered files |
| `threads` | (Optional) Thread count, default: all CPUs |

## Output

```
/tmp/reordered/
├── _0.si, _0.cfs, ...              # Reordered Lucene segment
├── _0_NativeEngines990KnnVectorsFormat_0.vec      # Float vectors (for rescoring)
├── _0_NativeEngines990KnnVectorsFormat_0.vemf     # Metadata
├── _0_NativeEngines990KnnVectorsFormat_0.vord     # docId→ord mapping
├── _0_NativeEngines990KnnVectorsFormat_0.osknnqstate  # Quantization state (if present)
└── _0_*_train.faiss                # HNSW index (binary if quantized)
```

## Hotswap into Cluster

```bash
# 1. Flush the index
POST /<index>/_flush?force=true

# 2. Backup original segment
BACKUP=/path/to/segment/_backup_$(date +%Y%m%d_%H%M%S)
mkdir -p $BACKUP
cp /path/to/segment/_* $BACKUP/

# 3. Remove old segment, copy new files
rm /path/to/segment/_*
cp /tmp/reordered/* /path/to/segment/

# 4. Refresh
POST /<index>/_refresh
```

## Quantization

If `.osknnqstate` exists in the source segment:
- Reads 1-bit scalar quantization thresholds
- Builds `IndexBinaryHNSW` instead of `IndexHNSWFlat`
- Float vectors still written to `.vec` for rescoring
