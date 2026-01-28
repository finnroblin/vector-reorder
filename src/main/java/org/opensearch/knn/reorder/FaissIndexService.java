/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

/**
 * JNI service for building FAISS HNSW indices.
 * 
 * Uses the same FAISS library as k-NN (linked at compile time from k-NN/jni/build).
 * Produces identical file format: IxMp (IndexIDMap) wrapping IHNf (IndexHNSWFlat).
 * 
 * This is equivalent to k-NN's JNIService.initIndex() + insertToIndex() + writeIndex()
 * but without requiring OpenSearch dependencies.
 */
public class FaissIndexService {
    
    static {
        System.loadLibrary("vectorreorder_faiss");
    }

    /**
     * Build a FAISS HNSW index from vectors and write to file.
     * 
     * @param vectorsAddress pointer to native memory where vectors are stored (n * dim floats, in insertion order)
     * @param numVectors number of vectors
     * @param dimension dimension of each vector
     * @param ids array of IDs for each vector (becomes the ID mapping in IxMp wrapper)
     * @param indexDescription FAISS index description (e.g., "HNSW16,Flat")
     * @param spaceType "l2" or "innerproduct" 
     * @param efConstruction ef_construction parameter for HNSW graph building
     * @param efSearch ef_search parameter for HNSW search (stored in index)
     * @param outputPath path to write the .faiss file
     */
    public static native void buildAndWriteIndex(
        long vectorsAddress, 
        int numVectors, 
        int dimension, 
        int[] ids,
        String indexDescription,
        String spaceType,
        int efConstruction,
        int efSearch,
        String outputPath
    );
}
