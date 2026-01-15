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
 * Reorder tool: Takes a .vec file path, clusters vectors, produces reordered .faiss and .vec files.
 * 
 * Usage: ReorderTool <vec-file-path> <output-faiss-path> <output-vec-path> [num-clusters]
 */
public class ReorderTool {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: ReorderTool <vec-file-path> <output-faiss-path> <output-vec-path> [num-clusters]");
            System.exit(1);
        }
        
        String vecPath = args[0];
        String outputFaissPath = args[1];
        String outputVecPath = args[2];
        int k = args.length > 3 ? Integer.parseInt(args[3]) : 1000;
        
        System.out.println("=== Vector Reorder Tool ===");
        System.out.println("Input .vec:    " + vecPath);
        System.out.println("Output .faiss: " + outputFaissPath);
        System.out.println("Output .vec:   " + outputVecPath);
        System.out.println("Clusters: " + k);
        System.out.println();
        
        // Load vectors
        System.out.println("Loading vectors...");
        long start = System.currentTimeMillis();
        float[][] vectors = loadVecFile(vecPath);
        int n = vectors.length;
        int dim = vectors[0].length;
        System.out.println("Loaded " + n + " vectors of dim " + dim + " in " + (System.currentTimeMillis() - start) + " ms");
        
        // Cluster
        System.out.println("Clustering with k=" + k + "...");
        start = System.currentTimeMillis();
        long addr = FaissKMeansService.storeVectors(vectors);
        KMeansResult result = FaissKMeansService.kmeansWithDistances(addr, n, dim, k, 25, FaissKMeansService.METRIC_L2);
        FaissKMeansService.freeVectors(addr);
        System.out.println("Clustering took " + (System.currentTimeMillis() - start) + " ms");
        
        // Sort by (cluster_id, distance)
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
        System.out.println("Building FAISS index...");
        start = System.currentTimeMillis();
        FaissIndexRebuilder.rebuild(vectors, newOrder, dim, outputFaissPath, 16, 100, FaissIndexRebuilder.SPACE_L2);
        System.out.println("Index build took " + (System.currentTimeMillis() - start) + " ms");
        
        // Write reordered .vec file
        System.out.println("Writing reordered .vec file...");
        start = System.currentTimeMillis();
        VectorReorder.writeReorderedVecFile(vecPath, outputVecPath, newOrder);
        System.out.println("Vec file write took " + (System.currentTimeMillis() - start) + " ms");
        
        // Verify
        File faissFile = new File(outputFaissPath);
        File vecFile = new File(outputVecPath);
        System.out.println();
        if (faissFile.exists() && vecFile.exists()) {
            System.out.println("SUCCESS!");
            System.out.println("  .faiss: " + outputFaissPath + " (" + faissFile.length() + " bytes)");
            System.out.println("  .vec:   " + outputVecPath + " (" + vecFile.length() + " bytes)");
            FaissFilePermuter.FaissStructure s = FaissFilePermuter.parseStructure(outputFaissPath);
            System.out.println("  Structure: " + s);
        } else {
            System.err.println("FAILED: Output files not created");
            System.exit(1);
        }
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
