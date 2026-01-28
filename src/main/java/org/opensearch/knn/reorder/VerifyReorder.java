/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import java.util.Arrays;

/**
 * Verify that BP reordering preserved vector-docID associations correctly.
 * 
 * For a few sample docIDs, check that:
 * 1. The vector at the new position matches the original vector for that docID
 * 2. The ID mapping correctly maps the new position back to the docID
 */
public class VerifyReorder {

    public static void main(String[] args) throws Exception {
        String origVecPath = "/Users/finnrobl/Documents/k-NN-2/vector-reorder/raw_test_files/_z_NativeEngines990KnnVectorsFormat_0.vec";
        String origFaissPath = "/Users/finnrobl/Documents/k-NN-2/vector-reorder/raw_test_files/_z_165_train.faiss";
        String newVecPath = "/Users/finnrobl/Documents/k-NN-2/vector-reorder/bp_files/_z_NativeEngines990KnnVectorsFormat_0.vec";
        String newFaissPath = "/Users/finnrobl/Documents/k-NN-2/vector-reorder/bp_files/_z_165_train.faiss";
        
        System.out.println("Loading original vectors...");
        float[][] origVectors = VecFileIO.loadVectors(origVecPath);
        System.out.println("Loading new vectors...");
        float[][] newVectors = VecFileIO.loadVectors(newVecPath);
        
        System.out.println("Reading original ID mapping...");
        long[] origIdMapping = FaissFilePermuter.readIdMapping(origFaissPath);
        System.out.println("Reading new ID mapping...");
        long[] newIdMapping = FaissFilePermuter.readIdMapping(newFaissPath);
        
        int n = origVectors.length;
        System.out.println("Vector count: " + n);
        
        // Build reverse mapping: docID -> newOrdinal
        // newIdMapping[newOrd] = docID, so we need docID -> newOrd
        int maxDocId = 0;
        for (long id : newIdMapping) maxDocId = Math.max(maxDocId, (int) id);
        int[] docIdToNewOrd = new int[maxDocId + 1];
        Arrays.fill(docIdToNewOrd, -1);
        for (int newOrd = 0; newOrd < newIdMapping.length; newOrd++) {
            docIdToNewOrd[(int) newIdMapping[newOrd]] = newOrd;
        }
        
        // Also build: docID -> origOrd
        int[] docIdToOrigOrd = new int[maxDocId + 1];
        Arrays.fill(docIdToOrigOrd, -1);
        for (int origOrd = 0; origOrd < origIdMapping.length; origOrd++) {
            docIdToOrigOrd[(int) origIdMapping[origOrd]] = origOrd;
        }
        
        // Check a sample of docIDs
        int[] sampleDocIds = {0, 1, 100, 1000, 10000, 100000, 500000, 999999};
        System.out.println("\n=== Checking sample docIDs ===");
        
        int errors = 0;
        for (int docId : sampleDocIds) {
            if (docId >= docIdToOrigOrd.length) continue;
            
            int origOrd = docIdToOrigOrd[docId];
            int newOrd = docIdToNewOrd[docId];
            
            if (origOrd < 0 || newOrd < 0) {
                System.out.println("DocID " + docId + ": missing mapping (origOrd=" + origOrd + ", newOrd=" + newOrd + ")");
                errors++;
                continue;
            }
            
            float[] origVec = origVectors[origOrd];
            float[] newVec = newVectors[newOrd];
            
            boolean match = Arrays.equals(origVec, newVec);
            System.out.println("DocID " + docId + ": origOrd=" + origOrd + " -> newOrd=" + newOrd + " vectors " + (match ? "MATCH" : "MISMATCH"));
            
            if (!match) {
                errors++;
                // Show first few values
                System.out.println("  orig[0:3]: " + origVec[0] + ", " + origVec[1] + ", " + origVec[2]);
                System.out.println("  new[0:3]:  " + newVec[0] + ", " + newVec[1] + ", " + newVec[2]);
            }
        }
        
        // Full verification: check ALL vectors
        System.out.println("\n=== Full verification ===");
        int mismatches = 0;
        for (int docId = 0; docId <= maxDocId && mismatches < 10; docId++) {
            int origOrd = docIdToOrigOrd[docId];
            int newOrd = docIdToNewOrd[docId];
            if (origOrd < 0 || newOrd < 0) continue;
            
            if (!Arrays.equals(origVectors[origOrd], newVectors[newOrd])) {
                System.out.println("MISMATCH at docId=" + docId + " (origOrd=" + origOrd + ", newOrd=" + newOrd + ")");
                mismatches++;
            }
        }
        
        if (mismatches == 0) {
            System.out.println("All vectors verified correctly!");
        } else {
            System.out.println("Found " + mismatches + " mismatches (showing first 10)");
        }
        
        System.out.println("\nTotal errors: " + errors);
    }
}
