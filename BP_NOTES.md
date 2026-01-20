I am trying to understand bipartite reordering in lucene which is an optimization to improve the compression/varint encoding of ordinals used in docids. THere are a few GH issues like the following:
https://github.com/opensearch-project/OpenSearch/issues/12257
https://github.com/apache/lucene/pull/12489
https://github.com/apache/lucene/issues/13565
Actual PR: https://github.com/apache/lucene/pull/14097

https://research.engineering.nyu.edu/~suel/papers/inter-vldb19.pdf
https://arxiv.org/pdf/1602.08820

recursively sorts the left and right halves.

the thing is that the original prs for lucene were focused on shared terms/some other core lucene optimization.

if our nodes are something like

level_1 
{1 : [50, 100, 150, 200, ...]}

->
{1: [50, 51, 52, 53]} ? after reordering I think? 
50 -> 50, 100 => 51 , 150 => 52, 200 => 53.


Looks like everything is in the BpVectorReorderer class.

Note that centroids are calculated to minimize sum of distances, maximizing the score.

Seems like basically a recursive mergesort.

Closer to a quickselect actually ,introselect once we've computed the bias terms.

Basically the Selector extends IntroSelector and the setPivot, comparePivot, and swap methods so that when the left and right side are swapped, there's an additional centroid bookkeeping calculation.

So the important part is that we have a partitioning scheme similar to quicksort , but I'm not sure if the pivot selection is based on the left or right centroid, or instead something else.

Partitioning is based on the bias, where bias[vec_id] is negative if vec_id is closer to left centroid and positive if vec_id is closer to right centroid.

There's a bit more stuff going through the computePermutation method o the BpVectorReorderer

() computeDocMap takes in a reader (the segment getting merged? I think it's once the floatvectorvalues have already been merged into one segment, where there's a SlowCOmpositeCodecReaaderWrapper (wraps all segments being merged into a single reader/"view").

But it also calls FloatVectroValues float =r eader.getFlatVectorvlaues(partitoinField). Then it computes a value map based on the floats, the VectorSimilarityFunction, and the taskExecutor. 

Finally it converts the valuemap back to a doc map with the valuemap, flooats, and reader.nextdoc. 

The valuemap internally is built by calling comptuePermutation on the vectors. Then computePermutation starts with all ids sorted i.e. 0, 1, ...., etc (per thread). 

Calls into the ReorderTask from there, which shuffles according to the left and the right centroid. The idea is to maximize bias -- every vector should be close to its centroid and far from its anti-centroid.

The merger takes in the modified docid map, and rebuilds the merged graph with new docids
