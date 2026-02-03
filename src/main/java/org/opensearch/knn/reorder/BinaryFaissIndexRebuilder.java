/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import java.io.IOException;

/**
 * Builds binary quantized FAISS HNSW indexes.
 */
public class BinaryFaissIndexRebuilder {

    /**
     * Build a binary FAISS index with quantized vectors in the specified order.
     *
     * @param vectors      float vectors (in original order)
     * @param newOrder     newOrder[newIdx] = oldIdx - the reordering permutation
     * @param oldIdMapping oldIdMapping[oldIdx] = docID - from original FAISS file
     * @param qstate       quantization state for 1-bit quantization
     * @param outputPath   path for output .faiss file
     * @param hnswM        HNSW M parameter
     * @param efConstruction ef_construction parameter
     * @param efSearch     ef_search parameter
     */
    public static void rebuild(
        float[][] vectors,
        int[] newOrder,
        long[] oldIdMapping,
        QuantizationStateIO.OneBitState qstate,
        String outputPath,
        int hnswM,
        int efConstruction,
        int efSearch
    ) throws IOException {
        int n = vectors.length;
        int bytesPerVector = qstate.getBytesPerVector();
        int binaryDim = qstate.meanThresholds.length;
        // Align to 8 for FAISS binary index
        int alignedDim = (binaryDim + 7) & ~7;
        
        // Quantize and reorder vectors
        byte[] quantizedVectors = new byte[n * bytesPerVector];
        int[] newIdMapping = new int[n];
        
        for (int newIdx = 0; newIdx < n; newIdx++) {
            int oldIdx = newOrder[newIdx];
            byte[] quantized = QuantizationStateIO.quantize(vectors[oldIdx], qstate);
            System.arraycopy(quantized, 0, quantizedVectors, newIdx * bytesPerVector, bytesPerVector);
            newIdMapping[newIdx] = (int) oldIdMapping[oldIdx];
        }
        
        // Build binary FAISS index
        FaissIndexService.buildAndWriteBinaryIndex(
            quantizedVectors, n, alignedDim, newIdMapping,
            hnswM, efConstruction, efSearch, outputPath
        );
    }
}
