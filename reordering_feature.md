# Reordering feature

Idea: use the mergesort-like BpVectorReorderer from Lucene code. It is implemented as a merge policy.

See BP_NOTES.md for an explanation of the algorithm. 

But the jist of it is to create a new entrypoint in reorderer command so that we can read all the vectors in the .vec file and then call the following:

```
  void reorderIndexDirectory(Directory directory, Executor executor) throws IOException {
    try (IndexReader reader = DirectoryReader.open(directory)) {
      IndexWriterConfig iwc = new IndexWriterConfig();
      iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
      try (IndexWriter writer = new IndexWriter(directory, iwc)) {
        for (LeafReaderContext ctx : reader.leaves()) {
          CodecReader codecReader = (CodecReader) ctx.reader();
          writer.addIndexes(
            // computeDocMap calls into BpVectorReorder.computeValueMap which calls into BpVectorReorderer.computePermutation , which performs the reordering based on vector similarity.
              SortingCodecReader.wrap(
                  codecReader, computeDocMap(codecReader, null, executor), null));
        }
      }
    }
  }
```

Once the new .vec file is created, call into Faiss insert to create a new faiss hnsw graph matching the new order.

Add this and use the bottom of this md document as a worklog

# Worklog

## 2026-01-23
- Added `lucene-misc` dependency (10.3.2)
- Copied `BpVectorReorderer`, `AbstractBPReorderer`, and `IndexReorderer` from local Lucene source to `src/main/java/org/apache/lucene/misc/index/`
- Made `computeValueMap` public for external access
- Created `BpReorderer` wrapper class in `org.opensearch.knn.reorder` package
- Created `BpReordererTest` that tests BP reordering on existing `.vec` file (1M vectors, 128 dims)
- Test passes: BP reordering took ~45s, FAISS index build took ~116s for 1M vectors
- Output FAISS index: 664MB, structure: IxMp/IHNf/IxF2, maxLevel=5