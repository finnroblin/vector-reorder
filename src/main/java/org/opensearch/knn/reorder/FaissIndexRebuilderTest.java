/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import java.io.File;
import java.util.Arrays;

/**
 * Test for FaissIndexRebuilder - builds a small FAISS index with cluster-sorted vectors.
 */
public class FaissIndexRebuilderTest {

    public static void main(String[] args) throws Exception {
        int n = 1000;
        int dim = 32;
        int k = 10;  // clusters
        
        System.out.println("Creating " + n + " random vectors of dimension " + dim);
        
        // Create random vectors
        float[][] vectors = new float[n][dim];
        java.util.Random rand = new java.util.Random(42);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < dim; j++) {
                vectors[i][j] = rand.nextFloat();
            }
        }
        
        // Cluster vectors
        System.out.println("Clustering with k=" + k);
        long addr = FaissKMeansService.storeVectors(vectors);
        KMeansResult result = FaissKMeansService.kmeansWithDistances(addr, n, dim, k, 10, FaissKMeansService.METRIC_L2);
        FaissKMeansService.freeVectors(addr);
        
        int[] assignments = result.assignments();
        float[] distances = result.distances();
        
        // Sort by (cluster_id, distance_to_centroid)
        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) indices[i] = i;
        
        Arrays.sort(indices, (a, b) -> {
            int cmp = Integer.compare(assignments[a], assignments[b]);
            if (cmp != 0) return cmp;
            return Float.compare(distances[a], distances[b]);
        });
        
        // newOrder[newIdx] = oldIdx
        int[] newOrder = new int[n];
        for (int i = 0; i < n; i++) {
            newOrder[i] = indices[i];
        }
        
        System.out.println("First 10 in newOrder: " + Arrays.toString(Arrays.copyOf(newOrder, 10)));
        System.out.println("  (cluster assignments: " + assignments[newOrder[0]] + ", " + 
                          assignments[newOrder[1]] + ", " + assignments[newOrder[2]] + "...)");
        
        // Build FAISS index
        String outputPath = System.getProperty("user.dir") + "/test_rebuilt.faiss";
        System.out.println("Building FAISS index to: " + outputPath);
        
        FaissIndexRebuilder.rebuild(vectors, newOrder, dim, outputPath);
        
        // Verify file was created
        File f = new File(outputPath);
        if (f.exists()) {
            System.out.println("SUCCESS: Index created, size = " + f.length() + " bytes");
            
            // Parse the structure to verify
            FaissFilePermuter.FaissStructure s = FaissFilePermuter.parseStructure(outputPath);
            System.out.println("Parsed structure: " + s);
            
            // Clean up
            f.delete();
        } else {
            System.out.println("FAILED: Index file not created");
        }
    }
}
