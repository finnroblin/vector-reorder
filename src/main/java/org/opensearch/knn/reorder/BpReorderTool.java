/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import java.io.File;

/**
 * CLI tool: BP reorder vectors and produce reordered .faiss, .vec, .vemf, and .vord files.
 * 
 * Usage: BpReorderTool <vec-file-path> <input-faiss-path> <output-faiss-path> <output-vec-path> [output-vemf-path]
 * 
 * Output files:
 *   .faiss - HNSW index with vectors in BP order, ID mapping: faissId -> docId
 *   .vec   - Vectors in BP order
 *   .vemf  - Lucene metadata (dense format, ord==docId assumption - INCORRECT after reorder)
 *   .vord  - docToOrd mapping for correct exact search lookups
 * 
 * After reorder, to look up vector for a docId:
 *   1. Read docToOrd from .vord
 *   2. ord = docToOrd[docId]
 *   3. Read vector at position ord from .vec
 * 
 * For ANN search, use FAISS directly - it has correct ID mapping.
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
        VemfFileIO.writeReordered(inputVemfPath, outputVemfPath, outputVecPath, newOrder);
        System.out.println("Vemf file write took " + (System.currentTimeMillis() - start) + " ms");
        
        // Verify
        File faissFile = new File(outputFaissPath);
        File vecFile = new File(outputVecPath);
        File vemfFile = new File(outputVemfPath);
        File vordFile = new File(outputVemfPath.replace(".vemf", ".vord"));
        System.out.println();
        if (faissFile.exists() && vecFile.exists() && vemfFile.exists() && vordFile.exists()) {
            System.out.println("SUCCESS!");
            System.out.println("  .faiss: " + outputFaissPath + " (" + faissFile.length() + " bytes)");
            System.out.println("  .vec:   " + outputVecPath + " (" + vecFile.length() + " bytes)");
            System.out.println("  .vemf:  " + outputVemfPath + " (" + vemfFile.length() + " bytes)");
            System.out.println("  .vord:  " + vordFile.getPath() + " (" + vordFile.length() + " bytes)");
            FaissFilePermuter.FaissStructure s = FaissFilePermuter.parseStructure(outputFaissPath);
            System.out.println("  Structure: " + s);
            System.out.println();
            System.out.println("NOTE: .vemf is in dense format (ord==docId assumption).");
            System.out.println("      .vord contains the actual docToOrd mapping for correct lookups.");
            System.out.println("      For exact search, use: ord = docToOrd[docId], then read vector at ord.");
        } else {
            System.err.println("FAILED: Output files not created");
            System.exit(1);
        }
    }
}
