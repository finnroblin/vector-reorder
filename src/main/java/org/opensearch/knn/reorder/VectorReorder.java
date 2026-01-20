/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * CLI tool to inspect and cluster vectors from .vec files.
 */
public class VectorReorder {

    private static final String VECTOR_DATA_CODEC_NAME = "Lucene99FlatVectorsFormatData";

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: VectorReorder <path-to-vec-file> [print|load|cluster]");
            System.exit(1);
        }

        String vecFilePath = args[0];
        String cmd = args.length > 1 ? args[1] : "print";
        
        switch (cmd) {
            case "load" -> loadAllVectors(vecFilePath);
            case "cluster" -> clusterVectors(vecFilePath);
            default -> printFirst10Vectors(vecFilePath);
        }
    }

    /**
     * Prints the first 10 vectors from a .vec file.
     */
    public static void printFirst10Vectors(String vecFilePath) throws IOException {
        Path path = Paths.get(vecFilePath);
        VecFileIO.VecFileMeta meta = VecFileIO.readMetadata(vecFilePath);

        System.out.println("File: " + vecFilePath);
        System.out.println("Dimension: " + meta.dimension());
        System.out.println("Vector count: " + meta.size());
        System.out.println();

        try (FSDirectory directory = FSDirectory.open(path.getParent());
             IndexInput vecInput = directory.openInput(path.getFileName().toString(), IOContext.DEFAULT)) {
            
            CodecUtil.checkHeader(vecInput, VECTOR_DATA_CODEC_NAME, 0, 0);
            IndexInput slice = vecInput.slice("vectors", meta.dataOffset(), meta.dataLength());

            float[] buffer = new float[meta.dimension()];
            int vectorsToPrint = Math.min(10, meta.size());

            for (int i = 0; i < vectorsToPrint; i++) {
                slice.seek((long) i * meta.dimension() * Float.BYTES);
                slice.readFloats(buffer, 0, meta.dimension());
                System.out.println("Vector " + i + ": " + formatVector(buffer));
            }
        }
    }

    /**
     * Loads all vectors and prints every 100,000th.
     */
    public static void loadAllVectors(String vecFilePath) throws IOException {
        float[][] vectors = VecFileIO.loadVectors(vecFilePath);
        System.out.println("Loaded " + vectors.length + " vectors (" + vectors[0].length + " dims)");
        
        for (int i = 0; i < vectors.length; i += 100_000) {
            System.out.println("Vector " + i + ": " + formatVector(vectors[i]));
        }
    }

    /**
     * Cluster vectors and show sorting results.
     */
    public static void clusterVectors(String vecFilePath) throws IOException {
        clusterVectors(vecFilePath, FaissKMeansService.METRIC_L2);
    }

    public static void clusterVectors(String vecFilePath, int metricType) throws IOException {
        float[][] vectors = VecFileIO.loadVectors(vecFilePath);
        int n = vectors.length;
        int dim = vectors[0].length;
        int k = 100;

        System.out.println("Loaded " + n + " vectors");
        System.out.println("Vector 100000 BEFORE sort: " + formatVector(vectors[100_000]));

        String metricName = metricType == FaissKMeansService.METRIC_INNER_PRODUCT ? "inner_product" : "l2";
        System.out.println("Running k-means with k=" + k + ", metric=" + metricName + "...");
        
        long addr = FaissKMeansService.storeVectors(vectors);
        KMeansResult result = FaissKMeansService.kmeansWithDistances(addr, n, dim, k, 1, metricType);
        FaissKMeansService.freeVectors(addr);

        int[] newOrder = ClusterSorter.sortByCluster(result.assignments(), result.distances(), metricType);

        System.out.println("Vector 100000 AFTER sort: " + formatVector(vectors[newOrder[100_000]]));
        System.out.println("  (was original index " + newOrder[100_000] + 
                         ", cluster " + result.assignments()[newOrder[100_000]] + 
                         ", distance " + result.distances()[newOrder[100_000]] + ")");
        
        // Show first few vectors in cluster 0
        System.out.println("\nFirst 5 vectors in cluster 0:");
        int count = 0;
        for (int i = 0; i < n && count < 5; i++) {
            int origIdx = newOrder[i];
            if (result.assignments()[origIdx] == 0) {
                System.out.println("  idx=" + origIdx + ", distance=" + result.distances()[origIdx]);
                count++;
            }
        }
    }

    private static String formatVector(float[] vector) {
        if (vector.length <= 8) {
            return Arrays.toString(vector);
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 4; i++) {
            sb.append(String.format("%.4f", vector[i])).append(", ");
        }
        sb.append("..., ");
        for (int i = vector.length - 4; i < vector.length; i++) {
            sb.append(String.format("%.4f", vector[i]));
            if (i < vector.length - 1) sb.append(", ");
        }
        sb.append("]");
        return sb.toString();
    }
}
