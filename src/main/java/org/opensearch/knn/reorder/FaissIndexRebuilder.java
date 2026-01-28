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
     * Build a new FAISS index with vectors in the specified order, composing with existing ID mapping.
     *
     * @param vectors      all vectors (in original order)
     * @param newOrder     newOrder[newIdx] = oldIdx - the reordering permutation
     * @param oldIdMapping oldIdMapping[oldIdx] = docID - from original FAISS file
     * @param dim          vector dimension
     * @param outputPath   path for output .faiss file
     * @param m            HNSW M parameter (neighbors per node)
     * @param efConstruction ef_construction parameter
     * @param efSearch     ef_search parameter (stored in index for search)
     * @param spaceType    "l2" or "innerproduct"
     */
    public static void rebuild(
        float[][] vectors,
        int[] newOrder,
        long[] oldIdMapping,
        int dim,
        String outputPath,
        int m,
        int efConstruction,
        int efSearch,
        String spaceType
    ) throws IOException {
        int n = vectors.length;
        
        // Reorder vectors: reordered[newIdx] = vectors[oldIdx]
        float[][] reordered = new float[n][];
        for (int newIdx = 0; newIdx < n; newIdx++) {
            reordered[newIdx] = vectors[newOrder[newIdx]];
        }
        
        // Compose ID mapping: newIdMapping[newIdx] = oldIdMapping[oldIdx] = docID
        int[] newIdMapping = new int[n];
        for (int newIdx = 0; newIdx < n; newIdx++) {
            int oldIdx = newOrder[newIdx];
            newIdMapping[newIdx] = (int) oldIdMapping[oldIdx];
        }
        
        // Transfer reordered vectors to native memory
        long vectorsAddr = FaissKMeansService.storeVectors(reordered);
        
        try {
            String indexDescription = "HNSW" + m + ",Flat";
            FaissIndexService.buildAndWriteIndex(
                vectorsAddr, n, dim, newIdMapping,
                indexDescription, spaceType, efConstruction, efSearch,
                outputPath
            );
        } finally {
            FaissKMeansService.freeVectors(vectorsAddr);
        }
    }

    /**
     * Build a new FAISS index without existing ID mapping (uses ordinal as docID).
     */
    public static void rebuild(
        float[][] vectors,
        int[] newOrder,
        int dim,
        String outputPath,
        int m,
        int efConstruction,
        int efSearch,
        String spaceType
    ) throws IOException {
        // Create identity mapping: oldIdMapping[i] = i
        long[] identityMapping = new long[vectors.length];
        for (int i = 0; i < identityMapping.length; i++) {
            identityMapping[i] = newOrder[i];  // For backwards compat: use newOrder as ID mapping
        }
        rebuild(vectors, newOrder, identityMapping, dim, outputPath, m, efConstruction, efSearch, spaceType);
    }

    /**
     * Convenience method using default HNSW parameters.
     */
    public static void rebuild(float[][] vectors, int[] newOrder, int dim, String outputPath) throws IOException {
        rebuild(vectors, newOrder, dim, outputPath, 16, 100, 100, SPACE_L2);
    }
}
