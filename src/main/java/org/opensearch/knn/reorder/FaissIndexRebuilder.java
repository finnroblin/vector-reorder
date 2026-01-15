/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import java.io.IOException;

/**
 * Rebuilds a FAISS HNSW index with vectors inserted in cluster-sorted order.
 * This improves cache locality during search by ensuring nearby vectors have nearby IDs.
 */
public class FaissIndexRebuilder {

    // Space type constants
    public static final String SPACE_L2 = "l2";
    public static final String SPACE_INNER_PRODUCT = "innerproduct";

    /**
     * Build a new FAISS index with vectors in the specified order.
     *
     * @param vectors    all vectors (in original order)
     * @param newOrder   newOrder[newIdx] = oldIdx - the insertion order  
     * @param dim        vector dimension
     * @param outputPath path for output .faiss file
     * @param m          HNSW M parameter (neighbors per node)
     * @param efConstruction ef_construction parameter
     * @param spaceType  "l2" or "innerproduct"
     */
    public static void rebuild(
        float[][] vectors,
        int[] newOrder,
        int dim,
        String outputPath,
        int m,
        int efConstruction,
        String spaceType
    ) throws IOException {
        int n = vectors.length;
        
        // Reorder vectors in Java: reordered[newIdx] = vectors[newOrder[newIdx]]
        float[][] reordered = new float[n][];
        for (int newIdx = 0; newIdx < n; newIdx++) {
            reordered[newIdx] = vectors[newOrder[newIdx]];
        }
        
        // Transfer reordered vectors to native memory
        long vectorsAddr = FaissKMeansService.storeVectors(reordered);
        
        try {
            // Build index description (e.g., "HNSW16,Flat")
            String indexDescription = "HNSW" + m + ",Flat";
            
            // Build and write FAISS index via JNI
            // newOrder array becomes the ID mapping: idMapping[internalId] = luceneDocId
            FaissIndexService.buildAndWriteIndex(
                vectorsAddr, n, dim, newOrder,
                indexDescription, spaceType, efConstruction,
                outputPath
            );
        } finally {
            FaissKMeansService.freeVectors(vectorsAddr);
        }
    }

    /**
     * Convenience method using default HNSW parameters.
     */
    public static void rebuild(float[][] vectors, int[] newOrder, int dim, String outputPath) throws IOException {
        rebuild(vectors, newOrder, dim, outputPath, 16, 100, SPACE_L2);
    }
}
