/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Test for BpReorderer using existing .vec file.
 */
public class BpReordererTest {

    private static final String TEST_FILES_DIR = "raw_test_files";
    private static final String VEC_FILE = "_z_NativeEngines990KnnVectorsFormat_0.vec";

    public static void main(String[] args) throws Exception {
        Path testDir = Paths.get(TEST_FILES_DIR);
        if (!Files.exists(testDir)) {
            System.err.println("Test files directory not found: " + testDir.toAbsolutePath());
            System.exit(1);
        }

        Path vecPath = testDir.resolve(VEC_FILE);
        if (!Files.exists(vecPath)) {
            System.err.println("Vec file not found: " + vecPath);
            System.exit(1);
        }

        System.out.println("=== BpReorderer Test ===\n");
        testBpReorderVecFile(vecPath.toString());
        testMultiFileBpReorder();
        testBpReorderWithoutFaiss();
        System.out.println("\n=== All tests passed ===");
    }

    private static void testBpReorderVecFile(String vecPath) throws IOException {
        System.out.println("Test: BP reorder .vec file and build FAISS index");
        System.out.println("File: " + vecPath);

        // Load vectors
        System.out.println("Loading vectors...");
        long start = System.currentTimeMillis();
        float[][] vectors = VecFileIO.loadVectors(vecPath);
        int n = vectors.length;
        int dim = vectors[0].length;
        System.out.println("Loaded " + n + " vectors of dim " + dim + " in " + (System.currentTimeMillis() - start) + " ms");

        // Compute BP reordering
        System.out.println("Computing BP reordering...");
        start = System.currentTimeMillis();
        int[] newOrder = BpReorderer.computePermutation(vectors);
        System.out.println("BP reordering took " + (System.currentTimeMillis() - start) + " ms");

        // Validate permutation
        assert newOrder.length == n : "Permutation length mismatch";
        boolean[] seen = new boolean[n];
        for (int i = 0; i < n; i++) {
            int oldIdx = newOrder[i];
            assert oldIdx >= 0 && oldIdx < n : "Invalid index in permutation: " + oldIdx;
            assert !seen[oldIdx] : "Duplicate index in permutation: " + oldIdx;
            seen[oldIdx] = true;
        }
        System.out.println("✓ Permutation is valid");

        // Show sample reordering
        System.out.println("\nSample reordering (first 5):");
        for (int i = 0; i < Math.min(5, n); i++) {
            System.out.println("  new[" + i + "] = old[" + newOrder[i] + "]");
        }

        // Build FAISS index with reordered vectors
        String outputFaiss = TEST_FILES_DIR + "/bp_reordered_test.faiss";
        System.out.println("\nBuilding FAISS index to: " + outputFaiss);
        start = System.currentTimeMillis();
        FaissIndexRebuilder.rebuild(vectors, newOrder, dim, outputFaiss, 16, 100, 100, FaissIndexRebuilder.SPACE_L2);
        System.out.println("FAISS index build took " + (System.currentTimeMillis() - start) + " ms");

        // Verify file was created
        File f = new File(outputFaiss);
        if (f.exists()) {
            System.out.println("✓ FAISS index created, size = " + f.length() + " bytes");
            FaissFilePermuter.FaissStructure s = FaissFilePermuter.parseStructure(outputFaiss);
            System.out.println("  Structure: " + s);
            f.delete();
        } else {
            throw new RuntimeException("FAISS index file not created");
        }

        System.out.println("\n✓ BP reorder test passed");
    }

    private static void testMultiFileBpReorder() throws Exception {
        System.out.println("\nTest: Multi-file BP reorder with FAISS rebuild");

        Path tempDir = Files.createTempDirectory("bp_test");
        try {
            // Create test vectors
            float[][] vectors = generateTestVectors(100, 8);
            
            // Store vectors and build initial FAISS index
            long addr = FaissKMeansService.storeVectors(vectors);
            int[] ids = new int[100];
            for (int i = 0; i < 100; i++) ids[i] = i;
            
            String inputFaiss = tempDir.resolve("input.faiss").toString();
            FaissIndexService.buildAndWriteIndex(addr, 100, 8, ids, "HNSW16,Flat", "l2", 100, 100, inputFaiss);
            FaissKMeansService.freeVectors(addr);

            // Write test .vec file (raw format for simplicity - won't work with VecFileIO but tests the flow)
            // For this test, we'll call bpReorder directly with vectors
            
            // Test with custom HNSW params
            int[] newOrder = BpReorderer.computePermutation(vectors);
            long[] oldIdMapping = FaissFilePermuter.readIdMapping(inputFaiss);
            
            String outputFaiss = tempDir.resolve("output.faiss").toString();
            FaissIndexRebuilder.rebuild(vectors, newOrder, oldIdMapping, 8, outputFaiss, 
                                        32, 128, 64, "l2");

            File f = new File(outputFaiss);
            assert f.exists() : "Output FAISS not created";
            
            FaissFilePermuter.FaissStructure s = FaissFilePermuter.parseStructure(outputFaiss);
            assert s.numVectors == 100 : "Expected 100 vectors";
            assert s.efConstruction == 128 : "Expected efConstruction=128, got " + s.efConstruction;
            assert s.efSearch == 64 : "Expected efSearch=64, got " + s.efSearch;
            
            System.out.println("  Structure: " + s);
            System.out.println("✓ Multi-file BP reorder with custom HNSW params passed");

        } finally {
            // Cleanup
            for (File f : tempDir.toFile().listFiles()) f.delete();
            tempDir.toFile().delete();
        }
    }

    private static void testBpReorderWithoutFaiss() throws Exception {
        System.out.println("\nTest: BP reorder without FAISS rebuild");

        float[][] vectors = generateTestVectors(50, 8);
        
        int[] newOrder = BpReorderer.computePermutation(vectors);
        
        // Verify permutation is valid
        assert newOrder.length == 50 : "Permutation length mismatch";
        boolean[] seen = new boolean[50];
        for (int idx : newOrder) {
            assert idx >= 0 && idx < 50 : "Invalid index";
            assert !seen[idx] : "Duplicate index";
            seen[idx] = true;
        }

        // Verify reordering works
        float[][] reordered = new float[50][];
        for (int i = 0; i < 50; i++) {
            reordered[i] = vectors[newOrder[i]];
        }
        assert reordered[0] != null : "Reordered vector is null";

        System.out.println("✓ BP reorder without FAISS passed (permutation valid, no FAISS built)");
    }

    private static float[][] generateTestVectors(int n, int dim) {
        float[][] vectors = new float[n][dim];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < dim; j++) {
                vectors[i][j] = (float) (i * dim + j);
            }
        }
        return vectors;
    }
}
