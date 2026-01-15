# Hot-Swapping Reordered Index Files

This guide explains how to replace `.faiss` files in a running OpenSearch k-NN cluster with cluster-reordered versions for improved cache locality.

## Prerequisites

- Reordered `.faiss` file created via `FaissIndexRebuilder`
- Access to OpenSearch data directory
- Ability to stop/restart OpenSearch or close/open indices

## File Locations

OpenSearch stores k-NN index files at:
```
<data-path>/nodes/0/indices/<index-uuid>/<shard-id>/index/
├── _<segment>_NativeEngines990KnnVectorsFormat_0.vec   # Flat vectors (Lucene format)
├── _<segment>_NativeEngines990KnnVectorsFormat_0.vemf  # Vector metadata
└── _<segment>_<field>_<version>.faiss                  # FAISS HNSW index
```

## Method 1: Stop Cluster (Safest)

```bash
# 1. Stop OpenSearch
pkill -f opensearch
# or: ./bin/opensearch stop

# 2. Find target files
find <data-path> -name "*.faiss" -ls

# 3. Backup original
cp <segment>.faiss <segment>.faiss.bak

# 4. Replace with reordered file
cp sift128_reordered.faiss <segment>.faiss

# 5. Restart OpenSearch
./bin/opensearch
```

## Method 2: Close/Open Index (No Restart)

```bash
# 1. Close the index (releases file handles)
curl -X POST "localhost:9200/<index-name>/_close"

# 2. Replace the .faiss file
cp sift128_reordered.faiss <path-to-segment>.faiss

# 3. Reopen the index
curl -X POST "localhost:9200/<index-name>/_open"
```

## Method 3: Single-Segment Replacement

For indices with multiple segments, force merge first:

```bash
# 1. Force merge to single segment
curl -X POST "localhost:9200/<index-name>/_forcemerge?max_num_segments=1"

# 2. Flush to disk
curl -X POST "localhost:9200/<index-name>/_flush?force=true"

# 3. Close index
curl -X POST "localhost:9200/<index-name>/_close"

# 4. Find the single segment file
ls <data-path>/nodes/0/indices/<uuid>/0/index/*.faiss

# 5. Replace and reopen
cp sift128_reordered.faiss <segment>.faiss
curl -X POST "localhost:9200/<index-name>/_open"
```

## What About the .vec File?

| Scenario | .vec Handling |
|----------|---------------|
| ANN search only | No change needed - FAISS reads from `.faiss` |
| Exact search / rescoring | Must reorder `.vec` to match (complex) |
| Script score queries | Must reorder `.vec` to match |

The `.faiss` file contains its own copy of vectors plus the ID mapping (`IxMp`), so ANN search works correctly with just the `.faiss` replacement.

## Verification

After hot-swap, verify the index works:

```bash
# Check cluster health
curl "localhost:9200/_cluster/health?pretty"

# Check index status
curl "localhost:9200/<index-name>/_stats?pretty"

# Run a test search
curl -X POST "localhost:9200/<index-name>/_search?pretty" -H 'Content-Type: application/json' -d'
{
  "size": 5,
  "query": {
    "knn": {
      "<field-name>": {
        "vector": [0.1, 0.2, ...],
        "k": 5
      }
    }
  }
}'
```

## Troubleshooting

| Issue | Cause | Solution |
|-------|-------|----------|
| Index won't open | File size mismatch or corruption | Restore from backup |
| Search returns wrong results | ID mapping mismatch | Verify `newOrder[]` array is correct |
| Cluster health RED | Shard allocation failed | Check logs, restore backup |
| `FileNotFoundException` | Wrong file path | Verify segment name matches |

## Rollback

```bash
# If something goes wrong:
curl -X POST "localhost:9200/<index-name>/_close"
cp <segment>.faiss.bak <segment>.faiss
curl -X POST "localhost:9200/<index-name>/_open"
```

## Notes

- The reordered `.faiss` file must have the same structure (IxMp → IHNf → IxF2)
- Vector count and dimension must match exactly
- The ID mapping in the reordered file maps internal IDs back to original Lucene doc IDs
- No Lucene segment metadata changes are needed - only the `.faiss` binary content changes
