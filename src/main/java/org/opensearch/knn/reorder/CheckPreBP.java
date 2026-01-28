package org.opensearch.knn.reorder;

public class CheckPreBP {
    public static void main(String[] args) throws Exception {
        String preBP = "/Users/finnrobl/Documents/k-NN-2/file-backups/20260126_161154-backups/_27_165_train.faiss";
        String postBP = "/Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/6rmnxTydThuknJeeqW6nEw/0/index/_27_165_train.faiss";
        
        System.out.println("=== PRE-BP ===");
        checkMapping(preBP);
        
        System.out.println("\n=== POST-BP ===");
        checkMapping(postBP);
    }
    
    static void checkMapping(String faissPath) throws Exception {
        long[] idMapping = FaissFilePermuter.readIdMapping(faissPath);
        
        System.out.println("ID mapping (first 10):");
        for (int i = 0; i < 10; i++) {
            System.out.println("  ordinal " + i + " -> docID " + idMapping[i]);
        }
        
        System.out.println("ordinal 210938 -> docID " + idMapping[210938]);
        System.out.println("ordinal 932085 -> docID " + idMapping[932085]);
        
        // Reverse: which ordinal maps to docID 932085?
        for (int i = 0; i < idMapping.length; i++) {
            if (idMapping[i] == 932085) {
                System.out.println("docID 932085 is at ordinal " + i);
                break;
            }
        }
    }
}
