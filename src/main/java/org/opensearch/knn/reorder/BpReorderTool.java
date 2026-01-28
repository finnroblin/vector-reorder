/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import java.io.File;

/**
 * CLI tool: BP reorder vectors and produce reordered .faiss, .vec, and .vemf files.
 * 
 * Usage: BpReorderTool <vec-file-path> <input-faiss-path> <output-faiss-path> <output-vec-path> [output-vemf-path]
 * 
 * If output-vemf-path is not provided, it defaults to output-vec-path with .vemf extension.
 */
public class BpReorderTool {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: BpReorderTool <vec-file-path> <input-faiss-path> <output-faiss-path> <output-vec-path> [output-vemf-path]");
            System.exit(1);
        }
        
        String vecPath = args[0];
        String inputFaissPath = args[1];
        String outputFaissPath = args[2];
        String outputVecPath = args[3];
        String outputVemfPath = args.length > 4 ? args[4] : outputVecPath.replace(".vec", ".vemf");
        String inputVemfPath = vecPath.replace(".vec", ".vemf");
        
        System.out.println("=== BP Vector Reorder Tool ===");
        System.out.println("Input .vec:    " + vecPath);
        System.out.println("Input .vemf:   " + inputVemfPath);
        System.out.println("Input .faiss:  " + inputFaissPath);
        System.out.println("Output .faiss: " + outputFaissPath);
        System.out.println("Output .vec:   " + outputVecPath);
        System.out.println("Output .vemf:  " + outputVemfPath);
        System.out.println();
        
        // Load vectors
        System.out.println("Loading vectors...");
        long start = System.currentTimeMillis();
        float[][] vectors = VecFileIO.loadVectors(vecPath);
        int n = vectors.length;
        int dim = vectors[0].length;
        System.out.println("Loaded " + n + " vectors of dim " + dim + " in " + (System.currentTimeMillis() - start) + " ms");
        
        // Read original ID mapping from FAISS file
        System.out.println("Reading original ID mapping...");
        long[] oldIdMapping = FaissFilePermuter.readIdMapping(inputFaissPath);
        System.out.println("Read " + oldIdMapping.length + " ID mappings");
        
        // Read HNSW params from original index
        int[] hnswParams = FaissFilePermuter.readHnswParams(inputFaissPath);
        int efConstruction = hnswParams[0];
        int efSearch = hnswParams[1];
        System.out.println("Original HNSW params: efConstruction=" + efConstruction + ", efSearch=" + efSearch);
        
        // Compute BP reordering
        System.out.println("Computing BP reordering...");
        start = System.currentTimeMillis();
        int[] newOrder = BpReorderer.computePermutation(vectors);
        System.out.println("BP reordering took " + (System.currentTimeMillis() - start) + " ms");
        
        // Build FAISS index with composed ID mapping
        System.out.println("Building FAISS index...");
        start = System.currentTimeMillis();
        FaissIndexRebuilder.rebuild(vectors, newOrder, oldIdMapping, dim, outputFaissPath, 16, efConstruction, efSearch, FaissIndexRebuilder.SPACE_L2);
        System.out.println("Index build took " + (System.currentTimeMillis() - start) + " ms");
        
        // Write reordered .vec file
        System.out.println("Writing reordered .vec file...");
        start = System.currentTimeMillis();
        VecFileIO.writeReordered(vecPath, outputVecPath, newOrder);
        System.out.println("Vec file write took " + (System.currentTimeMillis() - start) + " ms");
        
        // Write reordered .vemf file
        System.out.println("Writing reordered .vemf file...");
        start = System.currentTimeMillis();
        VemfFileIO.writeReordered(inputVemfPath, vecPath, outputVemfPath, outputVecPath, newOrder);
        System.out.println("Vemf file write took " + (System.currentTimeMillis() - start) + " ms");
        
        // Verify
        File faissFile = new File(outputFaissPath);
        File vecFile = new File(outputVecPath);
        File vemfFile = new File(outputVemfPath);
        System.out.println();
        if (faissFile.exists() && vecFile.exists() && vemfFile.exists()) {
            System.out.println("SUCCESS!");
            System.out.println("  .faiss: " + outputFaissPath + " (" + faissFile.length() + " bytes)");
            System.out.println("  .vec:   " + outputVecPath + " (" + vecFile.length() + " bytes)");
            System.out.println("  .vemf:  " + outputVemfPath + " (" + vemfFile.length() + " bytes)");
            FaissFilePermuter.FaissStructure s = FaissFilePermuter.parseStructure(outputFaissPath);
            System.out.println("  Structure: " + s);
        } else {
            System.err.println("FAILED: Output files not created");
            System.exit(1);
        }
    }
}
