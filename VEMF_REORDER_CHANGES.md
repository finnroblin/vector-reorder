# Changes Needed for .vemf File Reordering

## Problem Summary

From `SortingCodec.md`: BpReorderTool currently updates `.vec` and `.faiss` but **not** `.vemf`. This breaks exact search because the `ordToDoc` mapping in `.vemf` no longer matches the reordered vectors.

## .vemf File Structure

Based on `ParseVemf.java` and Lucene99FlatVectorsFormat:

```
.vemf file:
├── Codec Header (magic + codec name + version + object id)
├── Field Name Header
├── fieldNumber (int32)
├── vectorEncoding (int32)
├── similarityFunction (int32)
├── vectorDataOffset (vlong)
├── vectorDataLength (vlong)
├── dimension (vint)
├── size (int32)
├── OrdToDoc Configuration:
│   ├── docsWithFieldOffset (long)  -- -2=empty, -1=dense, >=0=sparse
│   ├── docsWithFieldLength (long)
│   ├── jumpTableEntryCount (short)
│   └── denseRankPower (byte)
│   [If sparse:]
│   ├── ordToDocOffset (long)
│   ├── blockShift (vint)
│   ├── DirectMonotonicReader.Meta
│   └── ordToDocLength (long)
├── End marker (int32 = -1)
└── Codec Footer
```

## OrdToDoc Modes

The `docsWithFieldOffset` field indicates the mapping type:
- `-2`: EMPTY (no vectors)
- `-1`: DENSE (ordinal == docID, identity mapping)
- `>= 0`: SPARSE (explicit ordToDoc mapping stored in .vec file)

## Implementation

### Files Modified

1. **VemfFileIO.java** (NEW): Handles .vemf file reading and rewriting
   - `readMetadata()`: Parse .vemf header
   - `writeReordered()`: Rewrite .vemf with new ordToDoc mapping

2. **BpReorderTool.java**: Updated to also rewrite .vemf
   - Added optional 5th argument for output .vemf path
   - Calls `VemfFileIO.writeReordered()` after writing .vec

### Key Logic

After BP reordering with permutation `newOrder[newIdx] = oldIdx`:

1. **Vector at new ordinal `i`** came from old ordinal `newOrder[i]`
2. **In dense case**: old ordinal == old docId
3. **Therefore**: `newOrdToDoc[i] = newOrder[i]`

The reordered .vemf converts from dense to sparse format because:
- Original: ordinal == docId (identity)
- After reorder: ordinal != docId (need explicit mapping)

### Data Flow

```
Original (Dense):
  ord 0 → docId 0 → vector A
  ord 1 → docId 1 → vector B
  ord 2 → docId 2 → vector C

After BP Reorder (newOrder = [2, 0, 1]):
  new ord 0 → vector C (from old ord 2) → docId 2
  new ord 1 → vector A (from old ord 0) → docId 0
  new ord 2 → vector B (from old ord 1) → docId 1

New ordToDoc mapping (Sparse):
  newOrdToDoc[0] = 2
  newOrdToDoc[1] = 0
  newOrdToDoc[2] = 1
```

## Usage

```bash
# Old usage (missing .vemf):
BpReorderTool <vec-path> <input-faiss> <output-faiss> <output-vec>

# New usage (includes .vemf):
BpReorderTool <vec-path> <input-faiss> <output-faiss> <output-vec> [output-vemf]

# If output-vemf not provided, defaults to output-vec with .vemf extension
```

## Files Updated

| File | Contains | Update |
|------|----------|--------|
| `.vec` | Raw vectors | ✅ Reorder vectors |
| `.vemf` | ordToDoc mapping | ✅ **NEW: Convert dense→sparse** |
| `.faiss` | HNSW graph + ID mapping | ✅ Rebuild with new ID mapping |

## Verification

After reordering, exact search should work correctly:
1. Query requests docId X
2. .vemf ordToDoc mapping finds ordinal for docId X
3. .vec file returns vector at that ordinal
4. Vector matches what FAISS returns for docId X
