# BP Reorder Contract: Ensuring Correct Exact Search

## The Problem

When BpReorderTool reorders vectors for better cache locality, exact search breaks because the vector lookup path doesn't account for the reordering.

### Exact Search Flow (KNNWeight → ExactSearcher)

```
KNNWeight.exactSearch()
  → ExactSearcher.searchLeaf()
    → VectorIdsKNNIterator.nextDoc()  // returns docId
    → VectorIdsKNNIterator.score()
      → computeScore()
        → knnFloatVectorValues.getVector()
          → VectorValueExtractorStrategy.extractFloatVector()
            → extractFromKnnVectorValues()
              → ord = docIdSetIterator.index()     // ← KEY: gets ordinal
              → knnVectorValues.vectorValue(ord)   // ← reads vector at ord
```

**The critical line:** `knnVectorValues.vectorValue(ord)` reads the vector at position `ord` from the `.vec` file.

### What Lucene's BP Reorder Does (During Merge)

When Lucene reorders during merge, it **renumbers all docIds**:

```java
// sortMap.oldToNew: 0→1, 1→2, 2→0
// Meaning: oldDocId 2 becomes newDocId 0 (first in new order)

// After reorder:
// - newDocId 0 contains what was oldDocId 2's data
// - newDocId 1 contains what was oldDocId 0's data  
// - newDocId 2 contains what was oldDocId 1's data
```

The entire segment operates in the **new docId space**. Queries use new docIds, so lookups work correctly.

### What BpReorderTool Does (Post-hoc Hotswap)

BpReorderTool reorders vectors **without renumbering docIds**:

```
Original:
  ord 0 (docId 0): [1.0, 0.0]
  ord 1 (docId 1): [0.0, 1.0]
  ord 2 (docId 2): [1.0, 1.0]

After BP reorder (newOrder = [2, 0, 1]):
  ord 0: [1.0, 1.0]  ← was docId 2's vector
  ord 1: [1.0, 0.0]  ← was docId 0's vector
  ord 2: [0.0, 1.0]  ← was docId 1's vector

But docIds are NOT renumbered!
  Query for docId 0 → ord 0 → [1.0, 1.0] ✗ WRONG (expected [1.0, 0.0])
```

## The Contract

For exact search to work correctly after BP reordering:

```
Given: docId (from query/filter)
Required: Return the vector that was originally indexed for that docId
```

### Solution: docToOrd Mapping

After reordering, we need a mapping from docId to the new ordinal:

```
newOrder[newOrd] = oldOrd (which equals docId in dense case)

Inverse: docToOrd[docId] = newOrd where newOrder[newOrd] == docId

Example:
  newOrder = [2, 0, 1]  // newOrd 0 came from oldOrd 2, etc.
  
  docToOrd[0] = 1  // docId 0's vector is now at ord 1
  docToOrd[1] = 2  // docId 1's vector is now at ord 2
  docToOrd[2] = 0  // docId 2's vector is now at ord 0
```

### Lookup Flow After Reorder

```
Query for docId 0:
  1. ord = docToOrd[0] = 1
  2. vector = vectorValue(1) = [1.0, 0.0] ✓ CORRECT
```

## Why .vemf Reordering Won't Work

The `.vemf` file stores `ordToDoc` (ordinal → docId) using `DirectMonotonicWriter`, which **enforces monotonically increasing values**:

```java
// DirectMonotonicWriter.add():
public void add(long v) throws IOException {
    if (v < previous) {
        throw new IllegalArgumentException("Values do not come in order: " + previous + ", " + v);
    }
    // ...
}
```

After BP reorder with `newOrder = [2, 0, 1]`:
- ord 0 contains docId 2's vector → `ordToDoc[0] = 2`
- ord 1 contains docId 0's vector → `ordToDoc[1] = 0`  
- ord 2 contains docId 1's vector → `ordToDoc[2] = 1`

The sequence `[2, 0, 1]` is **not monotonic** (2 > 0), so `DirectMonotonicWriter` throws an exception.

### Why Lucene's Merge Works

During merge, `mapOldOrdToNewOrd` **sorts the new docIds** before building `newDocsWithField`:

```java
Arrays.sort(newDocIds);  // ← KEY: sorts to ensure monotonicity

for (int newDocId : newDocIds) {
    newDocsWithField.add(newDocId);  // Added in sorted order
}
```

Then `writeStoredMeta` iterates `docsWithField` which yields docIds in sorted order, making `ordToDoc` monotonic.

### The Fundamental Difference

| Approach | DocIds | ordToDoc | Works? |
|----------|--------|----------|--------|
| Lucene merge | Renumbered | Monotonic (sorted) | ✓ |
| BpReorderTool hotswap | Original | Non-monotonic | ✗ |

BpReorderTool keeps original docIds, so `ordToDoc` after reorder is the permutation itself, which is not monotonic.

## Current Implementation

### Files Produced by BpReorderTool

| File | Contents | Purpose |
|------|----------|---------|
| `.vec` | Vectors in BP order | Cache-friendly ANN search |
| `.vemf` | Dense format (ord==docId) | Lucene metadata compatibility |
| `.vord` | `docToOrd[docId] = ord` | **Correct exact search lookups** |
| `.faiss` | HNSW graph + ID mapping | ANN search with correct docId returns |

### .vord File Format

```
Header (CodecUtil)
int count
int[count] docToOrd  // docToOrd[docId] = ord
Footer (CodecUtil)
```

## Integration Requirements

### Option 1: Modify VectorValueExtractorStrategy

The cleanest fix is to intercept the ordinal lookup:

```java
// In extractFromKnnVectorValues():
int ord = docIdSetIterator.index();

// If BP reordered, translate docId → ord
if (docToOrdMapping != null) {
    int docId = docIdSetIterator.docID();
    ord = docToOrdMapping[docId];
}

return knnVectorValues.vectorValue(ord);
```

### Option 2: Custom KnnVectorValues Wrapper

Wrap the vector values to intercept `vectorValue(ord)`:

```java
class ReorderedFloatVectorValues extends FloatVectorValues {
    private final FloatVectorValues delegate;
    private final int[] docToOrd;
    
    @Override
    public float[] vectorValue(int ord) throws IOException {
        // ord here is actually docId from the iterator
        int actualOrd = docToOrd[ord];
        return delegate.vectorValue(actualOrd);
    }
}
```

### Option 3: Modify Iterator to Return Correct Ord

Make the iterator's `index()` method return the translated ordinal:

```java
class ReorderedDocIndexIterator extends DocIndexIterator {
    private final int[] docToOrd;
    
    @Override
    public int index() {
        int docId = docID();
        return docToOrd[docId];  // Return translated ord
    }
}
```

## Verification Test

```java
// After BP reorder with newOrder = [2, 0, 1]
float[][] originalVectors = {
    {1.0f, 0.0f},  // docId 0
    {0.0f, 1.0f},  // docId 1
    {1.0f, 1.0f},  // docId 2
};

// Verify exact search returns correct vectors
for (int docId = 0; docId < 3; docId++) {
    float[] found = exactSearch(docId);
    float[] expected = originalVectors[docId];
    assert Arrays.equals(found, expected) : 
        "docId " + docId + ": expected " + Arrays.toString(expected) + 
        ", got " + Arrays.toString(found);
}
```

## Key Insight

Lucene's BP reorder works because it **renumbers docIds** during merge - the entire segment uses the new docId space.

BpReorderTool's hotswap approach keeps the original docIds, so we must provide a translation layer (`docToOrd`) to map from docId to the new vector position.

The `.vord` file provides this mapping. The integration point is where `vectorValue(ord)` is called - we need to translate the docId to the correct ordinal before fetching the vector.
