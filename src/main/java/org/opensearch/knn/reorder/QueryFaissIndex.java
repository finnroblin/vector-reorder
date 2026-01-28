/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import java.util.Arrays;

/**
 * Query the FAISS index and verify results match expected vectors.
 */
public class QueryFaissIndex {

    public static void main(String[] args) throws Exception {
        String vecPath = "/Users/finnrobl/Documents/k-NN-2/vector-reorder/bp_files/_z_NativeEngines990KnnVectorsFormat_0.vec";
        String faissPath = "/Users/finnrobl/Documents/k-NN-2/vector-reorder/bp_files/_z_165_train.faiss";
        
        System.out.println("Loading vectors from .vec file...");
        float[][] vectors = VecFileIO.loadVectors(vecPath);
        System.out.println("Loaded " + vectors.length + " vectors");
        
        System.out.println("Reading ID mapping from FAISS...");
        long[] idMapping = FaissFilePermuter.readIdMapping(faissPath);
        
        // Query with vector at ordinal 0 - should return docID = idMapping[0]
        System.out.println("\n=== Query test ===");
        System.out.println("Vector at ordinal 0 should map to docID " + idMapping[0]);
        System.out.println("Vector at ordinal 1 should map to docID " + idMapping[1]);
        System.out.println("Vector at ordinal 100 should map to docID " + idMapping[100]);
        
        // Show first few values of vectors at different ordinals
        System.out.println("\nVector[0][0:3]: " + vectors[0][0] + ", " + vectors[0][1] + ", " + vectors[0][2]);
        System.out.println("Vector[1][0:3]: " + vectors[1][0] + ", " + vectors[1][1] + ", " + vectors[1][2]);
        System.out.println("Vector[100][0:3]: " + vectors[100][0] + ", " + vectors[100][1] + ", " + vectors[100][2]);
        
        // Now load original vectors and check
        String origVecPath = "/Users/finnrobl/Documents/k-NN-2/vector-reorder/raw_test_files/_z_NativeEngines990KnnVectorsFormat_0.vec";
        String origFaissPath = "/Users/finnrobl/Documents/k-NN-2/vector-reorder/raw_test_files/_z_165_train.faiss";
        
        System.out.println("\nLoading original vectors...");
        float[][] origVectors = VecFileIO.loadVectors(origVecPath);
        long[] origIdMapping = FaissFilePermuter.readIdMapping(origFaissPath);
        
        // For docID = idMapping[0], find original ordinal
        long docId0 = idMapping[0];
        int origOrd0 = -1;
        for (int i = 0; i < origIdMapping.length; i++) {
            if (origIdMapping[i] == docId0) {
                origOrd0 = i;
                break;
            }
        }
        
        System.out.println("\nDocID " + docId0 + " was at original ordinal " + origOrd0);
        System.out.println("Original vector[" + origOrd0 + "][0:3]: " + origVectors[origOrd0][0] + ", " + origVectors[origOrd0][1] + ", " + origVectors[origOrd0][2]);
        System.out.println("New vector[0][0:3]: " + vectors[0][0] + ", " + vectors[0][1] + ", " + vectors[0][2]);
        System.out.println("Match: " + Arrays.equals(origVectors[origOrd0], vectors[0]));
    }
}
