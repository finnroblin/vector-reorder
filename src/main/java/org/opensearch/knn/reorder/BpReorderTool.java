/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import org.apache.lucene.store.FSDirectory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI tool: BP reorder vectors and produce reordered .faiss, .vec, .vemf, and .vord files.
 * 
 * Usage: BpReorderTool bp-reorder --vec <file1.vec> [--vec <file2.vec> ...] [--faiss <file1.faiss> ...]
 *                      [--space <l2|innerproduct>] [--ef-search <n>] [--ef-construction <n>] [--m <n>]
 * 
 * Output files:
 *   .faiss - HNSW index with vectors in BP order, ID mapping: faissId -> docId (only if --faiss specified)
 *   .vec   - Vectors in BP order
 *   .vemf  - Lucene metadata (dense format, ord==docId assumption - INCORRECT after reorder)
 *   .vord  - docToOrd mapping for correct exact search lookups
 *   .osknnqstate - Quantization state (copied unchanged if present)
 */
public class BpReorderTool {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        String cmd = args[0];
        if ("bp-reorder".equals(cmd)) {
            parseAndRunBpReorder(args);
        } else {
            // Legacy mode
            runLegacy(args);
        }
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  BpReorderTool bp-reorder --vec <file1.vec> [--vec <file2.vec> ...] [--faiss <file1.faiss> ...]");
        System.err.println("                [--space <l2|innerproduct>] [--ef-search <n>] [--ef-construction <n>] [--m <n>]");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  --vec             Path to .vec file (can specify multiple)");
        System.err.println("  --faiss           Path to .faiss file (can specify multiple, optional)");
        System.err.println("  --space           Space type: l2 (default) or innerproduct");
        System.err.println("  --ef-search       ef_search parameter for FAISS HNSW (default: 100)");
        System.err.println("  --ef-construction ef_construction parameter for FAISS HNSW (default: 100)");
        System.err.println("  --m               M parameter for FAISS HNSW (default: 16)");
    }

    private static void parseAndRunBpReorder(String[] args) throws Exception {
        List<String> vecFiles = new ArrayList<>();
        List<String> faissFiles = new ArrayList<>();
        String spaceType = "l2";
        int efSearch = 100;
        int efConstruction = 100;
        int m = 16;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--vec" -> { if (++i < args.length) vecFiles.add(args[i]); }
                case "--faiss" -> { if (++i < args.length) faissFiles.add(args[i]); }
                case "--space" -> { if (++i < args.length) spaceType = args[i]; }
                case "--ef-search" -> { if (++i < args.length) efSearch = Integer.parseInt(args[i]); }
                case "--ef-construction" -> { if (++i < args.length) efConstruction = Integer.parseInt(args[i]); }
                case "--m" -> { if (++i < args.length) m = Integer.parseInt(args[i]); }
            }
        }

        if (vecFiles.isEmpty()) {
            System.err.println("Error: At least one --vec file is required");
            printUsage();
            System.exit(1);
        }

        bpReorder(vecFiles, faissFiles, spaceType, efSearch, efConstruction, m);
    }

    public static void bpReorder(List<String> vecFiles, List<String> faissFiles,
                                  String spaceType, int efSearch, int efConstruction, int m) throws Exception {
        System.out.println("=== BP Vector Reorder Tool ===");
        System.out.println("Vec files: " + vecFiles);
        System.out.println("FAISS files: " + (faissFiles.isEmpty() ? "(none - skipping FAISS rebuild)" : faissFiles));
        System.out.println("Parameters: space=" + spaceType + ", ef_search=" + efSearch + 
                          ", ef_construction=" + efConstruction + ", m=" + m);
        System.out.println();

        // Load all vectors from all .vec files
        List<float[]> allVectors = new ArrayList<>();
        for (String vecFile : vecFiles) {
            System.out.println("Loading vectors from: " + vecFile);
            float[][] vectors = VecFileIO.loadVectors(vecFile);
            for (float[] v : vectors) allVectors.add(v);
        }

        int n = allVectors.size();
        int dim = allVectors.get(0).length;
        float[][] vectorArray = allVectors.toArray(new float[0][]);
        System.out.println("Total vectors loaded: " + n + " (dim=" + dim + ")");

        // Compute BP reordering
        System.out.println("Computing BP reordering...");
        long start = System.currentTimeMillis();
        int[] newOrder = BpReorderer.computePermutation(vectorArray);
        System.out.println("BP reordering took " + (System.currentTimeMillis() - start) + " ms");

        // Rebuild FAISS indices (only if --faiss was specified)
        if (!faissFiles.isEmpty()) {
            for (String faissFile : faissFiles) {
                String outputPath = faissFile.replace(".faiss", "_reordered.faiss");
                System.out.println("Rebuilding FAISS index: " + faissFile + " -> " + outputPath);
                
                // Read original ID mapping
                long[] oldIdMapping = FaissFilePermuter.readIdMapping(faissFile);
                
                FaissIndexRebuilder.rebuild(vectorArray, newOrder, oldIdMapping, dim, outputPath, 
                                            m, efConstruction, efSearch, spaceType);
            }
        }

        // Reorder .vec files
        for (String vecFile : vecFiles) {
            String outputPath = vecFile.replace(".vec", "_reordered.vec");
            System.out.println("Reordering .vec file: " + vecFile + " -> " + outputPath);
            VecFileIO.writeReordered(vecFile, outputPath, newOrder);

            // Also reorder .vemf if present
            String vemfPath = vecFile.replace(".vec", ".vemf");
            if (new File(vemfPath).exists()) {
                String outputVemfPath = outputPath.replace(".vec", ".vemf");
                System.out.println("Reordering .vemf file: " + vemfPath + " -> " + outputVemfPath);
                VemfFileIO.writeReordered(vemfPath, outputVemfPath, outputPath, newOrder);
            }

            // Copy .osknnqstate if present
            String qstatePath = vecFile.replace(".vec", ".osknnqstate");
            if (new File(qstatePath).exists()) {
                String outputQstatePath = outputPath.replace(".vec", ".osknnqstate");
                System.out.println("Copying .osknnqstate: " + qstatePath + " -> " + outputQstatePath);
                Files.copy(Path.of(qstatePath), Path.of(outputQstatePath), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        System.out.println("\nBP reorder complete!");
    }

    // Legacy mode for backwards compatibility
    private static void runLegacy(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Legacy usage: BpReorderTool <vec-file-path> <input-faiss-path> <output-faiss-path> <output-vec-path>");
            System.exit(1);
        }
        
        String vecPath = args[0];
        String inputFaissPath = args[1];
        String outputFaissPath = args[2];
        String outputVecPath = args[3];
        String outputVemfPath = args.length > 4 ? args[4] : outputVecPath.replace(".vec", ".vemf");
        String inputVemfPath = vecPath.replace(".vec", ".vemf");
        String inputQstatePath = vecPath.replace(".vec", ".osknnqstate");
        String outputQstatePath = outputVecPath.replace(".vec", ".osknnqstate");
        
        System.out.println("=== BP Vector Reorder Tool (Legacy Mode) ===");
        System.out.println("Input .vec:    " + vecPath);
        System.out.println("Input .vemf:   " + inputVemfPath);
        System.out.println("Input .faiss:  " + inputFaissPath);
        System.out.println("Output .faiss: " + outputFaissPath);
        System.out.println("Output .vec:   " + outputVecPath);
        System.out.println("Output .vemf:  " + outputVemfPath);
        
        File qstateFile = new File(inputQstatePath);
        boolean isQuantized = qstateFile.exists();
        if (isQuantized) {
            System.out.println("Quantization: ENABLED (.osknnqstate found)");
        } else {
            System.out.println("Quantization: DISABLED (no .osknnqstate)");
        }
        System.out.println();
        
        System.out.println("Loading vectors...");
        long start = System.currentTimeMillis();
        float[][] vectors = VecFileIO.loadVectors(vecPath);
        int n = vectors.length;
        int dim = vectors[0].length;
        System.out.println("Loaded " + n + " vectors of dim " + dim + " in " + (System.currentTimeMillis() - start) + " ms");
        
        System.out.println("Reading original ID mapping...");
        long[] oldIdMapping = FaissFilePermuter.readIdMapping(inputFaissPath);
        System.out.println("Read " + oldIdMapping.length + " ID mappings");
        
        int[] hnswParams = FaissFilePermuter.readHnswParams(inputFaissPath);
        int efConstruction = hnswParams[0];
        int efSearch = hnswParams[1];
        System.out.println("Original HNSW params: efConstruction=" + efConstruction + ", efSearch=" + efSearch);
        
        System.out.println("Computing BP reordering...");
        start = System.currentTimeMillis();
        int[] newOrder = BpReorderer.computePermutation(vectors);
        System.out.println("BP reordering took " + (System.currentTimeMillis() - start) + " ms");
        
        System.out.println("Building FAISS index...");
        start = System.currentTimeMillis();
        
        if (isQuantized) {
            QuantizationStateIO.OneBitState qstate = readQuantizationState(inputQstatePath, dim);
            System.out.println("  Quantization: 1-bit scalar, " + qstate.getBytesPerVector() + " bytes/vector");
            BinaryFaissIndexRebuilder.rebuild(vectors, newOrder, oldIdMapping, qstate, 
                                              outputFaissPath, 16, efConstruction, efSearch);
        } else {
            FaissIndexRebuilder.rebuild(vectors, newOrder, oldIdMapping, dim, outputFaissPath, 
                                        16, efConstruction, efSearch, FaissIndexRebuilder.SPACE_L2);
        }
        System.out.println("Index build took " + (System.currentTimeMillis() - start) + " ms");
        
        System.out.println("Writing reordered .vec file...");
        start = System.currentTimeMillis();
        VecFileIO.writeReordered(vecPath, outputVecPath, newOrder);
        System.out.println("Vec file write took " + (System.currentTimeMillis() - start) + " ms");
        
        System.out.println("Writing reordered .vemf file...");
        start = System.currentTimeMillis();
        VemfFileIO.writeReordered(inputVemfPath, outputVemfPath, outputVecPath, newOrder);
        System.out.println("Vemf file write took " + (System.currentTimeMillis() - start) + " ms");
        
        if (isQuantized) {
            System.out.println("Copying .osknnqstate file...");
            Files.copy(Path.of(inputQstatePath), Path.of(outputQstatePath), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Copied quantization state");
        }
        
        File faissFile = new File(outputFaissPath);
        File vecFile = new File(outputVecPath);
        File vemfFile = new File(outputVemfPath);
        File vordFile = new File(outputVemfPath.replace(".vemf", ".vord"));
        File outQstateFile = new File(outputQstatePath);
        System.out.println();
        if (faissFile.exists() && vecFile.exists() && vemfFile.exists() && vordFile.exists()) {
            System.out.println("SUCCESS!");
            System.out.println("  .faiss: " + outputFaissPath + " (" + faissFile.length() + " bytes)");
            System.out.println("  .vec:   " + outputVecPath + " (" + vecFile.length() + " bytes)");
            System.out.println("  .vemf:  " + outputVemfPath + " (" + vemfFile.length() + " bytes)");
            System.out.println("  .vord:  " + vordFile.getPath() + " (" + vordFile.length() + " bytes)");
            if (outQstateFile.exists()) {
                System.out.println("  .osknnqstate: " + outputQstatePath + " (" + outQstateFile.length() + " bytes)");
            }
            FaissFilePermuter.FaissStructure s = FaissFilePermuter.parseStructure(outputFaissPath);
            System.out.println("  Structure: " + s);
        } else {
            System.err.println("FAILED: Output files not created");
            System.exit(1);
        }
    }

    private static QuantizationStateIO.OneBitState readQuantizationState(String qstatePath, int expectedDim) throws Exception {
        Path path = Path.of(qstatePath);
        String fileName = path.getFileName().toString();
        String baseName = fileName.replace(".osknnqstate", "");
        
        int lastUnderscore = baseName.lastIndexOf('_');
        if (lastUnderscore == -1) {
            throw new IllegalArgumentException("Invalid .osknnqstate filename: " + fileName);
        }
        String segmentName = baseName.substring(0, lastUnderscore);
        String segmentSuffix = baseName.substring(lastUnderscore + 1);
        
        try (FSDirectory dir = FSDirectory.open(path.getParent())) {
            return QuantizationStateIO.readOneBitState(dir, segmentName, segmentSuffix, 0);
        }
    }
}
