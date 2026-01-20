/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import java.io.File;

/**
 * CLI tool: Cluster vectors and produce reordered .faiss and .vec files.
 * 
 * Usage: ReorderTool <vec-file-path> <output-faiss-path> <output-vec-path> [num-clusters]
 */
public class ReorderTool {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: ReorderTool <vec-file-path> <output-faiss-path> <output-vec-path> [num-clusters]");
            System.exit(1);
        }
        
        String vecPath = args[0];
        String outputFaissPath = args[1];
        String outputVecPath = args[2];
        int k = args.length > 3 ? Integer.parseInt(args[3]) : 1000;
        
        System.out.println("=== Vector Reorder Tool ===");
        System.out.println("Input .vec:    " + vecPath);
        System.out.println("Output .faiss: " + outputFaissPath);
        System.out.println("Output .vec:   " + outputVecPath);
        System.out.println("Clusters: " + k);
        System.out.println();
        
        // Load vectors
        System.out.println("Loading vectors...");
        long start = System.currentTimeMillis();
        float[][] vectors = VecFileIO.loadVectors(vecPath);
        int n = vectors.length;
        int dim = vectors[0].length;
        System.out.println("Loaded " + n + " vectors of dim " + dim + " in " + (System.currentTimeMillis() - start) + " ms");
        
        // Cluster and sort
        System.out.println("Clustering with k=" + k + "...");
        start = System.currentTimeMillis();
        int[] newOrder = ClusterSorter.clusterAndSort(vectors, k);
        System.out.println("Clustering took " + (System.currentTimeMillis() - start) + " ms");
        
        // Build FAISS index
        System.out.println("Building FAISS index...");
        start = System.currentTimeMillis();
        FaissIndexRebuilder.rebuild(vectors, newOrder, dim, outputFaissPath, 16, 100, FaissIndexRebuilder.SPACE_L2);
        System.out.println("Index build took " + (System.currentTimeMillis() - start) + " ms");
        
        // Write reordered .vec file
        System.out.println("Writing reordered .vec file...");
        start = System.currentTimeMillis();
        VecFileIO.writeReordered(vecPath, outputVecPath, newOrder);
        System.out.println("Vec file write took " + (System.currentTimeMillis() - start) + " ms");
        
        // Verify
        File faissFile = new File(outputFaissPath);
        File vecFile = new File(outputVecPath);
        System.out.println();
        if (faissFile.exists() && vecFile.exists()) {
            System.out.println("SUCCESS!");
            System.out.println("  .faiss: " + outputFaissPath + " (" + faissFile.length() + " bytes)");
            System.out.println("  .vec:   " + outputVecPath + " (" + vecFile.length() + " bytes)");
            FaissFilePermuter.FaissStructure s = FaissFilePermuter.parseStructure(outputFaissPath);
            System.out.println("  Structure: " + s);
        } else {
            System.err.println("FAILED: Output files not created");
            System.exit(1);
        }
    }
}
