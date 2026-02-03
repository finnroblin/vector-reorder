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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CLI tool to inspect and cluster vectors from .vec files.
 */
public class VectorReorder {

    private static final String VECTOR_DATA_CODEC_NAME = "Lucene99FlatVectorsFormatData";

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        String cmd = args[0];
        
        switch (cmd) {
            case "kmeans-reorder" -> parseAndRunKmeansReorder(args);
            case "print" -> {
                if (args.length < 2) { printUsage(); System.exit(1); }
                printFirst10Vectors(args[1]);
            }
            case "load" -> {
                if (args.length < 2) { printUsage(); System.exit(1); }
                loadAllVectors(args[1]);
            }
            default -> {
                // Legacy: treat first arg as vec file path
                String vecFilePath = args[0];
                String legacyCmd = args.length > 1 ? args[1] : "print";
                switch (legacyCmd) {
                    case "load" -> loadAllVectors(vecFilePath);
                    case "cluster" -> kmeansReorder(vecFilePath);
                    default -> printFirst10Vectors(vecFilePath);
                }
            }
        }
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  VectorReorder kmeans-reorder --vec <file1.vec> [--vec <file2.vec> ...] [--faiss <file1.faiss> ...]");
        System.err.println("                        [--space <l2|innerproduct>] [--ef-search <n>] [--ef-construction <n>] [--m <n>]");
        System.err.println("  VectorReorder print <path-to-vec-file>");
        System.err.println("  VectorReorder load <path-to-vec-file>");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  --vec             Path to .vec file (can specify multiple)");
        System.err.println("  --faiss           Path to .faiss file (can specify multiple, optional)");
        System.err.println("  --space           Space type: l2 (default) or innerproduct");
        System.err.println("  --ef-search       ef_search parameter for FAISS HNSW (default: 100)");
        System.err.println("  --ef-construction ef_construction parameter for FAISS HNSW (default: 100)");
        System.err.println("  --m               M parameter for FAISS HNSW (default: 16)");
    }

    private static void parseAndRunKmeansReorder(String[] args) throws IOException {
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

        int metricType = "innerproduct".equalsIgnoreCase(spaceType) 
            ? FaissKMeansService.METRIC_INNER_PRODUCT 
            : FaissKMeansService.METRIC_L2;

        kmeansReorder(vecFiles, faissFiles, metricType, efSearch, efConstruction, m, spaceType);
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
     * K-means reorder vectors (single file, legacy).
     */
    public static void kmeansReorder(String vecFilePath) throws IOException {
        kmeansReorder(List.of(vecFilePath), List.of(), FaissKMeansService.METRIC_L2, 100, 100, 16, "l2");
    }

    /**
     * K-means reorder vectors from files with HNSW parameters.
     * Each .vec file is processed independently. If .faiss files are specified,
     * they must match 1:1 with .vec files by position.
     */
    public static void kmeansReorder(List<String> vecFiles, List<String> faissFiles, 
                                       int metricType, int efSearch, int efConstruction, int m, String spaceType) throws IOException {
        if (!faissFiles.isEmpty() && faissFiles.size() != vecFiles.size()) {
            throw new IllegalArgumentException("Number of .faiss files (" + faissFiles.size() + 
                ") must match number of .vec files (" + vecFiles.size() + ")");
        }

        System.out.println("=== K-Means Reorder ===");
        System.out.println("Vec files: " + vecFiles);
        System.out.println("FAISS files: " + (faissFiles.isEmpty() ? "(none)" : faissFiles));
        System.out.println("Parameters: space=" + spaceType + ", ef_search=" + efSearch + 
                          ", ef_construction=" + efConstruction + ", m=" + m);
        System.out.println();

        String metricName = metricType == FaissKMeansService.METRIC_INNER_PRODUCT ? "inner_product" : "l2";

        for (int i = 0; i < vecFiles.size(); i++) {
            String vecFile = vecFiles.get(i);
            String faissFile = faissFiles.isEmpty() ? null : faissFiles.get(i);

            System.out.println("Processing: " + vecFile);
            float[][] vectors = VecFileIO.loadVectors(vecFile);
            int n = vectors.length;
            int dim = vectors[0].length;
            int k = Math.min(100, n / 10);  // Adaptive k

            System.out.println("  Loaded " + n + " vectors (dim=" + dim + "), k=" + k);

            long addr = FaissKMeansService.storeVectors(vectors);
            KMeansResult result = FaissKMeansService.kmeansWithDistances(addr, n, dim, k, 1, metricType);
            FaissKMeansService.freeVectors(addr);

            int[] newOrder = ClusterSorter.sortByCluster(result.assignments(), result.distances(), metricType);

            // Reorder .vec file
            String outputVec = vecFile.replace(".vec", "_reordered.vec");
            System.out.println("  Writing: " + outputVec);
            VecFileIO.writeReordered(vecFile, outputVec, newOrder);

            // Rebuild FAISS if specified
            if (faissFile != null) {
                String outputFaiss = faissFile.replace(".faiss", "_reordered.faiss");
                System.out.println("  Rebuilding: " + outputFaiss);
                long[] oldIdMapping = FaissFilePermuter.readIdMapping(faissFile);
                FaissIndexRebuilder.rebuild(vectors, newOrder, oldIdMapping, dim, outputFaiss, m, efConstruction, efSearch, spaceType);
            }
        }

        System.out.println("\nK-means reorder complete!");
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
