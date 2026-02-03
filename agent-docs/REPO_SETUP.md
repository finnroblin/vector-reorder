# Vector Reorder Module - Repository Setup

## Overview
Standalone Java tool to read .vec files (Lucene flat vector format) and reorder vectors for improved spatial locality in k-NN search.

## Project Structure
```
vector-reorder/
├── build.gradle
├── settings.gradle
├── gradlew
├── gradlew.bat
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── src/
│   └── main/
│       └── java/
│           └── org/opensearch/knn/reorder/
│               └── VectorReorder.java
├── FEATURE.md
└── REPO_SETUP.md
```

## Gradle Setup
- Using Gradle 9.2.0 (same as k-NN parent project)
- Need to copy gradle wrapper files from k-NN project:
  - `gradle/wrapper/gradle-wrapper.jar`
  - `gradle/wrapper/gradle-wrapper.properties`
  - `gradlew` (shell script)
  - `gradlew.bat` (Windows batch)

## Dependencies
- `org.apache.lucene:lucene-core:9.12.0` - For reading Lucene index files

## .vec File Format (Lucene99FlatVectorsFormat)
The `.vec` file contains:
1. **Header**: Lucene CodecUtil header
   - Magic number (4 bytes): `0x3fd76c17`
   - Codec name (length-prefixed string)
   - Version (4 bytes)
   - Object ID (16 bytes)
   - Suffix (length-prefixed string)
2. **Vector Data**: `dimension * sizeof(float) * num_vectors` bytes
   - Each vector is stored as IEEE floats in little-endian byte order
3. **Footer**: 16 bytes (checksum)

## Companion .vemf File (Metadata)
Contains per-field metadata:
- Field number
- Vector similarity function
- Vector data offset/length in .vec file
- Dimension
- Number of documents
- Sparse/dense indicator
- DocId mappings (if sparse)

## Key Lucene Classes for Vector I/O

### Reading Vectors via Lucene Index API (Recommended)
The proper way to read vectors is through Lucene's index reader API:

```java
// 1. Open the directory containing the index
FSDirectory directory = FSDirectory.open(indexPath);

// 2. Open a DirectoryReader
DirectoryReader reader = DirectoryReader.open(directory);

// 3. Get LeafReader for each segment
for (LeafReaderContext ctx : reader.leaves()) {
    LeafReader leafReader = ctx.reader();
    
    // 4. Get FloatVectorValues for a field
    FloatVectorValues vectorValues = leafReader.getFloatVectorValues(fieldName);
    
    // 5. Iterate or random access
    int size = vectorValues.size();
    int dimension = vectorValues.dimension();
    
    // Random access by ordinal
    float[] vector = vectorValues.vectorValue(ord);
    
    // Or iterate
    KnnVectorValues.DocIndexIterator iter = vectorValues.iterator();
    while (iter.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
        float[] vec = vectorValues.vectorValue(iter.index());
    }
}
```

### k-NN Plugin's Approach
The k-NN plugin uses:
- `NativeEngines990KnnVectorsReader` wraps `FlatVectorsReader`
- Delegates to `flatVectorsReader.getFloatVectorValues(field)` 
- `FlatVectorsReader` is `Lucene99FlatVectorsReader` which reads .vec/.vemf files

### File Structure
- `.vec` file: Header + raw vector data + footer
- `.vemf` file: Metadata per field (offset, length, dimension, size, sparse/dense info)
- Index also needs: `.si` (segment info), `.fnm` (field names), `segments_N` (commit point)

## TODO
- [x] Create build.gradle
- [x] Create settings.gradle  
- [x] Create VectorReorder.java boilerplate
- [x] Copy gradle wrapper files
- [x] Build and test with sample .vec file
- [x] Parse .vemf metadata to get vector data offset/length/dimension/size
- [x] Read vectors directly from .vec file
- [x] Copy vectors to new .vec file with 1000-vector buffer
- [x] Add FAISS JNI for k-means clustering
- [x] Implement cluster command to sort vectors by cluster assignment
- [x] Add distance-based secondary sort within clusters
- [x] Write reordered vectors to new file

### TODO Jan 15 
- [ ] Make sure the hotswap works and we can do an e2e sift search run.
- [ ] Support multiple segments
- [ ] Try out for binary data (32x compression), see if the recall is non-zero. 

## Issue: Custom Codec Dependency
The k-NN index uses `KNN1030Codec` which is a custom codec from the k-NN plugin.
To read these indexes, we need to either:
1. Add k-NN plugin as a dependency (complex - pulls in OpenSearch)
2. Read the .vec file directly without going through DirectoryReader
3. Build this tool as part of the k-NN plugin itself

## Test Results (2026-01-13)

Successfully reading vectors from .vec file by parsing .vemf metadata:

```
File: _z_NativeEngines990KnnVectorsFormat_0.vec
Dimension: 128
Vector count: 1000000
Vector data offset: 92
Vector data length: 512000000

Vector 0: [12.0000, 7.0000, 0.0000, 0.0000, ..., 0.0000, 1.0000, 7.0000, 35.0000]
Vector 1: [49.0000, 7.0000, 7.0000, 11.0000, ..., 62.0000, 13.0000, 23.0000, 39.0000]
...
```

### Key Implementation Details
- Skip index header manually (variable-length suffix makes `checkIndexHeader` require segment ID validation)
- Header length: `CodecUtil.headerLength(codec) + 16 + 1 + suffix.length()`
- Suffix for k-NN: `"NativeEngines990KnnVectorsFormat_0"`
- Metadata format: fieldNum(int), encoding(int), similarity(int), offset(vlong), length(vlong), dimension(vint), size(int)
