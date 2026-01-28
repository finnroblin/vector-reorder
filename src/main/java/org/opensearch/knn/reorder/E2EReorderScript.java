/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import java.io.File;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * E2E script: Backup, reorder, and swap files for a k-NN index.
 * 
 * Usage: E2EReorderScript <index-dir> [num-clusters]
 */
public class E2EReorderScript {

    private static final String BACKUP_BASE = "/Users/finnrobl/Documents/k-NN-2/file-backups";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: E2EReorderScript <index-dir> [num-clusters]");
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
        
        // Load vectors
        System.out.println("\nLoading vectors...");
        long start = System.currentTimeMillis();
        float[][] vectors = VecFileIO.loadVectors(vecFile.getAbsolutePath());
        int n = vectors.length;
        int dim = vectors[0].length;
        System.out.println("Loaded " + n + " vectors of dim " + dim + " in " + (System.currentTimeMillis() - start) + " ms");
        
        // Cluster and sort
        System.out.println("Clustering with k=" + clusters + "...");
        start = System.currentTimeMillis();
        int[] newOrder = ClusterSorter.clusterAndSort(vectors, clusters);
        System.out.println("Clustering took " + (System.currentTimeMillis() - start) + " ms");
        
        // Build FAISS index
        String outputFaiss = faissFile.getAbsolutePath().replace(".faiss", "_reordered.faiss");
        String outputVec = vecFile.getAbsolutePath().replace(".vec", "_reordered.vec");
        
        System.out.println("Building FAISS index...");
        start = System.currentTimeMillis();
        FaissIndexRebuilder.rebuild(vectors, newOrder, dim, outputFaiss, 16, 100, 100, FaissIndexRebuilder.SPACE_L2);
        System.out.println("Index build took " + (System.currentTimeMillis() - start) + " ms");
        
        // Write reordered .vec file
        System.out.println("Writing reordered .vec file...");
        start = System.currentTimeMillis();
        VecFileIO.writeReordered(vecFile.getAbsolutePath(), outputVec, newOrder);
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
}
