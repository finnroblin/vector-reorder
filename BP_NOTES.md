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
