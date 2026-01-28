/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

/**
 * Check ID mapping in original vs reordered FAISS files.
 */
public class CheckIdMapping {

    public static void main(String[] args) throws Exception {
        String origFaiss = "/Users/finnrobl/Documents/k-NN-2/vector-reorder/raw_test_files/_z_165_train.faiss";
        String newFaiss = "/Users/finnrobl/Documents/k-NN-2/vector-reorder/bp_files/_z_165_train.faiss";
        
        long[] origMapping = FaissFilePermuter.readIdMapping(origFaiss);
        long[] newMapping = FaissFilePermuter.readIdMapping(newFaiss);
        
        System.out.println("Original ID mapping (first 10):");
        for (int i = 0; i < 10; i++) {
            System.out.println("  ordinal " + i + " -> docID " + origMapping[i]);
        }
        
        System.out.println("\nNew ID mapping (first 10):");
        for (int i = 0; i < 10; i++) {
            System.out.println("  ordinal " + i + " -> docID " + newMapping[i]);
        }
        
        // Check if original is identity mapping
        boolean origIsIdentity = true;
        for (int i = 0; i < origMapping.length; i++) {
            if (origMapping[i] != i) {
                origIsIdentity = false;
                break;
            }
        }
        System.out.println("\nOriginal is identity mapping: " + origIsIdentity);
    }
}
