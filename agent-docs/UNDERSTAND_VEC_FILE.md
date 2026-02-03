# Understanding .vec and .faiss File Relationship in k-NN

## Purpose
Investigate how the k-NN repo uses `.vec` files (Lucene99FlatVectorsFormat) and `.faiss` files (native FAISS index) together for indexing and searching. Goal: understand what modifications are needed to both files when reordering vectors.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    NativeEngines990KnnVectorsFormat                      │
│                    (src/.../codec/KNN990Codec/)                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│   WRITE PATH (flush/merge)                 READ PATH (search)            │
│   ─────────────────────                    ───────────────────           │
│                                                                          │
│   NativeEngines990KnnVectorsWriter         NativeEngines990KnnVectorsReader
│           │                                         │                    │
│           ├──► FlatVectorsWriter ──► .vec          ├──► FlatVectorsReader ◄── .vec
│           │    (Lucene99FlatVectorsFormat)         │    (raw vector retrieval)
│           │                                         │                    │
│           └──► NativeIndexWriter ──► .faiss        └──► FaissMemoryOptimizedSearcher
│                (JNI to FAISS library)                   (graph traversal from .faiss)
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## File Formats

### .vec File (Lucene99FlatVectorsFormat)
- **Extension**: `.vec` (data), `.vemf` (metadata)
- **Created by**: `Lucene99FlatVectorsWriter` (Lucene core)
- **Contents**: Raw vectors stored sequentially by doc ordinal
- **Format** (from Lucene99FlatVectorsFormat.java):
  ```
  .vec file:
  - Vector data ordered by field, document ordinal, and vector dimension
  - FLOAT32: each sample as IEEE float (little-endian)
  - BYTE: each sample as single byte
  - DocIds (sparse case): encoded by IndexedDISI
  - OrdToDoc (sparse case): DirectMonotonicWriter encoded
  
  .vemf file (metadata):
  - [int32] field number
  - [int32] vector similarity function ordinal
  - [vlong] offset to vectors in .vec
  - [vlong] length of vectors in bytes
  - [vint] dimension
  - [int] number of docs with values
  - [int8] density indicator (-2=empty, -1=dense, 0=sparse)
  ```

### .faiss File (Native FAISS Index)
- **Extension**: `.faiss`
- **Created by**: `NativeIndexWriter` → JNI → FAISS library
- **Contents**: HNSW graph + vector storage (duplicated from .vec)
- **Structure** (parsed by FaissIndex.load()):
  ```
  Top-level: IxMp (FaissIdMapIndex) - ID mapping wrapper
      │
      └── Nested: IHNf (FaissHNSWIndex) - HNSW graph + flat storage
              │
              ├── FaissHNSW - graph structure (neighbor lists)
              │
              └── IxF2/IxFI (FaissIndexFloatFlat) - flat vector storage
                  - dimension * sizeof(float) * num_vectors bytes
  ```

---

## Key Code Paths

### WRITE: How vectors get written to both files

```java
// NativeEngines990KnnVectorsWriter.java (line 88-130)
public void flush(int maxDoc, final Sorter.DocMap sortMap) throws IOException {
    // 1. Write to .vec file via Lucene's FlatVectorsWriter
    flatVectorsWriter.flush(maxDoc, sortMap);  // ◄── Creates .vec file

    for (final NativeEngineFieldVectorsWriter<?> field : fields) {
        // 2. Get vector values supplier
        final Supplier<KNNVectorValues<?>> knnVectorValuesSupplier = getVectorValuesSupplier(...);
        
        // 3. Write to .faiss file via NativeIndexWriter
        final NativeIndexWriter writer = NativeIndexWriter.getWriter(...);
        writer.flushIndex(knnVectorValuesSupplier, totalLiveDocs);  // ◄── Creates .faiss file
    }
}
```

```java
// NativeIndexWriter.java (line 127-145)
private void buildAndWriteIndex(...) throws IOException {
    final String engineFileName = buildEngineFileName(
        state.segmentInfo.name,
        knnEngine.getVersion(),
        fieldInfo.name,
        knnEngine.getExtension()  // ".faiss"
    );
    try (IndexOutput output = state.directory.createOutput(engineFileName, state.context)) {
        // Builds HNSW graph via JNI and writes to .faiss
        indexBuilder.buildAndWriteIndex(nativeIndexParams);
    }
}
```

### READ: How vectors are read during search

```java
// NativeEngines990KnnVectorsReader.java (line 103-108)
// For retrieving raw vectors (used by Lucene queries):
public FloatVectorValues getFloatVectorValues(final String field) throws IOException {
    return flatVectorsReader.getFloatVectorValues(field);  // ◄── Reads from .vec
}

// For ANN search (memory-optimized path):
// NativeEngines990KnnVectorsReader.java (line 155-175)
public void search(String field, float[] target, KnnCollector knnCollector, AcceptDocs acceptDocs) {
    // Uses FaissMemoryOptimizedSearcher which reads from .faiss
    if (trySearchWithMemoryOptimizedSearch(field, target, knnCollector, acceptDocs, true)) {
        return;
    }
}
```

```java
// FaissMemoryOptimizedSearcher.java (line 85-95)
public void search(float[] target, KnnCollector knnCollector, AcceptDocs acceptDocs) {
    // Get vectors FROM .faiss file (not .vec!)
    final KnnVectorValues knnVectorValues = faissIndex.getFloatValues(getSlicedIndexInput());
    
    // Traverse HNSW graph from .faiss
    HnswGraphSearcher.search(scorer, collector, new FaissHnswGraph(hnsw, getSlicedIndexInput()), acceptedOrds);
}
```

---

## Vector Storage Duplication

**CRITICAL INSIGHT**: Vectors are stored in BOTH files:

1. **.vec file**: Lucene's flat vector storage (used for exact retrieval, rescoring)
2. **.faiss file**: FAISS's internal storage (used during graph traversal for distance computation)

```java
// FaissIndexFloatFlat.java - vectors stored inside .faiss
protected void doLoad(IndexInput input) throws IOException {
    readCommonHeader(input);
    oneVectorByteSize = (long) Float.BYTES * getDimension();
    floatVectors = new FaissSection(input, Float.BYTES);  // ◄── Vector data section in .faiss
}

public FloatVectorValues getFloatValues(final IndexInput indexInput) {
    // Reads vectors directly from .faiss file
    public float[] vectorValue(int internalVectorId) throws IOException {
        indexInput.seek(floatVectors.getBaseOffset() + internalVectorId * oneVectorByteSize);
        indexInput.readFloats(buffer, 0, buffer.length);
        return buffer;
    }
}
```

---

## ID Mapping (Sparse/Nested Cases)

The `.faiss` file contains an ID mapping layer (`FaissIdMapIndex`) that maps:
- **Internal vector ID** (0, 1, 2, ...) → **Lucene document ID**

```java
// FaissIdMapIndex.java (line 47-60)
protected void doLoad(IndexInput input) throws IOException {
    // Load nested HNSW index
    final FaissIndex nestedIndex = FaissIndex.load(input);
    
    // Load ID mapping table (sparse case)
    final int numElements = Math.toIntExact(input.readLong());
    idMappingReader = MonotonicIntegerSequenceEncoder.encode(numElements, input);
}

// Converting internal ID to doc ID:
public int ordToDoc(int internalVectorId) {
    return (int) idMappingReader.get(internalVectorId);  // ◄── Maps vector ID → doc ID
}
```

---

## Reordering Implications

### What needs to change when reordering vectors:

| Component | File | What Changes | How to Modify |
|-----------|------|--------------|---------------|
| Vector data | .vec | Vector order | Rewrite vectors in new order |
| Vector data | .faiss (FaissIndexFloatFlat section) | Vector order | Rewrite flat storage section |
| HNSW graph | .faiss (FaissHNSW section) | Neighbor IDs | Remap all neighbor list IDs |
| ID mapping | .faiss (FaissIdMapIndex) | Mapping table | Update mapping if doc IDs change |

### Consistency Requirements:

```
.vec file vector order  ←──MUST MATCH──►  .faiss flat storage order
         │                                         │
         │                                         │
         ▼                                         ▼
    Doc ordinal i                           Internal vector ID i
         │                                         │
         └────────────► ID Mapping ◄───────────────┘
                    (if sparse/nested)
```

---

## Binary Quantization (32x compression) Specifics

For binary quantized indices (1-bit per dimension):

```java
// Index description: "BHNSW16,Flat"
// FaissBinaryHnswIndex.java handles this case

// Vectors stored as bytes (8 dimensions per byte)
// FaissIndexBinaryFlat.java (line 45-55)
protected void doLoad(IndexInput input) throws IOException {
    readBinaryCommonHeader(input);
    oneVectorByteSize = (long) dimension / Byte.SIZE;  // ◄── 8x smaller than float
    binaryVectors = new FaissSection(input, Byte.SIZE);
}
```

---

## Test Validation

From `NativeEngines990KnnVectorsFormatTests.java`:

```java
// Verifies both files are created
final List<String> hnswfiles = getFilesFromSegment(dir, ".faiss");
assertEquals(3, hnswfiles.size());  // One per vector field

final List<String> vecfiles = getFilesFromSegment(dir, ".vec");
assertEquals(3, vecfiles.size());  // Matching .vec files

// Verifies vectors can be read from .vec
final FloatVectorValues floatVectorValues = leafReader.getFloatVectorValues(FLOAT_VECTOR_FIELD);
assertArrayEquals(floatVector, floatVectorValues.vectorValue(...), 0.0f);
```

---

## Reordering Strategy

To reorder vectors while maintaining search correctness:

1. **Read current state**:
   - Parse .vec file to get vector data + doc ID mapping
   - Parse .faiss file to get HNSW graph structure

2. **Compute new ordering**:
   - Determine optimal vector order (e.g., by graph locality)
   - Create old_id → new_id mapping

3. **Rewrite .vec file**:
   - Write vectors in new order
   - Update metadata offsets

4. **Rewrite .faiss file**:
   - Rewrite flat vector storage in new order
   - Remap all neighbor IDs in HNSW graph using old→new mapping
   - Update ID mapping table if needed

5. **Validate**:
   - Hot-swap reordered files in OpenSearch cluster
   - Verify search recall matches or improves


---

## Vector Insertion Order: .vec File vs FAISS Graph Building

### Key Question
If the `.vec` file is modified (reordered), does the FAISS graph building also use the same order?

### Answer: YES - They Share the Same Source

Both the `.vec` file and FAISS graph building iterate vectors in the **same order** because they use the **same underlying data source** during flush.

### Code Flow During Flush

```java
// NativeEngines990KnnVectorsWriter.flush() - line 92-130
public void flush(int maxDoc, final Sorter.DocMap sortMap) throws IOException {
    // STEP 1: Write to .vec file
    flatVectorsWriter.flush(maxDoc, sortMap);  // ◄── Uses FlatFieldVectorsWriter's data

    for (final NativeEngineFieldVectorsWriter<?> field : fields) {
        // STEP 2: Create vector values supplier FROM THE SAME SOURCE
        final Supplier<KNNVectorValues<?>> knnVectorValuesSupplier = getVectorValuesSupplier(
            vectorDataType,
            field.getFlatFieldVectorsWriter().getDocsWithFieldSet(),  // ◄── SAME DocsWithFieldSet
            field.getVectors()                                         // ◄── SAME vectors Map
        );
        
        // STEP 3: Build FAISS index using same supplier
        writer.flushIndex(knnVectorValuesSupplier, totalLiveDocs);  // ◄── Iterates same order
    }
}
```

### The Shared Data Structure

```java
// NativeEngineFieldVectorsWriter.java - line 40-45
class NativeEngineFieldVectorsWriter<T> extends KnnFieldVectorsWriter<T> {
    @Getter
    private final Map<Integer, T> vectors;           // ◄── Vectors stored by docId
    @Getter
    private final FlatFieldVectorsWriter<T> flatFieldVectorsWriter;  // ◄── Writes to .vec
    
    // Both addValue calls go to SAME storage
    public void addValue(int docID, T vectorValue) throws IOException {
        flatFieldVectorsWriter.addValue(docID, vectorValue);  // ◄── For .vec file
        vectors.put(docID, vectorValue);                       // ◄── For FAISS graph
        lastDocID = docID;
    }
}
```

### Iteration Order Determined By DocsWithFieldSet

```java
// KNNVectorValuesIterator.FieldWriterIteratorValues - line 159-161
FieldWriterIteratorValues(@NonNull final DocsWithFieldSet docsWithFieldSet, @NonNull final Map<Integer, T> vectors) {
    super(docsWithFieldSet.iterator());  // ◄── Iterator order from DocsWithFieldSet
    this.vectors = vectors;
}

// DocsWithFieldSet.iterator() returns docs in ASCENDING docId order
// (either via BitSetIterator or DocIdSetIterator.all())
```

### FAISS Graph Building Iteration

```java
// DefaultIndexBuildStrategy.buildAndWriteIndex() - line 52-70
public void buildAndWriteIndex(final BuildIndexParams indexInfo) throws IOException {
    final KNNVectorValues<?> knnVectorValues = indexInfo.getKnnVectorValuesSupplier().get();
    initializeVectorValues(knnVectorValues);
    
    final List<Integer> transferredDocIds = new ArrayList<>();
    
    // Iterates in DocsWithFieldSet order (ascending docId)
    while (knnVectorValues.docId() != NO_MORE_DOCS) {
        Object vector = QuantizationIndexUtils.processAndReturnVector(knnVectorValues, indexBuildSetup);
        vectorTransfer.transfer(vector, true);
        transferredDocIds.add(knnVectorValues.docId());  // ◄── DocIds in iteration order
        knnVectorValues.nextDoc();
    }
    
    // Build index with vectors in iteration order
    JNIService.createIndex(
        intListToArray(transferredDocIds),  // ◄── DocId array matches vector order
        vectorAddress,
        ...
    );
}
```

### Implication for Reordering

**The `.vec` file and FAISS graph are built from the same iteration order (ascending docId).**

However, they are **independent files** - changing one does NOT automatically change the other.

To reorder:

| Scenario | What Happens |
|----------|--------------|
| Modify `.vec` only | FAISS graph still has old order → **MISMATCH** |
| Modify `.faiss` only | `.vec` still has old order → **MISMATCH** (but search may still work if only using FAISS) |
| Modify both consistently | Both files have new order → **CORRECT** |

### Critical Insight: Internal Vector ID vs Doc ID

```
During flush:
  DocId 0 → Internal Vector ID 0 → Position 0 in both .vec and .faiss flat storage
  DocId 1 → Internal Vector ID 1 → Position 1 in both .vec and .faiss flat storage
  DocId 5 → Internal Vector ID 2 → Position 2 in both .vec and .faiss flat storage (sparse case)
  ...

The FAISS graph's neighbor lists reference INTERNAL VECTOR IDs (0, 1, 2, ...),
NOT Lucene doc IDs. The FaissIdMapIndex handles the mapping.
```

### What Needs to Stay Consistent

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        CONSISTENCY REQUIREMENTS                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│   .vec file position i  ◄──MUST MATCH──►  .faiss flat storage position i │
│           │                                         │                    │
│           │                                         │                    │
│           ▼                                         ▼                    │
│   Vector for internal ID i              Vector for internal ID i         │
│           │                                         │                    │
│           │                                         │                    │
│           └────────► HNSW neighbor lists reference internal IDs ◄────────┘
│                                                                          │
│   If you reorder vectors, you must:                                      │
│   1. Reorder .vec file positions                                         │
│   2. Reorder .faiss flat storage positions (same order)                  │
│   3. Remap ALL neighbor IDs in HNSW graph                                │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Search Path Verification

During search, `FaissMemoryOptimizedSearcher` reads vectors from `.faiss` file only:

```java
// FaissMemoryOptimizedSearcher.search() - line 87-88
final KnnVectorValues knnVectorValues = faissIndex.getFloatValues(getSlicedIndexInput());
// ◄── Reads from .faiss file's FaissIndexFloatFlat section, NOT from .vec file

// FaissIndexFloatFlat.getFloatValues() - line 82-90
public float[] vectorValue(int internalVectorId) throws IOException {
    indexInput.seek(floatVectors.getBaseOffset() + internalVectorId * oneVectorByteSize);
    indexInput.readFloats(buffer, 0, buffer.length);
    return buffer;
}
```

**Therefore**: For ANN search via `FaissMemoryOptimizedSearcher`, only the `.faiss` file matters. The `.vec` file is used for:
- Exact search / rescoring via `FlatVectorsReader`
- Lucene's native vector operations


---

## FAISS File Permutation Plan

### Goal
Create a new file that takes the clustering output (old_docid → new_docid mapping) and permutes the `.faiss` file to match the reordered `.vec` file.

### Key Components to Modify in .faiss File

1. **FaissIdMapIndex (IxMp)** - ID mapping table (maps internal vector ID → Lucene doc ID)
2. **FaissHNSWIndex (IHNf)** - Contains:
   - **FaissHNSW** - Graph structure with neighbor lists (neighbor IDs must be remapped)
   - **FaissIndexFloatFlat (IxF2/IxFI)** - Flat vector storage (must be reordered)

### Data Structures Affected

| Section | What Changes | How |
|---------|--------------|-----|
| Flat vectors (IxF2) | Vector order | Permute using new_order[i] |
| HNSW neighbors | All neighbor IDs | Remap: `new_id = inverse_mapping[old_id]` |
| HNSW levels | Level assignments per vector | Permute using new_order[i] |
| HNSW offsets | Offset per vector | Recompute after neighbor reordering |
| ID mapping | Internal ID → Doc ID | Update if doc IDs change |

### Implementation Steps

1. **Parse .faiss file structure** - Read IxMp, IHNf, IxF2/IxFI sections
2. **Build inverse mapping** - `inverse[old_idx] = new_idx` from clustering output
3. **Permute flat vectors** - Reorder to match new vector order
4. **Remap HNSW neighbor lists** - Replace all neighbor IDs with remapped IDs
5. **Permute HNSW levels** - Reorder level assignments
6. **Recompute HNSW offsets** - Rebuild offset table
7. **Write new .faiss file** - Preserve headers, write permuted data

### Design Decisions

| Question | Answer |
|----------|--------|
| Handle dense and sparse cases (with ID mapping)? | **Yes** |
| Support binary quantized indices (BHNSW)? | **Yes** |
| Integrate into VectorReorder.clusterVectors()? | **No, keep separate** |

### API

```java
public class FaissFilePermuter {
    /**
     * Permute .faiss file to match reordered vectors.
     * @param faissPath path to original .faiss file
     * @param newOrder new_order[new_idx] = old_idx mapping from clustering
     * @param outputPath path for output .faiss file
     */
    public static void permute(String faissPath, int[] newOrder, String outputPath);
}
```

---

## Using MemoryOptimizedSearcher to Read .faiss Files

### Goal
Leverage the existing `FaissIndex` parsing infrastructure from k-NN's `memoryoptsearch` package to read `.faiss` file structure instead of reimplementing parsing logic.

### Key Classes in k-NN memoryoptsearch Package

```
org.opensearch.knn.memoryoptsearch.faiss/
├── FaissIndex.java              # Base class, static load() method
├── FaissIdMapIndex.java         # IxMp - ID mapping wrapper
├── FaissHNSWIndex.java          # IHNf - HNSW + flat storage
├── FaissIndexFloatFlat.java     # IxF2/IxFI - flat vector storage
├── FaissHNSW.java               # HNSW graph structure
├── FaissSection.java            # Marks offset/size of sections
├── FaissIndexLoadUtils.java     # Utilities for reading index type
├── IndexTypeToFaissIndexMapping.java  # Maps type string to class
└── binary/
    ├── FaissBinaryHnswIndex.java    # Binary HNSW
    └── FaissIndexBinaryFlat.java    # Binary flat storage
```

### How FaissIndex.load() Works

```java
// FaissIndex.java - line 44-51
public static FaissIndex load(IndexInput input) throws IOException {
    final String indexType = FaissIndexLoadUtils.readIndexType(input);  // Read 4-byte type
    final FaissIndex faissIndex = IndexTypeToFaissIndexMapping.getFaissIndex(indexType);
    faissIndex.doLoad(input);  // Polymorphic loading
    return faissIndex;
}
```

### Accessing Parsed Structure

```java
// Example: Reading .faiss file using k-NN infrastructure
try (FSDirectory dir = FSDirectory.open(path.getParent());
     IndexInput input = dir.openInput(faissFileName, IOContext.DEFAULT)) {
    
    FaissIndex index = FaissIndex.load(input);
    
    if (index instanceof FaissIdMapIndex idMap) {
        FaissIndex nested = idMap.getNestedIndex();  // Get IHNf
        FaissHNSW hnsw = idMap.getFaissHnsw();       // Get graph
        
        if (nested instanceof FaissHNSWIndex hnswIndex) {
            // Access flat vectors via getFloatValues() or getByteValues()
            FloatVectorValues vectors = hnswIndex.getFloatValues(input);
        }
    }
}
```

### Key Data We Need to Extract

| Data | Source Class | Accessor |
|------|--------------|----------|
| Flat vectors | FaissIndexFloatFlat | `getFloatValues(input)` |
| HNSW neighbors | FaissHNSW | `neighbors` (FaissSection) |
| HNSW levels | FaissHNSW | `levels` (FaissSection) |
| HNSW offsets | FaissHNSW | `offsetsReader` (DirectMonotonicReader) |
| ID mapping | FaissIdMapIndex | `idMappingReader` (DirectMonotonicReader) |
| Entry point | FaissHNSW | `entryPoint` |
| Max level | FaissHNSW | `maxLevel` |

### Challenge: Read-Only Access

The existing classes are designed for **reading** during search, not modification. We need to:
1. Use them to **parse** the structure and get offsets
2. Read raw bytes from those offsets
3. Write modified bytes to new file

### Implementation Approach

```java
public class FaissFileReader {
    private final FaissIndex index;
    private final IndexInput input;
    
    // Use k-NN's parsing to get structure
    public FaissFileReader(String faissPath) {
        this.input = directory.openInput(faissPath, IOContext.DEFAULT);
        this.index = FaissIndex.load(input);
    }
    
    // Extract raw neighbor data using FaissSection offsets
    public int[] readNeighbors(int vectorId) {
        FaissHNSW hnsw = ((FaissIdMapIndex) index).getFaissHnsw();
        long offset = hnsw.getNeighbors().getBaseOffset();
        // Read from offset...
    }
}
```


### Reading Raw HNSW Neighbor Data

From `FaissHnswGraph.java`, we can see exactly how to read neighbor lists:

```java
// FaissHnswGraph.loadNeighborIdList() - line 60-90
private void loadNeighborIdList(final long begin, final long end) {
    // Seek to neighbor list offset
    indexInput.seek(faissHnsw.getNeighbors().getBaseOffset() + Integer.BYTES * begin);
    
    // Read neighbor IDs until -1 (terminator)
    for (long i = begin; i < end; i++) {
        final int neighborId = indexInput.readInt();
        if (neighborId >= 0) {
            neighborIdList[index++] = neighborId;
        } else {
            break;  // -1 indicates end of neighbors
        }
    }
}
```

### Key Offsets for Reading/Writing

```java
// For vector i at level L:
long relativeOffset = offsetsReader.get(i);  // From DirectMonotonicReader
long begin = relativeOffset + cumNumberNeighborPerLevel[level];
long end = relativeOffset + cumNumberNeighborPerLevel[level + 1];

// Absolute file offset for neighbors:
long fileOffset = neighbors.getBaseOffset() + Integer.BYTES * begin;

// Absolute file offset for level of vector i:
long levelOffset = levels.getBaseOffset() + Integer.BYTES * i;
```

### Reading Levels Per Vector

```java
// FaissHnswGraph.getNodesOnLevel() - line 115-125
levelIndexInput.seek(levelsSection.getBaseOffset());
for (int i = 0; i < numVectors; ++i) {
    final int maxLevel = levelIndexInput.readInt();  // levels[i] = max level + 1
}
```

### Summary: What We Can Extract

| Data | How to Read |
|------|-------------|
| Neighbor IDs | `input.seek(neighbors.baseOffset + 4*begin); input.readInt()` |
| Level per vector | `input.seek(levels.baseOffset + 4*i); input.readInt()` |
| Offset per vector | `offsetsReader.get(i)` (DirectMonotonicReader) |
| Flat vectors | `input.seek(flatVectors.baseOffset + bytesPerVector*i); input.readFloats()` |
| ID mapping | `idMappingReader.get(i)` (DirectMonotonicReader) |

### Next Step

Create `FaissFilePermuter.java` that:
1. Uses `FaissIndex.load()` to parse structure
2. Reads raw bytes using the offsets from parsed structure
3. Permutes/remaps data according to clustering output
4. Writes new `.faiss` file with same structure but reordered data


---

## FAISS Index Rebuild Strategy (Correct Approach)

### Goal

Improve cache locality during HNSW search by ensuring vectors that are close in embedding space are also close on disk.

### Key Insight: Rebuild, Don't Permute

**Wrong approach**: Permute an existing .faiss file (remap neighbor IDs, reorder levels, etc.)
- Complex and error-prone
- Doesn't improve graph structure - just moves data around

**Correct approach**: Rebuild the HNSW index from scratch with vectors inserted in cluster-sorted order
- HNSW naturally connects nearby vectors during insertion
- Inserting cluster-sorted vectors means sequential IDs are likely neighbors
- Better disk locality = better cache hits during search

### How It Works

```
Original insertion order:        Cluster-sorted insertion order:
┌─────────────────────────┐      ┌─────────────────────────┐
│ doc0: random location   │      │ doc0: cluster0, closest │
│ doc1: random location   │      │ doc1: cluster0, next    │
│ doc2: random location   │      │ doc2: cluster0, next    │
│ ...                     │      │ ...                     │
│ docN: random location   │      │ docN: cluster99, farthest│
└─────────────────────────┘      └─────────────────────────┘
         │                                │
         ▼                                ▼
   HNSW neighbors are                HNSW neighbors are
   scattered across file             likely sequential IDs
         │                                │
         ▼                                ▼
   Cache misses during               Cache hits during
   graph traversal                   graph traversal
```

### Implementation Pipeline

```
┌──────────────────────────────────────────────────────────────────────────┐
│ Step 1: Read original .vec file                                          │
│   - Load all vectors into memory                                         │
│   - vectors[docId] = float[dim]                                          │
└──────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ Step 2: Cluster vectors (k-means)                                        │
│   - Compute cluster assignments and distances to centroids               │
│   - Sort by (cluster_id, distance_to_centroid)                           │
│   - Output: newOrder[newIdx] = oldDocId                                  │
└──────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ Step 3: Write reordered .vec file                                        │
│   - newVec[newIdx] = oldVec[newOrder[newIdx]]                            │
│   - This is the "pre-sorted" .vec file                                   │
└──────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ Step 4: Build new FAISS index via JNI                                    │
│   - Call FAISS hnsw.add() with vectors in new order                      │
│   - Vectors inserted as: vec[0], vec[1], vec[2], ... (cluster-sorted)    │
│   - HNSW assigns internal IDs 0, 1, 2, ... in insertion order            │
│   - Graph naturally has good locality                                    │
└──────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ Step 5: Write ID mapping                                                 │
│   - idMapping[internalId] = originalDocId                                │
│   - idMapping[newIdx] = newOrder[newIdx]                                 │
│   - This maps back to Lucene doc IDs for search results                  │
└──────────────────────────────────────────────────────────────────────────┘
```

### What Changes vs Original k-NN Flush

Original k-NN flush:
```java
// Iterates docs in ascending docId order
while (knnVectorValues.nextDoc() != NO_MORE_DOCS) {
    vectorTransfer.transfer(vector);
    docIds.add(knnVectorValues.docId());  // 0, 1, 2, 3, ...
}
JNIService.createIndex(docIds, vectors, ...);
```

With cluster-sorted insertion:
```java
// Iterates docs in cluster-sorted order
for (int newIdx = 0; newIdx < n; newIdx++) {
    int oldDocId = newOrder[newIdx];
    vectorTransfer.transfer(vectors[oldDocId]);
    docIds.add(oldDocId);  // cluster-sorted order
}
JNIService.createIndex(docIds, vectors, ...);
```

### ID Mapping Implications

The ID mapping in IxMp serves to convert internal vector ID → Lucene doc ID.

After rebuild:
- Internal ID 0 → vectors from cluster 0, closest to centroid
- Internal ID 1 → vectors from cluster 0, next closest
- ...
- idMapping[internalId] = original Lucene docId

Search returns internal IDs, which get mapped back to correct Lucene doc IDs.

### Implementation Options

#### Option A: Standalone Tool (Outside OpenSearch)
```java
public class FaissIndexRebuilder {
    public static void rebuild(String vecPath, String outputFaissPath, int[] newOrder) {
        // 1. Read vectors from .vec
        float[][] vectors = readVecFile(vecPath);
        
        // 2. Reorder vectors
        float[][] sorted = new float[vectors.length][];
        for (int i = 0; i < vectors.length; i++) {
            sorted[i] = vectors[newOrder[i]];
        }
        
        // 3. Build FAISS index via JNI
        long indexAddr = JNIService.createIndex(
            newOrder,  // These become the ID mapping
            sorted,
            indexParams
        );
        
        // 4. Write to file
        JNIService.writeIndex(indexAddr, outputFaissPath);
    }
}
```

#### Option B: Modified k-NN Codec (Inside OpenSearch)
Modify `NativeEngines990KnnVectorsWriter` to accept a reordering:
```java
public void flush(int maxDoc, DocMap sortMap, int[] clusterOrder) {
    // Use clusterOrder instead of natural docId order
    // when iterating vectors for FAISS index building
}
```

### Questions to Resolve

1. **Where does clustering happen?**
   - At flush time? (adds latency to indexing)
   - As background optimization? (like Lucene segment merging)
   - Offline tool? (for existing indices)

2. **How to handle the .vec file?**
   - Also reorder it to match? (for consistency with exact search)
   - Keep original order? (simpler, but .vec and .faiss diverge)

3. **Sparse/nested document handling?**
   - ID mapping already handles this
   - Just need to preserve correct docId mapping

---

## Implementation: FaissIndexRebuilder (COMPLETED)

### Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         FaissIndexRebuilder                              │
│                    (Java - high-level API)                               │
├─────────────────────────────────────────────────────────────────────────┤
│  rebuild(vectors, newOrder, dim, outputPath, m, efConstruction, space)  │
│    1. Reorder vectors: reordered[i] = vectors[newOrder[i]]              │
│    2. Store in native memory via FaissKMeansService.storeVectors()      │
│    3. Call FaissIndexService.buildAndWriteIndex()                       │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         FaissIndexService                                │
│                    (JNI - thin wrapper)                                  │
├─────────────────────────────────────────────────────────────────────────┤
│  buildAndWriteIndex(vectorsAddr, n, dim, ids, desc, space, ef, path)    │
│    - Equivalent to k-NN's initIndex + insertToIndex + writeIndex        │
│    - Uses same FAISS library (linked from k-NN/jni/build)               │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         faiss_kmeans.cpp                                 │
│                    (C++ - FAISS calls)                                   │
├─────────────────────────────────────────────────────────────────────────┤
│  1. faiss::index_factory(dim, "HNSW16,Flat", metric)                    │
│  2. hnswIndex->hnsw.efConstruction = efConstruction                     │
│  3. faiss::IndexIDMap idMap(index)                                      │
│  4. idMap.add_with_ids(n, vectors, ids)                                 │
│  5. faiss::write_index(&idMap, outputPath)                              │
└─────────────────────────────────────────────────────────────────────────┘
```

### Java API

```java
public class FaissIndexRebuilder {
    public static final String SPACE_L2 = "l2";
    public static final String SPACE_INNER_PRODUCT = "innerproduct";
    
    /**
     * Build a new FAISS index with vectors in cluster-sorted order.
     *
     * @param vectors    all vectors (in original docId order)
     * @param newOrder   newOrder[newIdx] = oldDocId - the insertion order
     * @param dim        vector dimension
     * @param outputPath path for output .faiss file
     * @param m          HNSW M parameter (neighbors per node, typically 16)
     * @param efConstruction ef_construction parameter (typically 100)
     * @param spaceType  "l2" or "innerproduct"
     */
    public static void rebuild(float[][] vectors, int[] newOrder, int dim, 
                               String outputPath, int m, int efConstruction, 
                               String spaceType);
    
    // Convenience method with defaults: m=16, efConstruction=100, spaceType=l2
    public static void rebuild(float[][] vectors, int[] newOrder, int dim, 
                               String outputPath);
}
```

### Output File Format

The generated .faiss file has identical structure to k-NN's native index:

```
IxMp (IndexIDMap)
├── Common header (dim, ntotal, metric)
├── IHNf (IndexHNSWFlat)
│   ├── Common header
│   ├── HNSW graph
│   │   ├── assignProbas (level probabilities)
│   │   ├── cumNeighborsPerLevel
│   │   ├── levels (max level per vector)
│   │   ├── offsets (neighbor list offsets)
│   │   ├── neighbors (neighbor IDs)
│   │   └── params (entryPoint, maxLevel, efConstruction, efSearch)
│   └── IxF2 (IndexFlatL2)
│       └── vectors (n * dim * 4 bytes)
└── ID mapping (n * 8 bytes)
    └── ids[internalId] = originalDocId
```

### Usage Example

```java
// 1. Load vectors from .vec file
float[][] vectors = VectorReorder.loadVectors(vecPath);
int dim = vectors[0].length;
int n = vectors.length;

// 2. Cluster and get sorted order
long addr = FaissKMeansService.storeVectors(vectors);
KMeansResult result = FaissKMeansService.kmeansWithDistances(
    addr, n, dim, 100, 10, FaissKMeansService.METRIC_L2);
FaissKMeansService.freeVectors(addr);

// 3. Sort by (cluster_id, distance_to_centroid)
Integer[] indices = new Integer[n];
for (int i = 0; i < n; i++) indices[i] = i;
Arrays.sort(indices, (a, b) -> {
    int cmp = Integer.compare(result.assignments()[a], result.assignments()[b]);
    return cmp != 0 ? cmp : Float.compare(result.distances()[a], result.distances()[b]);
});

// 4. Create newOrder: newOrder[newIdx] = oldDocId
int[] newOrder = new int[n];
for (int i = 0; i < n; i++) newOrder[i] = indices[i];

// 5. Rebuild FAISS index with cluster-sorted insertion
FaissIndexRebuilder.rebuild(vectors, newOrder, dim, "output.faiss");
```

### Verification

Test output shows correct structure:
```
SUCCESS: Index created, size = 280135 bytes
Parsed structure: FaissStructure{type=IxMp, hnsw=IHNf, flat=IxF2, dim=32, n=1000, maxLevel=2, entry=592}
```
