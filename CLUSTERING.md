# K means clustering additional notes

See KMEANSIMPL.md for reference and setup.

What we need to do: come up with an optimal value for number of centroids hyperparameter adjustments.

We can start with sqrt(num_vectors) as this is the heuristic used for IVF.

Then go from - 1 order of magnitude (sqrt(n)/10) to sqrt(n) * 10 . The idea is to find a good balance of overall graph quality vs how long it takes to build the clustering step. I am expecting that around sqrt(n( should be enough))


How many clusters to choose?
- IVF takes following approach: sqrt(n) centroids. Rationale: 
The Trade-off
- Each inverted list contains ~n/nlist vectors on average
- Search cost ≈ nprobe × (n/nlist) distance computations
- To minimize this while keeping reasonable recall, you want lists small enough to be fast but large enough to be meaningful

Mathematical Intuition

If you set nlist = sqrt(n):
- Each list has ~sqrt(n) vectors
- Searching nprobe lists costs O(nprobe × sqrt(n))
- With nprobe = sqrt(n), total cost is O(n) but with much better constants than brute force

The sqrt(n) emerges as a natural balance point where:
- List size = sqrt(n)
- Number of lists = sqrt(n)

Note that this algorithm takes k-means clustering as a pre-ingestion step on hnsw graphs. The idea is to first cluster the dataset into centroids, then sort the vectors by (centroid_number, distance_of_this_vector_to_centroid). Then insert into hnsw graph. Hope: search time is slightly faster for hsnw search and recall is slightly higher. Another consideration: in opensearch k-NN search we provide a disk-based quantization scheme that uses scalar quantization to build a quantized hnsw graph, oversamples neighbors (say 500 neighbors for k=100), then rescores the full precision vectors (obtained from document ids) against the full precision query vector. The hope is with k-means clustering, when we load the full precision vectors from disk we see that more of the nearest vectors are loaded in each page of memory compared to a random insertion pattern.

We want to determine empirically the number of page faults...

Have a graph like number of centroids vs number of page faults [3rd highest priority — hard to get page fault metric]
- number of centroids vs recall (normal ef search) [second highest priotiy]
- number of centroids vs search latency [highest priority]
- number of centroids vs recall (low ef search)


1. Page locality: You want clusters large enough that multiple true neighbors land on the same disk page
   - If page = 4KB and vector = 512 floats (2KB), you get ~2 vectors/page
   - Cluster size should be >> page size to benefit from sequential reads
   - With n/nlist vectors per cluster, you want n/nlist to be hundreds to thousands

Since page size is so small we need to think more carefully about this.

With prefetch:

L2 cache: ~256KB-1MB typical
L3 cache: ~8-32MB typical
Vector size: 768 floats × 4 bytes = 3KB (typical embedding)

Vectors fitting in L3: ~8MB / 3KB ≈ 2700 vectors

If cluster size ≈ 1000-3000 vectors:
- Entire cluster fits in L3 cache
- Once first vector from cluster is read, rest are cache hits
- Prefetch distance of 8-16 vectors keeps pipeline full

### Recommended nlist for Lucene + Prefetch

nlist = n / 2000  (cluster size ~2000, fits in L3)


| n | nlist | cluster size |
|---|-------|--------------|
| 1M | 500 | 2000 |
| 10M | 5000 | 2000 |
| 100M | 50000 | 2000 |

So around num_docs / 2000.

