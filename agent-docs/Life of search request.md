# Life of a Vector Search Request in k-NN

This document traces the complete code flow of a vector search query through the OpenSearch k-NN plugin.

## Overview

```
User Query → KNNQueryBuilder → KNNQueryFactory → KNNQuery/NativeEngineKnnVectorQuery
           → KNNWeight → searchLeaf → doANNSearch/exactSearch
           → JNIService → Native Engine (Faiss/NMSLIB) → Results
```

## Where Lucene Segments Are Read

Lucene segment data is accessed at multiple points:

| What | Where | How |
|------|-------|-----|
| **Segment metadata** | `KNNWeight.searchLeaf()` | `Lucene.segmentReader(context.reader())` |
| **Field info** | `KNNWeight.approximateSearch()` | `FieldInfoExtractor.getFieldInfo(reader, field)` |
| **Native graph files** | `DefaultKNNWeight.doANNSearch()` | `KNNCodecUtil.getEngineFiles()` from `reader.getSegmentInfo().info` |
| **Vector values (exact search)** | `ExactSearcher.getKNNIterator()` | `KNNVectorValuesFactory.getVectorValues(fieldInfo, reader)` |
| **Filter docs** | `KNNWeight.getFilteredDocsBitSet()` | `filterWeight.scorer(ctx).iterator()` |
| **Live docs** | `NativeEngineKnnVectorQuery.searchLeaf()` | `ctx.reader().getLiveDocs()` |

The key distinction:
- **ANN Search**: Only reads segment metadata + loads native graph from `.hnsw`/`.faiss` files into memory cache
- **Exact Search**: Reads actual vector data from Lucene's stored fields via `LeafReader.getFloatVectorValues()` / `getBinaryDocValues()`

---

## 1. Query Parsing & Building

### KNNQueryBuilder.doToQuery()
**File:** `src/main/java/org/opensearch/knn/index/query/KNNQueryBuilder.java`

Entry point for k-NN queries. Parses the query DSL and creates the appropriate query object.

```
doToQuery()
  ├── Validates query parameters (k, vector, field)
  ├── Resolves vector data type and space type
  ├── Handles model-based indices
  ├── Transforms query vector if needed (e.g., cosine normalization)
  └── Calls KNNQueryFactory.create()
```

---

## 2. Query Factory

### KNNQueryFactory.create()
**File:** `src/main/java/org/opensearch/knn/index/query/KNNQueryFactory.java`

Creates the appropriate Lucene Query based on engine type.

```
create()
  ├── For Native Engines (Faiss, NMSLIB):
  │     ├── Creates KNNQuery with all parameters
  │     └── Wraps in NativeEngineKnnVectorQuery (if rescoring/nested)
  │
  └── For Lucene Engine:
        ├── Creates OSKnnFloatVectorQuery or OSKnnByteVectorQuery
        └── Wraps in RescoreKNNVectorQuery (if rescoring needed)
```

---

## 3. Query Execution (Native Engines)

### NativeEngineKnnVectorQuery.createWeight()
**File:** `src/main/java/org/opensearch/knn/index/query/nativelib/NativeEngineKnnVectorQuery.java`

Orchestrates the search across all segments.

```
createWeight()
  ├── Creates KNNWeight from KNNQuery
  ├── doSearch() - searches all leaf contexts
  │     └── For each segment: searchLeaf()
  ├── doRescore() - if rescoring enabled
  │     └── ExactSearcher for top candidates
  ├── retrieveAll() - if nested docs expansion
  ├── Merges TopDocs from all segments
  └── Returns DocAndScoreQuery weight
```

---

## 4. Weight & Scoring

### KNNWeight.searchLeaf()
**File:** `src/main/java/org/opensearch/knn/index/query/KNNWeight.java`

Per-segment search logic. **This is where Lucene segment access begins.**

```
searchLeaf()
  ├── SegmentReader reader = Lucene.segmentReader(context.reader())  ← LUCENE SEGMENT ACCESS
  │
  ├── getFilteredDocsBitSet() - applies filter query
  │     └── filterWeight.scorer(ctx).iterator()  ← reads filter matches from segment
  │
  ├── Check if exact search preferred:
  │     ├── filterCardinality <= k
  │     ├── filterThreshold setting
  │     └── maxDistComp threshold
  │
  ├── If exact search preferred:
  │     └── doExactSearch()
  │
  └── Otherwise:
        ├── approximateSearch() → doANNSearch()
        └── If results < k and more filtered docs:
              └── doExactSearch() (fallback)
```

### KNNWeight.approximateSearch()
**File:** `src/main/java/org/opensearch/knn/index/query/KNNWeight.java`

Prepares for ANN search by reading segment metadata.

```
approximateSearch()
  ├── SegmentReader reader = Lucene.segmentReader(context.reader())
  ├── FieldInfo fieldInfo = FieldInfoExtractor.getFieldInfo(reader, field)  ← SEGMENT FIELD METADATA
  │     └── Reads: engine, spaceType, vectorDataType, modelId from field attributes
  │
  ├── KNNCodecUtil.getEngineFiles(extension, field, reader.getSegmentInfo().info)
  │     └── Gets paths to native index files (.hnsw, .faiss) from segment info
  │
  └── doANNSearch(context, reader, fieldInfo, ...)
```

### DefaultKNNWeight.doANNSearch()
**File:** `src/main/java/org/opensearch/knn/index/query/DefaultKNNWeight.java`

Performs approximate nearest neighbor search using native libraries.

```
doANNSearch()
  ├── Get engine files from segment
  ├── loadGraph() - load/cache index in native memory
  │     └── NativeMemoryCacheManager.get()
  │           └── NativeMemoryLoadStrategy.IndexLoadStrategy
  │
  ├── Prepare filter IDs (BitSet or array)
  ├── Acquire read lock on index allocation
  │
  ├── JNIService.queryIndex() or queryBinaryIndex()
  │     ├── FaissService.queryIndex()
  │     └── NmslibService.queryIndex()
  │
  └── Collect results into TopDocs
```

---

## 5. JNI Layer

### JNIService.queryIndex()
**File:** `src/main/java/org/opensearch/knn/jni/JNIService.java`

Routes queries to the appropriate native engine.

```
queryIndex()
  ├── NMSLIB Engine:
  │     └── NmslibService.queryIndex()
  │
  └── FAISS Engine:
        ├── With filters:
        │     └── FaissService.queryIndexWithFilter()
        └── Without filters:
              └── FaissService.queryIndex()
```

### FaissService (Native Methods)
**File:** `src/main/java/org/opensearch/knn/jni/FaissService.java`

JNI bridge to Faiss C++ library.

```
Native Methods:
  ├── queryIndex(indexPointer, queryVector, k, params, parentIds)
  ├── queryIndexWithFilter(indexPointer, queryVector, k, params, filterIds, filterType, parentIds)
  ├── queryBinaryIndexWithFilter(...)
  └── radiusQueryIndex(...)
```

---

## 6. Native C++ Execution

### faiss_wrapper.cpp
**File:** `jni/src/faiss_wrapper.cpp`

C++ implementation that interfaces with Faiss.

```
knn_jni::faiss_wrapper::QueryIndex()
  ├── Cast index pointer to faiss::Index*
  ├── Set search parameters (efSearch, nprobe)
  ├── index->search(n, queryVector, k, distances, labels)
  └── Convert results to KNNQueryResult[]
```

### Faiss Index Search
**File:** `jni/external/faiss/faiss/`

Faiss library performs the actual vector search.

```
Index::search()
  ├── HNSW: IndexHNSW::search()
  │     └── hnsw.search() - graph traversal
  │
  ├── IVF: IndexIVF::search()
  │     ├── quantizer->search() - find clusters
  │     └── search_preassigned() - search within clusters
  │
  └── Flat: IndexFlat::search()
        └── exhaustive_inner_product/L2sqr()
```

---

## 7. Exact Search Path (Lucene Vector Reading)

### ExactSearcher.searchLeaf()
**File:** `src/main/java/org/opensearch/knn/index/query/ExactSearcher.java`

Brute-force search when ANN is not suitable. **This is where actual vector data is read from Lucene.**

```
searchLeaf()
  ├── getKNNIterator()  ← READS VECTORS FROM LUCENE SEGMENT
  │     │
  │     ├── SegmentReader reader = Lucene.segmentReader(leafReaderContext.reader())
  │     ├── FieldInfo fieldInfo = FieldInfoExtractor.getFieldInfo(reader, field)
  │     │
  │     └── KNNVectorValuesFactory.getVectorValues(fieldInfo, reader)
  │           │
  │           ├── If hasVectorValues (Lucene native):
  │           │     ├── reader.getFloatVectorValues(fieldName)  ← LUCENE VECTOR FORMAT
  │           │     └── reader.getByteVectorValues(fieldName)
  │           │
  │           └── Else (legacy DocValues):
  │                 └── DocValues.getBinary(reader, fieldName)  ← BINARY DOC VALUES
  │
  ├── Creates iterator:
  │     ├── VectorIdsKNNIterator (float)
  │     ├── ByteVectorIdsKNNIterator (byte)
  │     ├── BinaryVectorIdsKNNIterator (binary)
  │     └── Nested variants for nested fields
  │
  ├── For radial search:
  │     └── filterDocsByMinScore()
  │
  └── For k-NN search:
        └── searchTopCandidates() - heap-based top-k
```

### KNNVectorValuesFactory.getVectorValues()
**File:** `src/main/java/org/opensearch/knn/index/vectorvalues/KNNVectorValuesFactory.java`

Factory that reads vector data from Lucene segment.

```
getVectorValues(fieldInfo, leafReader)
  │
  ├── FLOAT32 encoding:
  │     └── leafReader.getFloatVectorValues(fieldName)
  │           └── Returns FloatVectorValues (Lucene 9.x+ vector format)
  │
  ├── BYTE encoding:
  │     └── leafReader.getByteVectorValues(fieldName)
  │           └── Returns ByteVectorValues
  │
  └── No vector values (legacy):
        └── DocValues.getBinary(leafReader, fieldName)
              └── Returns BinaryDocValues (stored as binary blob)
```

---

## 8. Memory Optimized Search Path

### MemoryOptimizedKNNWeight.doANNSearch()
**File:** `src/main/java/org/opensearch/knn/index/query/memoryoptsearch/MemoryOptimizedKNNWeight.java`

Alternative search path that reads vectors directly from disk.

```
doANNSearch()
  ├── Load FaissIndex from segment files
  ├── Get VectorSearcher from index
  ├── Create KnnCollector
  └── vectorSearcher.search() - Lucene-style search
```

---

## 9. Result Processing

### Back in NativeEngineKnnVectorQuery

```
After searchLeaf() returns:
  ├── Filter by liveDocs (handle deletions)
  ├── Rescore if RescoreContext present
  │     └── ExactSearcher on top candidates
  ├── Expand nested docs if needed
  ├── Merge TopDocs from all segments
  ├── ResultUtil.reduceToTopK()
  └── Create DocAndScoreQuery for final scoring
```

---

## Key Classes Summary

| Class | Responsibility |
|-------|---------------|
| `KNNQueryBuilder` | Parse query DSL, validate parameters |
| `KNNQueryFactory` | Create appropriate Query object |
| `KNNQuery` | Holds query parameters |
| `NativeEngineKnnVectorQuery` | Orchestrate multi-segment search |
| `KNNWeight` | Per-segment search logic |
| `DefaultKNNWeight` | Native library search via JNI |
| `MemoryOptimizedKNNWeight` | Disk-based search |
| `ExactSearcher` | Brute-force search |
| `JNIService` | Route to native engines |
| `FaissService` | JNI bridge to Faiss |
| `NmslibService` | JNI bridge to NMSLIB |

---

## Search Decision Flow

```
Is filter present?
  │
  ├─ Yes: Get filtered doc IDs
  │   │
  │   ├─ filteredCount <= k? → Exact Search
  │   ├─ filteredCount <= threshold? → Exact Search
  │   └─ Otherwise → ANN Search with filter
  │
  └─ No: 
      │
      ├─ Native engine files exist? 
      │   ├─ Yes → ANN Search
      │   └─ No → Exact Search
      │
      └─ ANN results < k && more docs available?
            └─ Yes → Fallback to Exact Search
```
