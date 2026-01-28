package org.opensearch.knn.reorder;

import java.util.Arrays;

public class TestFaissSearch {
    static {
        System.loadLibrary("vectorreorder_faiss");
    }
    
    public static void main(String[] args) throws Exception {
        String faissPath = "/Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/6rmnxTydThuknJeeqW6nEw/0/index/_27_165_train.faiss";
        String vecPath = "/Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/6rmnxTydThuknJeeqW6nEw/0/index/_27_NativeEngines990KnnVectorsFormat_0.vec";
        
        // Load GT vector (train[932085] = [20, 5, 8, 54, 46, ...])
        float[][] vecs = VecFileIO.loadVectors(vecPath);
        
        // Find GT vector in vec file
        float[] gtVec = null;
        int gtOrdinal = -1;
        for (int i = 0; i < vecs.length; i++) {
            if (Math.abs(vecs[i][0] - 20) < 0.1 && Math.abs(vecs[i][1] - 5) < 0.1 && 
                Math.abs(vecs[i][2] - 8) < 0.1 && Math.abs(vecs[i][3] - 54) < 0.1) {
                gtVec = vecs[i];
                gtOrdinal = i;
                System.out.println("GT vector found at ordinal " + i);
                System.out.println("GT vector: " + Arrays.toString(Arrays.copyOf(gtVec, 5)));
                break;
            }
        }
        
        // Search FAISS for this vector
        System.out.println("\nSearching FAISS index...");
        long[] ids = searchFaiss(faissPath, gtVec, 10);
        System.out.println("FAISS returned IDs: " + Arrays.toString(ids));
        
        // The first ID should be 210938 (the Lucene docID for _id=932085)
        System.out.println("\nExpected first ID: 210938");
        System.out.println("Actual first ID: " + ids[0]);
    }
    
    private static native long[] searchFaiss(String indexPath, float[] query, int k);
}
