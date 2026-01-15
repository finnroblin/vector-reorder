/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;

import java.io.File;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * E2E test script: Backup, reorder, and swap files for a k-NN index.
 * 
 * Usage: E2EReorderScript <index-dir>
 */
public class E2EReorderScript {

    private static final String BACKUP_BASE = "/Users/finnrobl/Documents/k-NN-2/file-backups";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: E2EReorderScript <index-dir>");
            System.exit(1);
        }
        
        String indexDir = args[0];
        int clusters = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        
        System.out.println("=== E2E Reorder Script ===");
        System.out.println("Index dir: " + indexDir);
        System.out.println();
        
        // Find .faiss and .vec files
        File dir = new File(indexDir);
        File faissFile = findFile(dir, ".faiss");
        File vecFile = findFile(dir, "_NativeEngines990KnnVectorsFormat_0.vec");
        
        if (faissFile == null || vecFile == null) {
            System.err.println("ERROR: Could not find .faiss or .vec files in " + indexDir);
            System.exit(1);
        }
        
        System.out.println("Found .faiss: " + faissFile.getName());
        System.out.println("Found .vec:   " + vecFile.getName());
        
        // Create backup directory
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path backupDir = Paths.get(BACKUP_BASE, timestamp + "-backups");
        Files.createDirectories(backupDir);
        
        // Backup originals
        System.out.println("\nBacking up to: " + backupDir);
        Files.copy(faissFile.toPath(), backupDir.resolve(faissFile.getName()));
        Files.copy(vecFile.toPath(), backupDir.resolve(vecFile.getName()));
        
        // Run reorder
        String outputFaiss = faissFile.getAbsolutePath().replace(".faiss", "_reordered.faiss");
        String outputVec = vecFile.getAbsolutePath().replace(".vec", "_reordered.vec");
        
        System.out.println("\nLoading vectors...");
        long start = System.currentTimeMillis();
        float[][] vectors = loadVecFile(vecFile.getAbsolutePath());
        int n = vectors.length;
        int dim = vectors[0].length;
        System.out.println("Loaded " + n + " vectors of dim " + dim + " in " + (System.currentTimeMillis() - start) + " ms");
        
        System.out.println("Clustering with k=" + clusters + "...");
        start = System.currentTimeMillis();
        long addr = FaissKMeansService.storeVectors(vectors);
        KMeansResult result = FaissKMeansService.kmeansWithDistances(addr, n, dim, clusters, 25, FaissKMeansService.METRIC_L2);
        FaissKMeansService.freeVectors(addr);
        System.out.println("Clustering took " + (System.currentTimeMillis() - start) + " ms");
        
        // Sort by (cluster_id, distance)
        int[] assignments = result.assignments();
        float[] distances = result.distances();
        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> {
            int cmp = Integer.compare(assignments[a], assignments[b]);
            return cmp != 0 ? cmp : Float.compare(distances[a], distances[b]);
        });
        int[] newOrder = new int[n];
        for (int i = 0; i < n; i++) newOrder[i] = indices[i];
        
        System.out.println("Building FAISS index...");
        start = System.currentTimeMillis();
        FaissIndexRebuilder.rebuild(vectors, newOrder, dim, outputFaiss, 16, 100, FaissIndexRebuilder.SPACE_L2);
        System.out.println("Index build took " + (System.currentTimeMillis() - start) + " ms");
        
        System.out.println("Writing reordered .vec file...");
        start = System.currentTimeMillis();
        VectorReorder.writeReorderedVecFile(vecFile.getAbsolutePath(), outputVec, newOrder);
        System.out.println("Vec file write took " + (System.currentTimeMillis() - start) + " ms");
        
        // Swap files
        System.out.println("\nSwapping files...");
        Files.delete(faissFile.toPath());
        Files.move(Paths.get(outputFaiss), faissFile.toPath());
        Files.delete(vecFile.toPath());
        Files.move(Paths.get(outputVec), vecFile.toPath());
        
        System.out.println();
        System.out.println("=== DONE ===");
        System.out.println("Backups at: " + backupDir);
        System.out.println();
        System.out.println("Now kill and restart cluster.");
    }
    
    private static File findFile(File dir, String suffix) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(suffix));
        return (files != null && files.length > 0) ? files[0] : null;
    }
    
    private static float[][] loadVecFile(String vecFilePath) throws Exception {
        Path path = Paths.get(vecFilePath);
        Path dir = path.getParent();
        String vecFileName = path.getFileName().toString();
        String metaFileName = vecFileName.replace(".vec", ".vemf");

        try (FSDirectory directory = FSDirectory.open(dir);
             IndexInput meta = directory.openInput(metaFileName, IOContext.DEFAULT)) {
            
            int headerLength = CodecUtil.headerLength("Lucene99FlatVectorsFormatMeta") + 16 + 1 + "NativeEngines990KnnVectorsFormat_0".length();
            meta.seek(headerLength);
            
            meta.readInt();
            meta.readInt();
            meta.readInt();
            long vectorDataOffset = meta.readVLong();
            long vectorDataLength = meta.readVLong();
            int dimension = meta.readVInt();
            int size = meta.readInt();
            
            float[][] vectors = new float[size][dimension];
            try (IndexInput vecInput = directory.openInput(vecFileName, IOContext.DEFAULT)) {
                IndexInput slice = vecInput.slice("vectors", vectorDataOffset, vectorDataLength);
                for (int i = 0; i < size; i++) {
                    slice.readFloats(vectors[i], 0, dimension);
                }
            }
            return vectors;
        }
    }
}
