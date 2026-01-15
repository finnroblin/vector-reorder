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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Test: Load sift-128 .vec file, cluster, rebuild FAISS index with cluster-sorted order.
 */
public class Sift128RebuildTest {

    public static void main(String[] args) throws Exception {
        String vecPath = "raw_test_files/_z_NativeEngines990KnnVectorsFormat_0.vec";
        String outputPath = System.getProperty("user.dir") + "/raw_test_files/sift128_reordered.faiss";
        
        int k = 1000;  // clusters
        
        // Load vectors from .vec file
        System.out.println("Loading vectors from " + vecPath);
        long loadStart = System.currentTimeMillis();
        float[][] vectors = loadVecFile(vecPath);
        int n = vectors.length;
        int dim = vectors[0].length;
        System.out.println("Loaded " + n + " vectors of dim " + dim + " in " + (System.currentTimeMillis() - loadStart) + " ms");
        
        // Cluster vectors
        System.out.println("Clustering with k=" + k + "...");
        long start = System.currentTimeMillis();
        long addr = FaissKMeansService.storeVectors(vectors);
        KMeansResult result = FaissKMeansService.kmeansWithDistances(addr, n, dim, k, 25, FaissKMeansService.METRIC_L2);
        FaissKMeansService.freeVectors(addr);
        System.out.println("Clustering took " + (System.currentTimeMillis() - start) + " ms");
        
        // Sort by (cluster_id, distance_to_centroid)
        System.out.println("Sorting by cluster...");
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
        
        // Build FAISS index
        System.out.println("Building FAISS index to: " + outputPath);
        start = System.currentTimeMillis();
        FaissIndexRebuilder.rebuild(vectors, newOrder, dim, outputPath, 16, 100, FaissIndexRebuilder.SPACE_L2);
        System.out.println("Index build took " + (System.currentTimeMillis() - start) + " ms");
        
        // Verify
        File f = new File(outputPath);
        if (f.exists()) {
            System.out.println("SUCCESS: Index created, size = " + f.length() + " bytes");
            FaissFilePermuter.FaissStructure s = FaissFilePermuter.parseStructure(outputPath);
            System.out.println("Parsed structure: " + s);
        } else {
            System.out.println("FAILED: Index file not created");
        }
    }
    
    /** Load vectors from Lucene99FlatVectorsFormat .vec file */
    private static float[][] loadVecFile(String vecFilePath) throws Exception {
        Path path = Paths.get(vecFilePath);
        Path dir = path.getParent();
        String vecFileName = path.getFileName().toString();
        String metaFileName = vecFileName.replace(".vec", ".vemf");

        try (FSDirectory directory = FSDirectory.open(dir);
             IndexInput meta = directory.openInput(metaFileName, IOContext.DEFAULT)) {
            
            // Skip header to field data
            int headerLength = CodecUtil.headerLength("Lucene99FlatVectorsFormatMeta") + 16 + 1 + "NativeEngines990KnnVectorsFormat_0".length();
            meta.seek(headerLength);
            
            meta.readInt(); // fieldNumber
            meta.readInt(); // vectorEncoding
            meta.readInt(); // similarityFunction
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
