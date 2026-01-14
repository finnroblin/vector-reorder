/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

/**
 * JNI service for FAISS k-means clustering
 */
public class FaissKMeansService {
    
    public static final int METRIC_L2 = 0;
    public static final int METRIC_INNER_PRODUCT = 1;
    
    static {
        System.loadLibrary("vectorreorder_faiss");
    }

    /**
     * Run k-means clustering on vectors stored in native memory
     *
     * @param vectorsAddress pointer to native memory where vectors are stored (n * dim floats)
     * @param numVectors number of vectors
     * @param dimension dimension of each vector
     * @param numClusters number of clusters (k)
     * @param numIterations number of k-means iterations
     * @return cluster assignment for each vector (array of size numVectors)
     */
    public static native int[] kmeans(long vectorsAddress, int numVectors, int dimension, int numClusters, int numIterations);

    /**
     * Run k-means clustering and return both assignments and distances to centroids
     *
     * @param vectorsAddress pointer to native memory where vectors are stored
     * @param numVectors number of vectors
     * @param dimension dimension of each vector
     * @param numClusters number of clusters (k)
     * @param numIterations number of k-means iterations
     * @param metricType METRIC_L2 or METRIC_INNER_PRODUCT
     * @return KMeansResult containing assignments and distances
     */
    public static native KMeansResult kmeansWithDistances(long vectorsAddress, int numVectors, int dimension, 
                                                          int numClusters, int numIterations, int metricType);

    /**
     * Allocate native memory and copy vectors into it
     *
     * @param vectors 2D array of vectors [numVectors][dimension]
     * @return pointer to native memory
     */
    public static native long storeVectors(float[][] vectors);

    /**
     * Free native memory
     *
     * @param address pointer to native memory
     */
    public static native void freeVectors(long address);
}
