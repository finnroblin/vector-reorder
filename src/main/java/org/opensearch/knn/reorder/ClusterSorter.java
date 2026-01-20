/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import java.util.Arrays;

/**
 * Utility class for clustering vectors and sorting by cluster assignment.
 */
public class ClusterSorter {

    /**
     * Sort indices by (cluster_id, distance_to_centroid).
     * TODO: make sure to sort by cluster_id
     * @return newOrder where newOrder[newIdx] = oldIdx
     */
    public static int[] sortByCluster(int[] assignments, float[] distances, int metricType) {
        int n = assignments.length;
        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) indices[i] = i;

        final boolean reverseDistance = (metricType == FaissKMeansService.METRIC_INNER_PRODUCT);
        
        Arrays.sort(indices, (a, b) -> {
            int cmp = Integer.compare(assignments[a], assignments[b]);
            if (cmp != 0) return cmp;
            // For inner product, higher is better (reverse); for L2, lower is better
            return reverseDistance 
                ? Float.compare(distances[b], distances[a])
                : Float.compare(distances[a], distances[b]);
        });

        int[] newOrder = new int[n];
        for (int i = 0; i < n; i++) newOrder[i] = indices[i];
        return newOrder;
    }

    /**
     * Cluster vectors and return sorted order.
     * @return newOrder where newOrder[newIdx] = oldIdx
     */
    public static int[] clusterAndSort(float[][] vectors, int k, int niter, int metricType) {
        int n = vectors.length;
        int dim = vectors[0].length;
        
        long addr = FaissKMeansService.storeVectors(vectors);
        try {
            KMeansResult result = FaissKMeansService.kmeansWithDistances(addr, n, dim, k, niter, metricType);
            return sortByCluster(result.assignments(), result.distances(), metricType);
        } finally {
            FaissKMeansService.freeVectors(addr);
        }
    }

    /**
     * Cluster vectors with L2 metric and return sorted order.
     */
    public static int[] clusterAndSort(float[][] vectors, int k) {
        return clusterAndSort(vectors, k, 25, FaissKMeansService.METRIC_L2);
    }
}
