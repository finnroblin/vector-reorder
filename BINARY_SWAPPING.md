# Binary Index Hotswap Guide

## Overview

After running `e2eBpReorder`, you have BP-reordered k-NN files in `/tmp/reordered/`. To test, swap these into the running OpenSearch index.

## SortingCodecReader Approach (Recommended)

Lucene's `SortingCodecReader` can rewrite an entire segment with documents in BP order. This is the cleanest approach.

### What Works

```bash
./gradlew sortingBpReorder \
    -PsrcDir=/path/to/index \
    -PdstDir=/tmp/sorted_index \
    -PvectorField=train
```

This successfully:
- Reads the k-NN index (requires k-NN jar + guava)
- Computes BP ordering from vectors
- Rewrites all segment data in BP order
- Writes vectors with Lucene's native HNSW format

### Limitation: No FAISS Output

The k-NN codec (`NativeEngines990KnnVectorsFormat`) requires OpenSearch runtime:
- `ClusterService` for settings
- `NativeMemoryCacheManager` for index loading

Without OpenSearch, we can only use standard Lucene codecs, which produce Lucene HNSW indexes (`.vex`) instead of FAISS indexes (`.faiss`).

### Options

1. **Run within OpenSearch** - Use the SortingCodecReader approach inside an OpenSearch plugin or script that has access to the full runtime.

2. **Two-phase approach** (current `e2eBpReorder`):
   - Phase 1: Compute BP ordering and reorder vectors
   - Phase 2: Build FAISS index separately via JNI
   - Requires `.vord` file for doc-to-ord mapping

3. **Modify k-NN codec** - Remove OpenSearch dependencies from the codec to allow standalone use.

---

## Current Approach: Manual File Swap

## Files Produced by e2eBpReorder

| File | Description |
|------|-------------|
| `_13_165_train.faiss` | Reordered binary HNSW index (IBMp) |
| `_13_NativeEngines990KnnVectorsFormat_0.vec` | Reordered float vectors |
| `_13_NativeEngines990KnnVectorsFormat_0.vemf` | Vector metadata (dense format) |
| `_13_NativeEngines990KnnVectorsFormat_0.vord` | Doc-to-ord mapping (NEW file) |
| `_13_NativeEngines990KnnVectorsFormat_0.osknnqstate` | Quantization state (copied unchanged) |

## Files to Swap

Replace these files in the index directory:

```
/Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/d2IuSy-tScG2vzrEzugaZw/0/index/
```

| Original File | Replacement |
|---------------|-------------|
| `_13_165_train.faiss` | `/tmp/reordered/_13_165_train.faiss` |
| `_13_NativeEngines990KnnVectorsFormat_0.vec` | `/tmp/reordered/_13_NativeEngines990KnnVectorsFormat_0.vec` |
| `_13_NativeEngines990KnnVectorsFormat_0.vemf` | `/tmp/reordered/_13_NativeEngines990KnnVectorsFormat_0.vemf` |
| (none) | `/tmp/reordered/_13_NativeEngines990KnnVectorsFormat_0.vord` (ADD) |

**Do NOT replace:**
- `.osknnqstate` - unchanged, but can copy for consistency
- Lucene segment files (`_13.si`, `_13.fnm`, `_13.fdm`, etc.)
- `segments_5` - segment metadata

## Swap Procedure

1. **Stop OpenSearch** (or ensure index is not being accessed)

2. **Backup originals:**
   ```bash
   INDEX_DIR=/Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/d2IuSy-tScG2vzrEzugaZw/0/index
   mkdir -p /tmp/backup
   cp $INDEX_DIR/_13_165_train.faiss /tmp/backup/
   cp $INDEX_DIR/_13_NativeEngines990KnnVectorsFormat_0.vec /tmp/backup/
   cp $INDEX_DIR/_13_NativeEngines990KnnVectorsFormat_0.vemf /tmp/backup/
   ```

3. **Copy reordered files:**
   ```bash
   cp /tmp/reordered/_13_165_train.faiss $INDEX_DIR/
   cp /tmp/reordered/_13_NativeEngines990KnnVectorsFormat_0.vec $INDEX_DIR/
   cp /tmp/reordered/_13_NativeEngines990KnnVectorsFormat_0.vemf $INDEX_DIR/
   cp /tmp/reordered/_13_NativeEngines990KnnVectorsFormat_0.vord $INDEX_DIR/
   ```

4. **Start OpenSearch** and run k-NN queries

## Important Notes

### The .vord File

The `.vord` file is a **new file type** that maps document IDs to vector ordinals after BP reordering. The current k-NN codec does NOT read this file - it assumes ordinals match document IDs.

**To fully support BP reordering, the k-NN codec must be modified to:**
1. Check for `.vord` file existence
2. If present, use `docToOrd[docId]` to look up vectors instead of direct ordinal access

### Without Codec Changes

Without codec changes, swapping will cause **incorrect results** because:
- Vector at ordinal `i` now belongs to a different document
- The FAISS index returns ordinals that no longer match document IDs

### Testing Strategy

For initial testing without codec changes:
1. Verify files load without errors
2. Run k-NN queries and check they return results (even if wrong)
3. Compare FAISS index structure (should have same graph topology)

For correct results, implement `.vord` support in the k-NN codec.
