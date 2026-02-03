# BP Reordering: How Lucene Does It vs. BpReorderTool

## The Problem

BpReorderTool hotswaps `.vec` and rebuilds `.faiss`, but doesn't update `.vemf`. This breaks exact search because the `ordToDoc` mapping in `.vemf` no longer matches the reordered vectors.

## Lucene's Approach

### Key Data Structures

**Sorter.DocMap** - The BP permutation:
- `oldToNew(oldDocId)` → newDocId  
- `newToOld(newDocId)` → oldDocId

**SortingCodecReader** - Wraps a reader to present vectors in new order.

### How SortingFloatVectorValues Works

```java
// In SortingCodecReader.iteratorSupplier():
for (int doc = iter.nextDoc(); doc != NO_MORE_DOCS; doc = iter.nextDoc()) {
    int newDocId = docMap.oldToNew(doc);  // doc is OLD docId
    docToOrd[newDocId] = iter.index();    // iter.index() is OLD ordinal
}
```

This builds `docToOrd[newDocId] = oldOrdinal`.

Then when iterating:
```java
// SortingValuesIterator.index():
public int index() {
    return docToOrd[doc];  // doc is NEW docId, returns OLD ordinal
}
```

### What Gets Written During Merge

In `Lucene99FlatVectorsWriter.writeVectorData()`:
```java
for (int docV = iter.nextDoc(); docV != NO_MORE_DOCS; docV = iter.nextDoc()) {
    float[] value = floatVectorValues.vectorValue(iter.index());  // fetch from OLD ordinal
    output.writeBytes(...);  // write to NEW position
    docsWithField.add(docV); // record NEW docId
}
```

**Result:**
- `.vec`: Vectors written in new BP order
- `.vemf`: `ordToDoc` maps new ordinals to new docIds (identity for dense case)
- Both files are consistent

## k-NN's Merge Path

k-NN already uses Lucene's infrastructure:

```java
// NativeEngines990KnnVectorsWriter.mergeOneField():
flatVectorsWriter.mergeOneField(fieldInfo, mergeState);  // writes .vec/.vemf
writer.mergeIndex(mergeState, ...);                       // builds .faiss
```

Both use `MergeState` which contains `SortingCodecReader`-wrapped readers when BP reordering is active. Vectors are iterated in new order for both files.

## BpReorderTool's Problem

Current approach:
1. Read `.vec` vectors
2. Compute BP permutation
3. Write reordered `.vec`
4. Rebuild `.faiss` with reordered vectors

**Missing:** The `.vemf` file still has the old `ordToDoc` mapping.

In dense case (all docs have vectors):
- Old: `ordToDoc(ord) = ord` (identity)
- After reorder: `.vec` ordinal 0 now contains a different vector
- But `.vemf` still says ordinal 0 → docId 0
- Exact search reads wrong vector for docId 0

## The Fix

BpReorderTool must also update `.vemf` to maintain consistency:

| File | Contains | Must Update |
|------|----------|-------------|
| `.vec` | Raw vectors | ✅ Reorder |
| `.vemf` | `ordToDoc` mapping | ✅ **Must update** |
| `.faiss` | HNSW graph + ID mapping | ✅ Rebuild |

The new `ordToDoc` after reordering should map:
- new ordinal → new docId (which equals new ordinal in dense case, so still identity)

But the vectors at each ordinal have changed, so the file must be rewritten to reflect the new vector positions.
