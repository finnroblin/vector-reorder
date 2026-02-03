/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Test for VectorReorder multi-file clustering with HNSW parameters.
 */
public class VectorReorderTest {

    public static void main(String[] args) throws Exception {
        testMultiFileClusterWithFaissRebuild();
        testClusterWithoutFaiss();
        System.out.println("\nAll VectorReorder tests passed!");
    }

    private static void testMultiFileClusterWithFaissRebuild() throws Exception {
        System.out.println("Testing multi-file cluster with FAISS rebuild...");

        Path tempDir = Files.createTempDirectory("vectorreorder_test");
        try {
            float[][] vectors1 = generateTestVectors(100, 8, 0);
            float[][] vectors2 = generateTestVectors(100, 8, 100);
            
            float[][] allVectors = new float[200][];
            System.arraycopy(vectors1, 0, allVectors, 0, 100);
            System.arraycopy(vectors2, 0, allVectors, 100, 100);

            String faissOutput = tempDir.resolve("output.faiss").toString();

            int metricType = FaissKMeansService.METRIC_L2;
            int efSearch = 50;
            int efConstruction = 64;
            int m = 8;
            String spaceType = "l2";

            long addr = FaissKMeansService.storeVectors(allVectors);
            KMeansResult result = FaissKMeansService.kmeansWithDistances(addr, 200, 8, 10, 1, metricType);
            FaissKMeansService.freeVectors(addr);

            int[] newOrder = ClusterSorter.sortByCluster(result.assignments(), result.distances(), metricType);

            FaissIndexRebuilder.rebuild(allVectors, newOrder, 8, faissOutput, m, efConstruction, efSearch, spaceType);

            File faissFile = new File(faissOutput);
            assert faissFile.exists() : "FAISS output file not created";
            assert faissFile.length() > 0 : "FAISS output file is empty";

            FaissFilePermuter.FaissStructure structure = FaissFilePermuter.parseStructure(faissOutput);
            assert structure.numVectors == 200 : "Expected 200 vectors, got " + structure.numVectors;
            assert structure.dimension == 8 : "Expected dim 8, got " + structure.dimension;

            System.out.println("  Created FAISS index: " + faissFile.length() + " bytes");
            System.out.println("  Structure: " + structure);
            System.out.println("  PASSED");

        } finally {
            for (File f : tempDir.toFile().listFiles()) f.delete();
            tempDir.toFile().delete();
        }
    }

    private static void testClusterWithoutFaiss() throws Exception {
        System.out.println("Testing cluster without FAISS (vec-only reorder)...");

        float[][] vectors = generateTestVectors(100, 8, 0);

        int metricType = FaissKMeansService.METRIC_L2;

        long addr = FaissKMeansService.storeVectors(vectors);
        KMeansResult result = FaissKMeansService.kmeansWithDistances(addr, 100, 8, 5, 1, metricType);
        FaissKMeansService.freeVectors(addr);

        int[] newOrder = ClusterSorter.sortByCluster(result.assignments(), result.distances(), metricType);

        // Verify newOrder is a valid permutation
        assert newOrder.length == 100 : "Expected 100 elements in newOrder";
        boolean[] seen = new boolean[100];
        for (int idx : newOrder) {
            assert idx >= 0 && idx < 100 : "Invalid index in newOrder: " + idx;
            assert !seen[idx] : "Duplicate index in newOrder: " + idx;
            seen[idx] = true;
        }

        // Verify vectors can be reordered
        float[][] reordered = new float[100][];
        for (int i = 0; i < 100; i++) {
            reordered[i] = vectors[newOrder[i]];
        }
        assert reordered[0] != null : "Reordered vector is null";

        System.out.println("  Clustering produced valid permutation of 100 vectors");
        System.out.println("  PASSED (no FAISS rebuild performed)");
    }

    private static float[][] generateTestVectors(int n, int dim, int offset) {
        float[][] vectors = new float[n][dim];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < dim; j++) {
                vectors[i][j] = (float) ((i + offset) * dim + j);
            }
        }
        return vectors;
    }
}
