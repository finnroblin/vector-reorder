# Code Duplication Analysis & Refactoring Plan

## Identified Duplications

### 1. `loadVecFile()` - Duplicated in 4 files

**Files:**
- `ReorderTool.java` (lines 103-130)
- `E2EReorderScript.java` (lines 123-150)
- `Sift128RebuildTest.java` (lines 79-106)
- `VectorReorder.java` (has `readMetadata()` + loading logic)

**Pattern:**
```java
private static float[][] loadVecFile(String vecFilePath) throws Exception {
    Path path = Paths.get(vecFilePath);
    // ... parse .vemf metadata
    int headerLength = CodecUtil.headerLength("Lucene99FlatVectorsFormatMeta") + 16 + 1 + "NativeEngines990KnnVectorsFormat_0".length();
    // ... read vectors from slice
}
```

**Refactor:** Move to `VectorReorder.loadVecFile()` as public static method.

---

### 2. Cluster sorting logic - Duplicated in 6 files

**Files:**
- `ReorderTool.java`
- `E2EReorderScript.java`
- `Sift128RebuildTest.java`
- `FaissIndexRebuilderTest.java`
- `VectorReorder.java`
- `ClusterSortTest.java` (2x)

**Pattern:**
```java
Integer[] indices = new Integer[n];
for (int i = 0; i < n; i++) indices[i] = i;

Arrays.sort(indices, (a, b) -> {
    int cmp = Integer.compare(assignments[a], assignments[b]);
    return cmp != 0 ? cmp : Float.compare(distances[a], distances[b]);
});

int[] newOrder = new int[n];
for (int i = 0; i < n; i++) newOrder[i] = indices[i];
```

**Refactor:** Create `ClusterSorter.sortByCluster(KMeansResult result)` returning `int[] newOrder`.

---

### 3. Full clustering pipeline - Duplicated in 5 files

**Files:**
- `ReorderTool.java`
- `E2EReorderScript.java`
- `Sift128RebuildTest.java`
- `FaissIndexRebuilderTest.java`
- `VectorReorder.clusterVectors()`

**Pattern:**
```java
long addr = FaissKMeansService.storeVectors(vectors);
KMeansResult result = FaissKMeansService.kmeansWithDistances(addr, n, dim, k, niter, metric);
FaissKMeansService.freeVectors(addr);
// ... sort by cluster
// ... create newOrder
```

**Refactor:** Create `ClusterSorter.clusterAndSort(float[][] vectors, int k, int metric)` returning `int[] newOrder`.

---

## Proposed New Structure

### Core Services (keep as-is)
- `FaissKMeansService.java` - JNI for k-means
- `FaissIndexService.java` - JNI for index building
- `KMeansResult.java` - Result record

### Utility Classes (refactor into)

**`VecFileIO.java`** - All .vec file operations
```java
public class VecFileIO {
    public record VecFileMeta(int dimension, int size, long dataOffset, long dataLength) {}
    
    public static VecFileMeta readMetadata(String vecPath);
    public static float[][] loadVectors(String vecPath);
    public static void writeReordered(String srcPath, String dstPath, int[] newOrder);
}
```

**`ClusterSorter.java`** - Clustering + sorting
```java
public class ClusterSorter {
    public static int[] clusterAndSort(float[][] vectors, int k, int niter, int metric);
    public static int[] sortByCluster(int[] assignments, float[] distances);
}
```

**`FaissIndexRebuilder.java`** - Keep as-is (already clean)

### Scripts/Tests (simplify)

**`ReorderTool.java`** - CLI tool (simplified)
```java
float[][] vectors = VecFileIO.loadVectors(vecPath);
int[] newOrder = ClusterSorter.clusterAndSort(vectors, k, 25, METRIC_L2);
FaissIndexRebuilder.rebuild(vectors, newOrder, dim, outputFaiss);
VecFileIO.writeReordered(vecPath, outputVec, newOrder);
```

**`E2EReorderScript.java`** - E2E script (simplified)
- Uses `VecFileIO` and `ClusterSorter`
- Adds backup logic

**Test files** - Can be deleted or simplified
- `Sift128RebuildTest.java` - Redundant with `ReorderTool`
- `FaissIndexRebuilderTest.java` - Keep for unit testing
- `ClusterSortTest.java` - Keep for unit testing
- `KMeansTest.java` - Keep for unit testing

---

## Refactoring Steps

### Step 1: Create `VecFileIO.java` ✅
- [x] Extract `loadVecFile()` from `ReorderTool.java`
- [x] Move `readMetadata()` from `VectorReorder.java`
- [x] Move `writeReorderedVecFile()` from `VectorReorder.java`

### Step 2: Create `ClusterSorter.java` ✅
- [x] Extract sorting logic into `sortByCluster(int[] assignments, float[] distances)`
- [x] Create `clusterAndSort(float[][] vectors, int k, int niter, int metric)`

### Step 3: Update consumers ✅
- [x] `ReorderTool.java` - Use new utilities
- [x] `E2EReorderScript.java` - Use new utilities
- [x] `VectorReorder.java` - Delegate to new utilities

### Step 4: Move tests to proper location ✅
- [x] Create `src/test/java/org/opensearch/knn/reorder/`
- [x] Move `KMeansTest.java` → `src/test/java/.../KMeansTest.java`
- [x] Move `ClusterSortTest.java` → `src/test/java/.../ClusterSortTest.java`
- [x] Move `FaissIndexRebuilderTest.java` → `src/test/java/.../FaissIndexRebuilderTest.java`
- [x] Move `FaissFilePermuterTest.java` → `src/test/java/.../FaissFilePermuterTest.java`
- [x] Delete `Sift128RebuildTest.java` (redundant with `ReorderTool`)

### Step 5: Clean up ✅
- [x] Remove dead code from `VectorReorder.java`
- [x] Update `build.gradle` test configuration
- [ ] Simplify test files

---

## Lines of Code Impact

| File | Before | After | Change |
|------|--------|-------|--------|
| ReorderTool.java | 130 | ~40 | -90 |
| E2EReorderScript.java | 150 | ~60 | -90 |
| Sift128RebuildTest.java | 106 | DELETE | -106 |
| VectorReorder.java | 300 | ~150 | -150 |
| VecFileIO.java (new) | 0 | ~80 | +80 |
| ClusterSorter.java (new) | 0 | ~50 | +50 |
| **Total** | ~686 | ~380 | **-306** |
