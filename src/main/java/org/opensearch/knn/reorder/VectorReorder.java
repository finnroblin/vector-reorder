/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Tool to read and reorder vectors from .vec files for improved spatial locality.
 * Reads Lucene99FlatVectorsFormat files directly without needing the full index infrastructure.
 */
public class VectorReorder {

    private static final String VECTOR_DATA_CODEC_NAME = "Lucene99FlatVectorsFormatData";
    private static final String META_CODEC_NAME = "Lucene99FlatVectorsFormatMeta";

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: VectorReorder <path-to-vec-file> [copy|load|cluster]");
            System.exit(1);
        }

        String vecFilePath = args[0];
        if (args.length > 1 && "copy".equals(args[1])) {
            copyVectors(vecFilePath);
        } else if (args.length > 1 && "load".equals(args[1])) {
            loadAllVectors(vecFilePath);
        } else if (args.length > 1 && "cluster".equals(args[1])) {
            clusterVectors(vecFilePath);
        } else {
            printFirst10Vectors(vecFilePath);
        }
    }

    /**
     * Reads a .vec file and prints the first 10 vectors.
     * Parses the companion .vemf metadata file to get vector layout info.
     */
    public static void printFirst10Vectors(String vecFilePath) throws IOException {
        Path path = Paths.get(vecFilePath);
        Path dir = path.getParent();
        String vecFileName = path.getFileName().toString();
        String metaFileName = vecFileName.replace(".vec", ".vemf");

        try (FSDirectory directory = FSDirectory.open(dir)) {
            // Parse metadata
            VectorFieldMeta meta = readMetadata(directory, metaFileName);

            System.out.println("File: " + vecFilePath);
            System.out.println("Dimension: " + meta.dimension);
            System.out.println("Vector count: " + meta.size);
            System.out.println("Vector data offset: " + meta.vectorDataOffset);
            System.out.println("Vector data length: " + meta.vectorDataLength);
            System.out.println();

            // Read vectors directly from .vec file
            try (IndexInput vecInput = directory.openInput(vecFileName, IOContext.DEFAULT)) {
                // Verify header
                CodecUtil.checkHeader(vecInput, VECTOR_DATA_CODEC_NAME, 0, 0);

                // Create slice for vector data
                IndexInput vectorSlice = vecInput.slice("vectors", meta.vectorDataOffset, meta.vectorDataLength);

                int byteSize = meta.dimension * Float.BYTES;
                float[] buffer = new float[meta.dimension];
                int vectorsToPrint = Math.min(10, meta.size);

                for (int i = 0; i < vectorsToPrint; i++) {
                    vectorSlice.seek((long) i * byteSize);
                    vectorSlice.readFloats(buffer, 0, meta.dimension);
                    System.out.println("Vector " + i + ": " + formatVector(buffer));
                }
            }
        }
    }

    /**
     * Reads vector field metadata from .vemf file.
     * Format: header, then per-field: fieldNum(int), encoding(int), similarity(int), 
     *         offset(vlong), length(vlong), dimension(vint), size(int), ordToDoc config
     */
    private static VectorFieldMeta readMetadata(FSDirectory directory, String metaFileName) throws IOException {
        try (IndexInput meta = directory.openInput(metaFileName, IOContext.DEFAULT)) {
            // Skip the index header manually - it has variable length suffix
            // Header: magic(4) + codec_name_len(1) + codec_name + version(4) + segmentID(16) + suffix_len(1) + suffix
            int headerLength = CodecUtil.headerLength(META_CODEC_NAME) + 16 + 1 + "NativeEngines990KnnVectorsFormat_0".length();
            meta.seek(headerLength);

            // Read first field entry
            int fieldNumber = meta.readInt();
            if (fieldNumber == -1) {
                throw new IOException("No vector fields found in metadata");
            }

            // Skip encoding and similarity (each is an int)
            meta.readInt(); // vectorEncoding
            meta.readInt(); // similarityFunction

            // Read vector data location
            long vectorDataOffset = meta.readVLong();
            long vectorDataLength = meta.readVLong();

            // Read dimension and size
            int dimension = meta.readVInt();
            int size = meta.readInt();

            return new VectorFieldMeta(dimension, size, vectorDataOffset, vectorDataLength);
        }
    }

    private record VectorFieldMeta(int dimension, int size, long vectorDataOffset, long vectorDataLength) {}

    /**
     * Loads all vectors into memory and prints every 100,000th vector.
     */
    public static void loadAllVectors(String vecFilePath) throws IOException {
        Path path = Paths.get(vecFilePath);
        Path dir = path.getParent();
        String vecFileName = path.getFileName().toString();
        String metaFileName = vecFileName.replace(".vec", ".vemf");

        try (FSDirectory directory = FSDirectory.open(dir)) {
            VectorFieldMeta meta = readMetadata(directory, metaFileName);
            float[][] vectors = new float[meta.size][meta.dimension];

            try (IndexInput vecInput = directory.openInput(vecFileName, IOContext.DEFAULT)) {
                IndexInput vectorSlice = vecInput.slice("vectors", meta.vectorDataOffset, meta.vectorDataLength);
                for (int i = 0; i < meta.size; i++) {
                    vectorSlice.readFloats(vectors[i], 0, meta.dimension);
                }
            }

            System.out.println("Loaded " + meta.size + " vectors (" + meta.dimension + " dims) into memory");
            for (int i = 0; i < meta.size; i += 100_000) {
                System.out.println("Vector " + i + ": " + formatVector(vectors[i]));
            }
        }
    }

    /**
     * Cluster vectors using k-means and sort by (cluster_id, distance_to_centroid).
     */
    public static void clusterVectors(String vecFilePath) throws IOException {
        clusterVectors(vecFilePath, FaissKMeansService.METRIC_L2);
    }

    /**
     * Cluster vectors using k-means and sort by (cluster_id, distance_to_centroid).
     * @param metricType FaissKMeansService.METRIC_L2 or FaissKMeansService.METRIC_INNER_PRODUCT
     */
    public static void clusterVectors(String vecFilePath, int metricType) throws IOException {
        Path path = Paths.get(vecFilePath);
        Path dir = path.getParent();
        String vecFileName = path.getFileName().toString();
        String metaFileName = vecFileName.replace(".vec", ".vemf");

        try (FSDirectory directory = FSDirectory.open(dir)) {
            VectorFieldMeta meta = readMetadata(directory, metaFileName);
            float[][] vectors = new float[meta.size][meta.dimension];

            // Load vectors
            System.out.println("Loading " + meta.size + " vectors...");
            try (IndexInput vecInput = directory.openInput(vecFileName, IOContext.DEFAULT)) {
                IndexInput vectorSlice = vecInput.slice("vectors", meta.vectorDataOffset, meta.vectorDataLength);
                for (int i = 0; i < meta.size; i++) {
                    vectorSlice.readFloats(vectors[i], 0, meta.dimension);
                }
            }

            System.out.println("Vector 100000 BEFORE sort: " + formatVector(vectors[100_000]));

            // Run k-means with distances
            int k = 100;
            String metricName = metricType == FaissKMeansService.METRIC_INNER_PRODUCT ? "inner_product" : "l2";
            System.out.println("Running k-means with k=" + k + ", metric=" + metricName + "...");
            long addr = FaissKMeansService.storeVectors(vectors);
            KMeansResult result = FaissKMeansService.kmeansWithDistances(addr, meta.size, meta.dimension, k, 1, metricType);
            FaissKMeansService.freeVectors(addr);

            int[] assignments = result.assignments();
            float[] distances = result.distances();

            // Sort by (cluster_id, distance_to_centroid)
            Integer[] indices = new Integer[meta.size];
            for (int i = 0; i < meta.size; i++) indices[i] = i;
            
            Arrays.sort(indices, (a, b) -> {
                int clusterCmp = Integer.compare(assignments[a], assignments[b]);
                if (clusterCmp != 0) return clusterCmp;
                // For inner product, higher is better (closer), so reverse the comparison
                if (metricType == FaissKMeansService.METRIC_INNER_PRODUCT) {
                    return Float.compare(distances[b], distances[a]);
                }
                // For L2, lower distance is closer
                return Float.compare(distances[a], distances[b]);
            });

            // Reorder vectors
            float[][] sorted = new float[meta.size][];
            for (int i = 0; i < meta.size; i++) {
                sorted[i] = vectors[indices[i]];
            }

            System.out.println("Vector 100000 AFTER sort: " + formatVector(sorted[100_000]));
            System.out.println("  (was original index " + indices[100_000] + 
                             ", cluster " + assignments[indices[100_000]] + 
                             ", distance " + distances[indices[100_000]] + ")");
            
            // Show first few vectors in cluster 0 to verify distance ordering
            System.out.println("\nFirst 5 vectors in cluster 0 (should be sorted by distance):");
            int count = 0;
            for (int i = 0; i < meta.size && count < 5; i++) {
                int origIdx = indices[i];
                if (assignments[origIdx] == 0) {
                    System.out.println("  idx=" + origIdx + ", distance=" + distances[origIdx]);
                    count++;
                }
            }
        }
    }

    /**
     * Copies vectors from source .vec file to a new .vec file with "_reordered" suffix.
     * Uses buffered I/O with 1000 vectors per batch.
     */
    public static void copyVectors(String vecFilePath) throws IOException {
        Path path = Paths.get(vecFilePath);
        Path dir = path.getParent();
        String vecFileName = path.getFileName().toString();
        String metaFileName = vecFileName.replace(".vec", ".vemf");
        String outFileName = vecFileName.replace(".vec", "_reordered.vec");

        try (FSDirectory directory = FSDirectory.open(dir)) {
            VectorFieldMeta meta = readMetadata(directory, metaFileName);
            int batchSize = 1000;
            int batchBytes = meta.dimension * Float.BYTES * batchSize;
            byte[] buffer = new byte[batchBytes];

            try (IndexInput vecInput = directory.openInput(vecFileName, IOContext.DEFAULT);
                 IndexOutput out = directory.createOutput(outFileName, IOContext.DEFAULT)) {

                // Copy header
                byte[] header = new byte[(int) meta.vectorDataOffset];
                vecInput.readBytes(header, 0, header.length);
                out.writeBytes(header, header.length);

                // Copy vectors in batches
                IndexInput vectorSlice = vecInput.slice("vectors", meta.vectorDataOffset, meta.vectorDataLength);
                int vectorBytes = meta.dimension * Float.BYTES;
                for (int i = 0; i < meta.size; i += batchSize) {
                    int count = Math.min(batchSize, meta.size - i);
                    int bytes = count * vectorBytes;
                    vectorSlice.readBytes(buffer, 0, bytes);
                    out.writeBytes(buffer, bytes);
                }

                // Copy footer
                vecInput.seek(meta.vectorDataOffset + meta.vectorDataLength);
                byte[] footer = new byte[(int) (vecInput.length() - vecInput.getFilePointer())];
                vecInput.readBytes(footer, 0, footer.length);
                out.writeBytes(footer, footer.length);
            }
            System.out.println("Copied " + meta.size + " vectors to " + outFileName);
        }
    }

    private static String formatVector(float[] vector) {
        if (vector.length <= 8) {
            return Arrays.toString(vector);
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 4; i++) {
            sb.append(String.format("%.4f", vector[i]));
            sb.append(", ");
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
