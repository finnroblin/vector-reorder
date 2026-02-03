/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import org.apache.lucene.store.FSDirectory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * BP reorder tool for k-NN segments.
 * 
 * Reads vectors from .vec file, computes BP ordering, and produces:
 * - Reordered .faiss (quantized if .osknnqstate present)
 * - Reordered .vec, .vemf, .vord
 * - Copied .osknnqstate
 * 
 * Lucene segment rewrite is handled separately via BpSegmentReorderer.
 * 
 * Usage: E2EBpReorder <segment-dir> <output-dir>
 */
public class E2EBpReorder {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: E2EBpReorder <segment-dir> <vector-field> <output-dir>");
            System.exit(1);
        }

        new E2EBpReorder().run(args[0], args[2]);
    }

    public void run(String segmentDir, String outputDir) throws Exception {
        Path srcPath = Path.of(segmentDir);
        Path dstPath = Path.of(outputDir);
        Files.createDirectories(dstPath);

        System.out.println("=== BP Reorder for k-NN ===");
        System.out.println("Source:  " + srcPath);
        System.out.println("Output:  " + dstPath);

        // Find k-NN files
        String knnFormat = "NativeEngines990KnnVectorsFormat_0";
        File[] vecFiles = srcPath.toFile().listFiles((d, n) -> n.endsWith("_" + knnFormat + ".vec"));
        File[] vemfFiles = srcPath.toFile().listFiles((d, n) -> n.endsWith("_" + knnFormat + ".vemf"));
        File[] faissFiles = srcPath.toFile().listFiles((d, n) -> n.endsWith(".faiss"));
        File[] qstateFiles = srcPath.toFile().listFiles((d, n) -> n.endsWith(".osknnqstate"));

        if (vecFiles == null || vecFiles.length == 0) {
            throw new IllegalArgumentException("No .vec file found in " + segmentDir);
        }
        if (vemfFiles == null || vemfFiles.length == 0) {
            throw new IllegalArgumentException("No .vemf file found in " + segmentDir);
        }
        if (faissFiles == null || faissFiles.length == 0) {
            throw new IllegalArgumentException("No .faiss file found in " + segmentDir);
        }

        String vecFile = vecFiles[0].getAbsolutePath();
        String vemfFile = vemfFiles[0].getAbsolutePath();
        String faissFile = faissFiles[0].getAbsolutePath();
        String qstateFile = qstateFiles != null && qstateFiles.length > 0 ? qstateFiles[0].getAbsolutePath() : null;
        boolean isQuantized = qstateFile != null;

        System.out.println();
        System.out.println("Vec file:   " + vecFile);
        System.out.println("FAISS file: " + faissFile);
        System.out.println("Quantized:  " + (isQuantized ? "YES" : "NO"));

        // Step 1: Load vectors and compute BP ordering
        System.out.println();
        System.out.println("=== Step 1: Computing BP ordering ===");
        long start = System.currentTimeMillis();
        
        float[][] vectors = VecFileIO.loadVectors(vecFile);
        int n = vectors.length;
        int dim = vectors[0].length;
        System.out.println("Vectors: " + n + " x " + dim);

        int[] bpOrder = BpReorderer.computePermutation(vectors);
        System.out.println("BP ordering computed in " + (System.currentTimeMillis() - start) + " ms");

        // Step 2: Build reordered k-NN files
        System.out.println();
        System.out.println("=== Step 2: Building k-NN files ===");
        start = System.currentTimeMillis();

        long[] oldIdMapping = FaissFilePermuter.readIdMapping(faissFile);
        int[] hnswParams = FaissFilePermuter.readHnswParams(faissFile);
        int efConstruction = hnswParams[0];
        int efSearch = hnswParams[1];

        String srcVecName = new File(vecFile).getName();
        String srcFaissName = new File(faissFile).getName();
        String outVec = dstPath.resolve(srcVecName).toString();
        String outFaiss = dstPath.resolve(srcFaissName).toString();
        String outVemf = outVec.replace(".vec", ".vemf");

        // Build FAISS index
        if (isQuantized) {
            System.out.println("Building quantized FAISS index...");
            QuantizationStateIO.OneBitState qstate = readQuantizationState(qstateFile, vemfFile);
            BinaryFaissIndexRebuilder.rebuild(vectors, bpOrder, oldIdMapping, qstate,
                                              outFaiss, 16, efConstruction, efSearch);
        } else {
            System.out.println("Building float FAISS index...");
            FaissIndexRebuilder.rebuild(vectors, bpOrder, oldIdMapping, dim,
                                        outFaiss, 16, efConstruction, efSearch, FaissIndexRebuilder.SPACE_L2);
        }

        // Write reordered .vec file
        VecFileIO.writeReordered(vecFile, outVec, bpOrder);

        // Write .vemf and .vord
        String srcVemf = vecFile.replace(".vec", ".vemf");
        VemfFileIO.writeReordered(srcVemf, outVemf, outVec, bpOrder);

        // Copy .osknnqstate if present
        if (isQuantized) {
            String outQstate = outVec.replace(".vec", ".osknnqstate");
            Files.copy(Path.of(qstateFile), Path.of(outQstate), StandardCopyOption.REPLACE_EXISTING);
        }

        System.out.println("k-NN files built in " + (System.currentTimeMillis() - start) + " ms");

        // Summary
        System.out.println();
        System.out.println("=== Output Files ===");
        for (File f : dstPath.toFile().listFiles()) {
            System.out.println("  " + f.getName() + " (" + f.length() + " bytes)");
        }
        System.out.println();
        System.out.println("=== Done ===");
    }

    private QuantizationStateIO.OneBitState readQuantizationState(String qstatePath, String vemfPath) throws IOException {
        // Get field number from vemf
        VemfFileIO.VemfMeta vemfMeta = VemfFileIO.readMetadata(vemfPath);
        int fieldNumber = vemfMeta.fieldNumber();
        
        Path path = Path.of(qstatePath);
        String fileName = path.getFileName().toString();
        String baseName = fileName.replace(".osknnqstate", "");
        
        int lastUnderscore = baseName.lastIndexOf('_');
        String segmentName = baseName.substring(0, lastUnderscore);
        String segmentSuffix = baseName.substring(lastUnderscore + 1);
        
        try (FSDirectory dir = FSDirectory.open(path.getParent())) {
            return QuantizationStateIO.readOneBitState(dir, segmentName, segmentSuffix, fieldNumber);
        }
    }
}
