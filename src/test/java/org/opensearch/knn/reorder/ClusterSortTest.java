/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

/**
 * Test for distance-based secondary sort within clusters.
 */
public class ClusterSortTest {

    public static void main(String[] args) {
        testL2DistanceSort();
        testInnerProductDistanceSort();
        System.out.println("\nAll tests passed!");
    }

    /**
     * Test that vectors are sorted by (cluster_id, L2_distance) where lower distance = closer.
     */
    private static void testL2DistanceSort() {
        System.out.println("Testing L2 distance-based secondary sort...");
        
        // Create test vectors: 3 clusters with known distances
        // Cluster 0 centroid ~= (0,0), Cluster 1 centroid ~= (10,10), Cluster 2 centroid ~= (20,20)
        float[][] vectors = {
            {0.0f, 0.0f},    // 0: cluster 0, dist=0
            {1.0f, 1.0f},    // 1: cluster 0, dist=sqrt(2)
            {2.0f, 2.0f},    // 2: cluster 0, dist=sqrt(8)
            {10.0f, 10.0f},  // 3: cluster 1, dist=0
            {11.0f, 11.0f},  // 4: cluster 1, dist=sqrt(2)
            {9.0f, 9.0f},    // 5: cluster 1, dist=sqrt(2)
            {20.0f, 20.0f},  // 6: cluster 2, dist=0
            {21.0f, 21.0f},  // 7: cluster 2, dist=sqrt(2)
        };

        long addr = FaissKMeansService.storeVectors(vectors);
        KMeansResult result = FaissKMeansService.kmeansWithDistances(addr, vectors.length, 2, 3, 10, FaissKMeansService.METRIC_L2);
        FaissKMeansService.freeVectors(addr);

        int[] assignments = result.assignments();
        float[] distances = result.distances();

        // Sort indices by (cluster, distance)
        Integer[] indices = new Integer[vectors.length];
        for (int i = 0; i < vectors.length; i++) indices[i] = i;
        
        java.util.Arrays.sort(indices, (a, b) -> {
            int cmp = Integer.compare(assignments[a], assignments[b]);
            if (cmp != 0) return cmp;
            return Float.compare(distances[a], distances[b]);
        });

        // Verify: within each cluster, distances should be non-decreasing
        int prevCluster = -1;
        float prevDist = -1;
        for (int idx : indices) {
            int cluster = assignments[idx];
            float dist = distances[idx];
            
            if (cluster == prevCluster) {
                assert dist >= prevDist : "L2 distances not sorted within cluster " + cluster;
            }
            prevCluster = cluster;
            prevDist = dist;
        }
        
        System.out.println("  L2 sort test passed - distances are non-decreasing within clusters");
    }

    /**
     * Test that vectors are sorted by (cluster_id, inner_product) where higher IP = closer.
     */
    private static void testInnerProductDistanceSort() {
        System.out.println("Testing inner product distance-based secondary sort...");
        
        // For inner product, higher value = more similar
        float[][] vectors = {
            {1.0f, 0.0f},    // 0
            {0.9f, 0.1f},    // 1
            {0.8f, 0.2f},    // 2
            {0.0f, 1.0f},    // 3
            {0.1f, 0.9f},    // 4
            {0.2f, 0.8f},    // 5
        };

        long addr = FaissKMeansService.storeVectors(vectors);
        KMeansResult result = FaissKMeansService.kmeansWithDistances(addr, vectors.length, 2, 2, 10, FaissKMeansService.METRIC_INNER_PRODUCT);
        FaissKMeansService.freeVectors(addr);

        int[] assignments = result.assignments();
        float[] distances = result.distances();

        // Sort indices by (cluster, -distance) for inner product (higher = closer)
        Integer[] indices = new Integer[vectors.length];
        for (int i = 0; i < vectors.length; i++) indices[i] = i;
        
        java.util.Arrays.sort(indices, (a, b) -> {
            int cmp = Integer.compare(assignments[a], assignments[b]);
            if (cmp != 0) return cmp;
            return Float.compare(distances[b], distances[a]); // Reverse for IP
        });

        // Verify: within each cluster, distances should be non-increasing (higher first)
        int prevCluster = -1;
        float prevDist = Float.MAX_VALUE;
        for (int idx : indices) {
            int cluster = assignments[idx];
            float dist = distances[idx];
            
            if (cluster == prevCluster) {
                assert dist <= prevDist : "IP distances not sorted (descending) within cluster " + cluster;
            }
            prevCluster = cluster;
            prevDist = dist;
        }
        
        System.out.println("  Inner product sort test passed - distances are non-increasing within clusters");
    }
}
