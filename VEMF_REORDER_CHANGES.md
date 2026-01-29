# VEMF Reorder Changes

## Problem

After BP reordering, vectors in `.vec` are in a different order than docIds. The `.vemf` file contains an `ordToDoc` mapping that tells the reader which docId owns each vector ordinal.

In the original (dense) case:
- `ordToDoc[ord] = ord` (identity mapping)
- Vector at position `ord` belongs to `docId = ord`

After BP reorder with permutation `newOrder`:
- Vector at position `newOrd` came from `oldOrd = newOrder[newOrd]`
- In dense case: `oldOrd == docId`
- So: `ordToDoc[newOrd] = newOrder[newOrd]`

## The Monotonicity Problem

Lucene stores `ordToDoc` using `DirectMonotonicWriter`, which requires values to be monotonically increasing. This works because Lucene writes vectors in docId order, so `ordToDoc[0] < ordToDoc[1] < ...`.

After BP reorder, `ordToDoc` values are a permutation of `[0, 1, ..., n-1]`, which is NOT monotonic (unless it's the identity permutation).

**There is no way to store a non-monotonic mapping with DirectMonotonicWriter.**

## How Lucene Handles This (SortingCodecReader)

During merge with `BPReorderingMergePolicy`, Lucene uses `SortingCodecReader` to reorder the ENTIRE segment:
1. All fields (inverted index, stored fields, vectors) get new docIds
2. Vectors are written in NEW docId order
3. After merge, `ordToDoc` is identity again (dense)

This works because the entire segment is rewritten consistently.

## Our Hotswap Approach

For experimentation, we want to hotswap segment files without rebuilding the entire segment. This means:
- Inverted index keeps OLD docIds
- Vectors are in BP order (different from docId order)
- We need a mapping to translate docId -> vector position

### Solution: Separate .vord File

We create a new `.vord` file containing `docToOrd` mapping:
- `docToOrd[docId] = ord` (position in reordered `.vec`)
- This is the inverse of `ordToDoc`

The `.vemf` file is written in dense format (claiming `ord == docId`), which is technically incorrect but structurally valid.

### Output Files After Reorder

| File | Contents | Notes |
|------|----------|-------|
| `.faiss` | HNSW index with BP-ordered vectors | ID mapping: faissId -> docId (correct) |
| `.vec` | Vectors in BP order | Position `ord` has vector from `docId = newOrder[ord]` |
| `.vemf` | Lucene metadata (dense format) | Claims `ord == docId` (INCORRECT) |
| `.vord` | `docToOrd` mapping | `docToOrd[docId] = ord` (CORRECT) |

### Usage After Reorder

**For ANN search:** Use FAISS directly. It has the correct ID mapping.

**For exact search (if needed):**
1. Load `docToOrd` from `.vord`
2. Given `docId`, compute `ord = docToOrd[docId]`
3. Read vector at position `ord` from `.vec`

### Limitations

- The `.vemf` file is not usable by standard Lucene readers for exact search
- A custom reader would be needed to use the `.vord` mapping
- This is intended for experimentation, not production use

## Alternative Approaches Considered

1. **Store ordToDoc as plain int array** - Wastes space, requires custom reader
2. **Store docToOrd with DirectMonotonic** - Values are still non-monotonic
3. **Full segment rewrite** - Correct but defeats hotswap purpose
4. **Use FAISS for all vector access** - Viable but changes read path significantly

## File Format: .vord

```
Header:
  - Magic (4 bytes, big-endian): 0x3fd76c17
  - Codec name (string): "OpenSearchVectorOrdMapping"
  - Version (4 bytes, big-endian): 0
  - Segment ID (16 bytes)
  - Suffix length (1 byte) + suffix bytes

Data:
  - Count (4 bytes, int)
  - docToOrd array (count * 4 bytes, int[])

Footer:
  - Lucene checksum footer
```
