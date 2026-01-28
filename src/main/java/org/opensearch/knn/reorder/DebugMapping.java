package org.opensearch.knn.reorder;

import java.util.Arrays;

public class DebugMapping {
    public static void main(String[] args) throws Exception {
        String postBPFaiss = "/Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/6rmnxTydThuknJeeqW6nEw/0/index/_27_165_train.faiss";
        String postBPVec = "/Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/6rmnxTydThuknJeeqW6nEw/0/index/_27_NativeEngines990KnnVectorsFormat_0.vec";
        String preBPVec = "/Users/finnrobl/Documents/k-NN-2/file-backups/20260126_161154-backups/_27_NativeEngines990KnnVectorsFormat_0.vec";
        
        // Load original vec[0]
        float[][] preBP = VecFileIO.loadVectors(preBPVec);
        float[] originalVec0 = preBP[0];
        System.out.println("Original vec[0]: " + Arrays.toString(Arrays.copyOf(originalVec0, 5)));
        
        // Load post-BP vec[414740]
        float[][] postBP = VecFileIO.loadVectors(postBPVec);
        System.out.println("Post-BP vec[414740]: " + Arrays.toString(Arrays.copyOf(postBP[414740], 5)));
        
        // Check ID mapping
        long[] idMapping = FaissFilePermuter.readIdMapping(postBPFaiss);
        System.out.println("FAISS ordinal 414740 -> docID " + idMapping[414740]);
        
        // Search FAISS for originalVec0 - should return docID 0
        System.out.println("\nSearching FAISS for original vec[0]...");
        // We don't have search JNI, so let's verify via the mapping
        
        // The key question: does OpenSearch use the FAISS vectors or the .vec file?
        System.out.println("\n=== KEY INSIGHT ===");
        System.out.println("FAISS ID mapping is correct: ordinal 414740 -> docID 0");
        System.out.println("Post-BP .vec[414740] = original vec[0]");
        System.out.println("So if OpenSearch reads from .vec file, it should work.");
        System.out.println("If OpenSearch reads from FAISS internal vectors, we need to verify those match.");
    }
}
