# K-Means Implementation using FAISS JNI

## Overview
This module includes a standalone JNI binding to FAISS k-means clustering, using the pre-built FAISS library from the k-NN project.

## Directory Structure
```
vector-reorder/
├── jni/
│   ├── CMakeLists.txt          # CMake build for JNI library
│   ├── include/
│   │   └── org_opensearch_knn_reorder_FaissKMeansService.h
│   ├── src/
│   │   └── faiss_jni.cpp       # JNI implementation
│   └── release/
│       └── libvectorreorder_faiss.dylib
└── src/main/java/org/opensearch/knn/reorder/
    ├── FaissKMeansService.java  # Java JNI interface
    ├── KMeansResult.java        # Result record with assignments and distances
    ├── VectorReorder.java       # Main tool with cluster command
    ├── KMeansTest.java          # Basic k-means test
    └── ClusterSortTest.java     # Distance-based sort test
```

## Building the JNI Library

Prerequisites:
- k-NN JNI must be built first (provides libfaiss.a)
- OpenMP (libomp from homebrew on macOS)
- BLAS/LAPACK (Accelerate framework on macOS)

```bash
cd vector-reorder/jni
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build
```

Output: `jni/release/libvectorreorder_faiss.dylib`

## Usage

### Cluster command with multiple files and HNSW parameters
```bash
./gradlew run --args="cluster --vec <file1.vec> [--vec <file2.vec> ...] [--faiss <file1.faiss> ...] \
    [--space <l2|innerproduct>] [--ef-search <n>] [--ef-construction <n>] [--m <n>]"
```

### Options
| Option | Description | Default |
|--------|-------------|---------|
| `--vec` | Path to .vec file (can specify multiple) | Required |
| `--faiss` | Path to .faiss file (can specify multiple) | None |
| `--space` | Space type: `l2` or `innerproduct` | `l2` |
| `--ef-search` | ef_search parameter for FAISS HNSW | 100 |
| `--ef-construction` | ef_construction parameter for FAISS HNSW | 100 |
| `--m` | M parameter for FAISS HNSW | 16 |

### Examples

Single .vec file (legacy):
```bash
./gradlew run --args="cluster --vec /path/to/vectors.vec"
```

Multiple .vec files with inner product:
```bash
./gradlew run --args="cluster --vec /path/to/file1.vec --vec /path/to/file2.vec --space innerproduct"
```

With FAISS files and custom HNSW parameters:
```bash
./gradlew run --args="cluster --vec /path/to/vectors.vec --faiss /path/to/index.faiss \
    --space l2 --ef-search 200 --ef-construction 128 --m 32"
```

### Example output (1M vectors, k=100, 1 iteration)
```
Loading vectors from: /path/to/file1.vec
Loading vectors from: /path/to/file2.vec
Total vectors loaded: 1000000 (dim=128)
FAISS files: [/path/to/index.faiss]
Parameters: space=l2, ef_search=200, ef_construction=128, m=32
Vector 100000 BEFORE sort: [16.0000, 4.0000, 0.0000, 17.0000, ..., 0.0000, 2.0000, 19.0000, 25.0000]
Running k-means with k=100, metric=l2...
Vector 100000 AFTER sort: [0.0000, 0.0000, 0.0000, 0.0000, ..., 5.0000, 6.0000, 0.0000, 4.0000]
  (was original index 464995, cluster 12, distance 63148.906)

First 5 vectors in cluster 0 (should be sorted by distance):
  idx=386343, distance=34875.5
  idx=210817, distance=36415.72
  idx=983897, distance=36643.656
  idx=804511, distance=38360.438
  idx=998943, distance=39238.688
```

### Other commands
```bash
# Print first 10 vectors
./gradlew run --args="print <path-to-vec-file>"

# Load and print every 100,000th vector
./gradlew run --args="load <path-to-vec-file>"
```

### Run tests
```bash
./gradlew runClusterSortTest
```

## Java API

```java
import org.opensearch.knn.reorder.FaissKMeansService;
import org.opensearch.knn.reorder.KMeansResult;

// Store vectors in native memory
float[][] vectors = { {1.0f, 1.0f}, {5.0f, 5.0f}, {9.0f, 9.0f} };
long addr = FaissKMeansService.storeVectors(vectors);

// Run k-means with distances (L2 metric)
KMeansResult result = FaissKMeansService.kmeansWithDistances(
    addr, vectors.length, dim, k, niter, 
    FaissKMeansService.METRIC_L2  // or METRIC_INNER_PRODUCT
);
int[] assignments = result.assignments();
float[] distances = result.distances();

// Sort by (cluster_id, distance)
Integer[] indices = new Integer[n];
for (int i = 0; i < n; i++) indices[i] = i;
Arrays.sort(indices, (a, b) -> {
    int cmp = Integer.compare(assignments[a], assignments[b]);
    if (cmp != 0) return cmp;
    // For L2: lower distance = closer
    // For inner product: higher distance = closer (reverse comparison)
    return Float.compare(distances[a], distances[b]);
});

// Free memory
FaissKMeansService.freeVectors(addr);
```

## Distance Metrics

- `METRIC_L2` (0): Euclidean distance - lower values mean closer to centroid
- `METRIC_INNER_PRODUCT` (1): Inner product - higher values mean closer to centroid

## Test
```bash
./gradlew runKMeansTest        # Basic k-means test
./gradlew runClusterSortTest   # Distance-based sort test
```
