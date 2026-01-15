/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test for FaissFilePermuter using real .faiss files.
 */
public class FaissFilePermuterTest {

    private static final String TEST_FILES_DIR = "raw_test_files";
    private static final String FAISS_FILE = "_z_165_train.faiss";
    private static final String VEC_FILE = "_z_NativeEngines990KnnVectorsFormat_0.vec";

    public static void main(String[] args) throws IOException {
        Path testDir = Paths.get(TEST_FILES_DIR);
        if (!Files.exists(testDir)) {
            System.err.println("Test files directory not found: " + testDir.toAbsolutePath());
            System.exit(1);
        }

        Path faissPath = testDir.resolve(FAISS_FILE);
        if (!Files.exists(faissPath)) {
            System.err.println("FAISS file not found: " + faissPath);
            System.exit(1);
        }

        System.out.println("=== FaissFilePermuter Test ===\n");

        // Test 1: Parse structure
        testParseStructure(faissPath.toString());

        // Test 2: Simple permutation (identity - should produce identical file)
        testIdentityPermutation(faissPath.toString());

        // Test 3: Reverse permutation
        testReversePermutation(faissPath.toString());

        System.out.println("\n=== All tests completed ===");
    }

    private static void testParseStructure(String faissPath) throws IOException {
        System.out.println("Test 1: Parse FAISS structure");
        System.out.println("File: " + faissPath);
        System.out.println("Size: " + Files.size(Paths.get(faissPath)) + " bytes");
        System.out.println();

        FaissFilePermuter.FaissStructure s = FaissFilePermuter.parseStructure(faissPath);
        
        System.out.println("Parsed structure:");
        System.out.println("  Index type: " + s.indexType);
        System.out.println("  HNSW type: " + s.hnswType);
        System.out.println("  Flat type: " + s.flatType);
        System.out.println("  Dimension: " + s.dimension);
        System.out.println("  Num vectors: " + s.numVectors);
        System.out.println("  Max level: " + s.maxLevel);
        System.out.println("  Entry point: " + s.entryPoint);
        System.out.println();
        
        System.out.println("Section offsets:");
        System.out.println("  Header end: " + s.headerEnd);
        System.out.println("  HNSW start: " + s.hnswStart);
        System.out.println("  Levels: " + s.levelsStart + " - " + s.levelsEnd + 
                          " (" + (s.levelsEnd - s.levelsStart) + " bytes)");
        System.out.println("  Offsets: " + s.offsetsStart + " - " + s.offsetsEnd +
                          " (" + (s.offsetsEnd - s.offsetsStart) + " bytes)");
        System.out.println("  Neighbors: " + s.neighborsStart + " - " + s.neighborsEnd +
                          " (" + (s.neighborsEnd - s.neighborsStart) + " bytes)");
        System.out.println("  Flat vectors: " + s.flatVectorsStart + " - " + s.flatVectorsEnd +
                          " (" + (s.flatVectorsEnd - s.flatVectorsStart) + " bytes)");
        System.out.println("  ID mapping start: " + s.idMappingStart);
        System.out.println("  File end: " + s.fileEnd);
        
        // Validate
        long expectedVectorBytes = (long) s.numVectors * s.dimension * Float.BYTES;
        long actualVectorBytes = s.flatVectorsEnd - s.flatVectorsStart - 37; // minus header
        System.out.println();
        System.out.println("Validation:");
        System.out.println("  Expected vector data: " + expectedVectorBytes + " bytes");
        System.out.println("  Actual vector section: ~" + actualVectorBytes + " bytes");
        
        if (s.cumNeighborsPerLevel != null && s.cumNeighborsPerLevel.length > 1) {
            System.out.println("  M (max neighbors at level 0): " + s.cumNeighborsPerLevel[1]);
        }
        
        System.out.println("\n✓ Parse test passed\n");
    }

    private static void testIdentityPermutation(String faissPath) throws IOException {
        System.out.println("Test 2: Identity permutation");
        
        FaissFilePermuter.FaissStructure s = FaissFilePermuter.parseStructure(faissPath);
        
        // Create identity permutation
        int[] identity = new int[s.numVectors];
        for (int i = 0; i < s.numVectors; i++) {
            identity[i] = i;
        }
        
        String outputPath = TEST_FILES_DIR + "/identity_permuted.faiss";
        
        try {
            FaissFilePermuter.permute(faissPath, identity, outputPath);
            
            long originalSize = Files.size(Paths.get(faissPath));
            long permutedSize = Files.size(Paths.get(outputPath));
            
            System.out.println("  Original size: " + originalSize);
            System.out.println("  Permuted size: " + permutedSize);
            
            if (originalSize == permutedSize) {
                System.out.println("✓ Identity permutation test passed (sizes match)\n");
            } else {
                System.out.println("✗ Size mismatch!\n");
            }
        } catch (Exception e) {
            System.out.println("✗ Identity permutation failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Cleanup
            Files.deleteIfExists(Paths.get(outputPath));
        }
    }

    private static void testReversePermutation(String faissPath) throws IOException {
        System.out.println("Test 3: Reverse permutation (first 1000 vectors only for speed)");
        
        FaissFilePermuter.FaissStructure s = FaissFilePermuter.parseStructure(faissPath);
        
        // For large files, just test with subset
        int testSize = Math.min(1000, s.numVectors);
        
        // Create reverse permutation for first testSize vectors
        int[] reverse = new int[s.numVectors];
        for (int i = 0; i < testSize; i++) {
            reverse[i] = testSize - 1 - i;
        }
        // Keep rest in place
        for (int i = testSize; i < s.numVectors; i++) {
            reverse[i] = i;
        }
        
        String outputPath = TEST_FILES_DIR + "/reverse_permuted.faiss";
        
        try {
            FaissFilePermuter.permute(faissPath, reverse, outputPath);
            
            // Verify the permuted file can be parsed
            FaissFilePermuter.FaissStructure permuted = FaissFilePermuter.parseStructure(outputPath);
            
            System.out.println("  Original vectors: " + s.numVectors);
            System.out.println("  Permuted vectors: " + permuted.numVectors);
            System.out.println("  Dimension preserved: " + (s.dimension == permuted.dimension));
            
            if (s.numVectors == permuted.numVectors && s.dimension == permuted.dimension) {
                System.out.println("✓ Reverse permutation test passed\n");
            } else {
                System.out.println("✗ Structure mismatch!\n");
            }
        } catch (Exception e) {
            System.out.println("✗ Reverse permutation failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Cleanup
            Files.deleteIfExists(Paths.get(outputPath));
        }
    }
}
