/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import java.util.HashSet;
import java.util.Set;

/**
 * Verify ID mapping composition after BP reordering.
 */
public class VerifyIdMapping {

    public static void main(String[] args) throws Exception {
        String oldFaiss = "raw_test_files/_z_165_train.faiss";
        String newFaiss = "bp_files/_z_165_train.faiss";
        
        long[] oldMapping = FaissFilePermuter.readIdMapping(oldFaiss);
        long[] newMapping = FaissFilePermuter.readIdMapping(newFaiss);
        
        System.out.println("Old mapping (first 10): ");
        for (int i = 0; i < 10; i++) System.out.print(oldMapping[i] + " ");
        System.out.println();
        
        System.out.println("New mapping (first 10): ");
        for (int i = 0; i < 10; i++) System.out.print(newMapping[i] + " ");
        System.out.println();
        
        // Verify all docIDs are preserved (just reordered)
        Set<Long> oldSet = new HashSet<>();
        Set<Long> newSet = new HashSet<>();
        for (long id : oldMapping) oldSet.add(id);
        for (long id : newMapping) newSet.add(id);
        
        System.out.println("\nOld unique docIDs: " + oldSet.size());
        System.out.println("New unique docIDs: " + newSet.size());
        System.out.println("Sets equal: " + oldSet.equals(newSet));
    }
}
