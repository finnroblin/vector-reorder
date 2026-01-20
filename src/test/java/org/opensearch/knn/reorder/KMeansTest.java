/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

/**
 * Simple test for k-means JNI binding
 */
public class KMeansTest {
    public static void main(String[] args) {
        // Small test data: 6 vectors of dimension 2
        float[][] vectors = {
            {1.0f, 1.0f},
            {1.5f, 1.5f},
            {5.0f, 5.0f},
            {5.5f, 5.5f},
            {9.0f, 9.0f},
            {9.5f, 9.5f}
        };
        
        int n = vectors.length;
        int dim = vectors[0].length;
        int k = 3;
        int niter = 10;
        
        System.out.println("Running k-means with " + n + " vectors, dim=" + dim + ", k=" + k);
        
        // Store vectors in native memory
        long vectorsAddr = FaissKMeansService.storeVectors(vectors);
        
        // Run k-means
        int[] assignments = FaissKMeansService.kmeans(vectorsAddr, n, dim, k, niter);
        
        // Print results
        for (int i = 0; i < n; i++) {
            System.out.println("Vector " + i + " [" + vectors[i][0] + ", " + vectors[i][1] + "] -> cluster " + assignments[i]);
        }
        
        // Free native memory
        FaissKMeansService.freeVectors(vectorsAddr);
        
        System.out.println("Done!");
    }
}
