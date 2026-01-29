# Integrating .vord File for BP-Reordered Segments

## Overview

After BP reordering, the `.vemf` file contains an incorrect `ordToDoc` mapping (dense format assumes `ord == docId`). The correct mapping is stored in a separate `.vord` file containing `docToOrd[docId] = ord`.

This document describes the changes needed in k-NN to use the `.vord` file for correct vector lookups after BP reordering.

## Problem

When exact search needs to fetch a vector for a given `docId`:
1. Current code assumes `ord == docId` (dense case) or uses Lucene's `ordToDoc` mapping
2. After BP reorder, vector at position `ord` belongs to `docId = newOrder[ord]`
3. To find vector for `docId`, we need: `ord = docToOrd[docId]`

## Code Path Analysis

### 1. Entry Point: KNNWeight.searchLeaf()
**File:** `src/main/java/org/opensearch/knn/index/query/KNNWeight.java`
**Line:** ~307

```java
public PerLeafResult searchLeaf(LeafReaderContext context, int k) throws IOException {
    // ...
    if (isFilteredExactSearchPreferred(filterCardinality)) {
        final TopDocs result = doExactSearch(context, ...);  // <-- exact search path
    }
    // ...
    final TopDocs topDocs = approximateSearch(context, ...);  // <-- ANN search path (uses FAISS)
}
```

**ANN search** uses FAISS which has correct ID mapping - no changes needed.
**Exact search** uses Lucene vector values - needs .vord integration.

### 2. Exact Search: ExactSearcher.searchLeaf()
**File:** `src/main/java/org/opensearch/knn/index/query/ExactSearcher.java`
**Line:** ~65

```java
public TopDocs searchLeaf(final LeafReaderContext leafReaderContext, final ExactSearcherContext context) throws IOException {
    final KNNIterator iterator = getKNNIterator(leafReaderContext, context);  // <-- creates iterator
    // ...
}
```

### 3. Iterator Creation: ExactSearcher.getKNNIterator()
**File:** `src/main/java/org/opensearch/knn/index/query/ExactSearcher.java`
**Line:** ~165

```java
private KNNIterator getKNNIterator(LeafReaderContext leafReaderContext, ExactSearcherContext exactSearcherContext) throws IOException {
    // ...
    final KNNVectorValues<float[]> vectorValues = KNNVectorValuesFactory.getVectorValues(fieldInfo, reader);
    // ...
    return new VectorIdsKNNIterator(matchedDocs, queryVector, (KNNFloatVectorValues) vectorValues, spaceType, ...);
}
```

### 4. Vector Values Factory: KNNVectorValuesFactory.getVectorValues()
**File:** `src/main/java/org/opensearch/knn/index/vectorvalues/KNNVectorValuesFactory.java`
**Line:** ~97

```java
public static <T> KNNVectorValues<T> getVectorValues(final FieldInfo fieldInfo, final LeafReader leafReader) throws IOException {
    // ...
    if (fieldInfo.getVectorEncoding() == VectorEncoding.FLOAT32) {
        return getVectorValues(
            FieldInfoExtractor.extractVectorDataType(fieldInfo),
            new KNNVectorValuesIterator.DocIdsIteratorValues(leafReader.getFloatVectorValues(fieldInfo.getName()))
        );
    }
}
```

This calls `leafReader.getFloatVectorValues()` which returns Lucene's `FloatVectorValues`. The iterator assumes `ord == docId` in dense case.

### 5. KNNIterator Usage: VectorIdsKNNIterator
**File:** `src/main/java/org/opensearch/knn/index/query/iterators/VectorIdsKNNIterator.java`

The iterator calls `vectorValues.getVector()` with docId, expecting to get the vector for that doc.

## Changes Required

### Option A: Wrapper Approach (Minimal Changes)

Create a wrapper that intercepts vector lookups and applies the `docToOrd` mapping.

#### 1. New Class: ReorderedKNNVectorValues
**Location:** `src/main/java/org/opensearch/knn/index/vectorvalues/ReorderedKNNVectorValues.java`

```java
public class ReorderedKNNVectorValues<T> extends KNNVectorValues<T> {
    private final KNNVectorValues<T> delegate;
    private final int[] docToOrd;  // loaded from .vord file
    
    @Override
    public T getVector() throws IOException {
        int docId = docId();
        int ord = docToOrd[docId];
        // Need to position delegate at ord and return vector
    }
}
```

#### 2. Modify: KNNVectorValuesFactory.getVectorValues()
**File:** `src/main/java/org/opensearch/knn/index/vectorvalues/KNNVectorValuesFactory.java`

Add logic to detect if segment has `.vord` file and wrap the vector values:

```java
public static <T> KNNVectorValues<T> getVectorValues(final FieldInfo fieldInfo, final LeafReader leafReader) throws IOException {
    KNNVectorValues<T> vectorValues = ... // existing logic
    
    // Check for .vord file
    int[] docToOrd = loadDocToOrdIfExists(leafReader, fieldInfo);
    if (docToOrd != null) {
        return new ReorderedKNNVectorValues<>(vectorValues, docToOrd);
    }
    return vectorValues;
}
```

#### 3. New Utility: VordFileReader
**Location:** `src/main/java/org/opensearch/knn/index/codec/util/VordFileReader.java`

```java
public class VordFileReader {
    public static int[] readDocToOrd(Directory directory, String segmentName, String fieldName) throws IOException {
        String vordFileName = segmentName + "_" + fieldName + ".vord";
        if (!Arrays.asList(directory.listAll()).contains(vordFileName)) {
            return null;  // No .vord file, use default behavior
        }
        // Read and return docToOrd array
    }
}
```

### Option B: Iterator-Level Approach

Modify the KNNIterator implementations to handle the mapping.

#### 1. Modify: VectorIdsKNNIterator
**File:** `src/main/java/org/opensearch/knn/index/query/iterators/VectorIdsKNNIterator.java`

Add `docToOrd` parameter and apply mapping when fetching vectors.

#### 2. Modify: ExactSearcher.getKNNIterator()
Load `.vord` and pass to iterator constructors.

## File Detection

The `.vord` file should be named to match the segment:
- Pattern: `{segmentName}_{fieldSuffix}.vord`
- Example: `_0_NativeEngines990KnnVectorsFormat_0.vord`

Detection logic:
```java
SegmentInfo segmentInfo = reader.getSegmentInfo().info;
String segmentName = segmentInfo.name;
// Look for .vord files in segment directory
```

## Testing Strategy

1. **Unit Test:** Create segment with BP-reordered vectors and .vord file, verify exact search returns correct docIds
2. **Integration Test:** End-to-end test with reordered index, verify search results match expected

## Files to Modify

| File | Change |
|------|--------|
| `KNNVectorValuesFactory.java` | Add .vord detection and wrapping |
| `ExactSearcher.java` | Pass segment info for .vord lookup |
| New: `ReorderedKNNVectorValues.java` | Wrapper class |
| New: `VordFileReader.java` | .vord file reading utility |

## Open Questions

1. **Caching:** Should `docToOrd` be cached per segment? Memory vs. I/O tradeoff.
2. **File naming:** How to reliably match .vord file to field? Need consistent naming convention.
3. **Codec integration:** Should .vord be part of the codec or separate?
