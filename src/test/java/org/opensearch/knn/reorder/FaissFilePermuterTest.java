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
 * Test for FaissFilePermuter parsing functionality.
 */
public class FaissFilePermuterTest {

    private static final String TEST_FILES_DIR = "raw_test_files";
    private static final String FAISS_FILE = "_z_165_train.faiss";

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
        testParseStructure(faissPath.toString());
        System.out.println("=== All tests passed ===");
    }

    private static void testParseStructure(String faissPath) throws IOException {
        System.out.println("Test: Parse FAISS structure");
        System.out.println("File: " + faissPath);
        System.out.println("Size: " + Files.size(Paths.get(faissPath)) + " bytes\n");

        FaissFilePermuter.FaissStructure s = FaissFilePermuter.parseStructure(faissPath);
        
        System.out.println("Parsed structure:");
        System.out.println("  Index type: " + s.indexType);
        System.out.println("  HNSW type: " + s.hnswType);
        System.out.println("  Flat type: " + s.flatType);
        System.out.println("  Dimension: " + s.dimension);
        System.out.println("  Num vectors: " + s.numVectors);
        System.out.println("  Max level: " + s.maxLevel);
        System.out.println("  Entry point: " + s.entryPoint);
        
        // Validate expected values for sift-128
        assert s.dimension == 128 : "Expected dimension 128";
        assert s.numVectors == 1000000 : "Expected 1M vectors";
        assert "IxMp".equals(s.indexType) : "Expected IxMp index type";
        assert "IHNf".equals(s.hnswType) : "Expected IHNf HNSW type";
        
        System.out.println("\n✓ Parse test passed");
    }
}
