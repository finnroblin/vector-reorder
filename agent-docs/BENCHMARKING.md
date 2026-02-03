# Benchmarking and Reorder Commands

## K-Means Reorder

Clusters vectors using k-means and reorders them by cluster assignment. Optionally rebuilds FAISS indices.

Each .vec file is processed independently. If .faiss files are specified, they must match 1:1 with .vec files by position.

### Usage

```bash
./gradlew kmeansReorder -Pvec=<file1.vec>[,<file2.vec>...] [-Pfaiss=<file1.faiss>[,<file2.faiss>...]] [options]
```

### Options

| Option | Gradle Property | Description | Default |
|--------|-----------------|-------------|---------|
| `--vec` | `-Pvec` | Comma-separated .vec files (required) | - |
| `--faiss` | `-Pfaiss` | Comma-separated .faiss files (optional, must match .vec count) | - |
| `--space` | `-Pspace` | Space type: `l2` or `innerproduct` | `l2` |
| `--ef-search` | `-PefSearch` | ef_search parameter for FAISS HNSW | `100` |
| `--ef-construction` | `-PefConstruction` | ef_construction parameter for FAISS HNSW | `100` |
| `--m` | `-Pm` | M parameter for FAISS HNSW | `16` |

### Examples

```bash
# Single .vec file, no FAISS rebuild
./gradlew kmeansReorder -Pvec=/path/to/vectors.vec

# Multiple .vec files (each processed independently)
./gradlew kmeansReorder -Pvec=file1.vec,file2.vec

# With matching FAISS files (file1.vec pairs with file1.faiss, etc.)
./gradlew kmeansReorder -Pvec=file1.vec,file2.vec -Pfaiss=file1.faiss,file2.faiss

# With custom HNSW parameters
./gradlew kmeansReorder -Pvec=vectors.vec -Pfaiss=index.faiss -Pspace=l2 -PefSearch=200 -PefConstruction=128 -Pm=32
```

### Output

- `*_reordered.vec` - Vectors reordered by cluster assignment
- `*_reordered.faiss` - Rebuilt FAISS index (only if `-Pfaiss` specified)

---

## BP Reorder

Reorders vectors using the BP (Block Placement) algorithm for improved cache locality. Optionally rebuilds FAISS indices.

Each .vec file is processed independently. If .faiss files are specified, they must match 1:1 with .vec files by position.

### Usage

```bash
./gradlew bpReorder -Pvec=<file1.vec>[,<file2.vec>...] [-Pfaiss=<file1.faiss>[,<file2.faiss>...]] [options]
```

### Options

| Option | Gradle Property | Description | Default |
|--------|-----------------|-------------|---------|
| `--vec` | `-Pvec` | Comma-separated .vec files (required) | - |
| `--faiss` | `-Pfaiss` | Comma-separated .faiss files (optional) | - |
| `--space` | `-Pspace` | Space type: `l2` or `innerproduct` | `l2` |
| `--ef-search` | `-PefSearch` | ef_search parameter for FAISS HNSW | `100` |
| `--ef-construction` | `-PefConstruction` | ef_construction parameter for FAISS HNSW | `100` |
| `--m` | `-Pm` | M parameter for FAISS HNSW | `16` |

### Examples

```bash
# Single .vec file, no FAISS rebuild
./gradlew bpReorder -Pvec=/path/to/vectors.vec

# Multiple .vec files
./gradlew bpReorder -Pvec=file1.vec,file2.vec

# With FAISS rebuild
./gradlew bpReorder -Pvec=vectors.vec -Pfaiss=index.faiss

# With custom HNSW parameters
./gradlew bpReorder -Pvec=vectors.vec -Pfaiss=index.faiss -Pspace=l2 -PefSearch=200 -PefConstruction=128 -Pm=32
```

### Output

- `*_reordered.vec` - Vectors in BP order
- `*_reordered.vemf` - Updated Lucene metadata (if .vemf exists)
- `*_reordered.faiss` - Rebuilt FAISS index (only if `-Pfaiss` specified)
- `.osknnqstate` - Copied unchanged (if present)

---

## Running Tests

```bash
# Test k-means reorder
./gradlew testKmeansReorder

# Test BP reorder
./gradlew testBpReorder

# Test cluster sorting
./gradlew runClusterSortTest
```

---

## HNSW Parameters

| Parameter | Description | Typical Range |
|-----------|-------------|---------------|
| `m` | Number of neighbors per node in HNSW graph | 8-64 |
| `ef_construction` | Size of dynamic candidate list during index construction | 100-500 |
| `ef_search` | Size of dynamic candidate list during search | 100-500 |

Higher values improve recall but increase memory and time.

# Reordering E2E


./gradlew kmeansReorder -Pvec=vectors.vec -Pfaiss=index.faiss -Pspace=l2 -PefSearch=100 -PefConstruction=100 -Pm=16

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
