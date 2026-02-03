/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Test for BpSegmentReorderer - creates a small index, reorders it, builds FAISS, and verifies.
 */
public class BpSegmentReordererTest {

    private static final String VECTOR_FIELD = "vector";
    private static final String ID_FIELD = "id";
    private static final int DIM = 4;
    private static final int NUM_DOCS = 100;

    public static void main(String[] args) throws Exception {
        Path tempDir = Files.createTempDirectory("bp-reorder-test");
        Path srcIndex = tempDir.resolve("src");
        Path dstIndex = tempDir.resolve("dst");
        Path faissPath = tempDir.resolve("index.faiss");
        
        try {
            // Create source index with vectors
            float[][] vectors = createIndex(srcIndex);
            System.out.println("Created source index with " + NUM_DOCS + " docs");

            // Reorder to destination with FAISS
            BpSegmentReorderer reorderer = new BpSegmentReorderer(VECTOR_FIELD);
            reorderer.reorder(srcIndex, dstIndex, 1, faissPath.toString());
            System.out.println("Reordered index and built FAISS");

            // Verify Lucene segment
            verifyIndex(dstIndex, vectors);
            System.out.println("✓ Lucene segment verification passed");

            // Verify FAISS file exists
            if (!Files.exists(faissPath)) {
                throw new AssertionError("FAISS file not created: " + faissPath);
            }
            long faissSize = Files.size(faissPath);
            System.out.println("✓ FAISS file created: " + faissSize + " bytes");

        } finally {
            // Cleanup
            deleteDir(srcIndex);
            deleteDir(dstIndex);
            Files.deleteIfExists(faissPath);
            Files.deleteIfExists(tempDir);
        }
    }

    private static float[][] createIndex(Path indexPath) throws IOException {
        Files.createDirectories(indexPath);
        float[][] vectors = new float[NUM_DOCS][DIM];
        
        try (Directory dir = FSDirectory.open(indexPath);
             IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig())) {
            
            for (int i = 0; i < NUM_DOCS; i++) {
                // Create vectors in clusters to make BP reordering meaningful
                int cluster = i / 10;
                vectors[i] = new float[] {
                    cluster + 0.1f * (i % 10),
                    cluster + 0.2f * (i % 10),
                    cluster + 0.3f * (i % 10),
                    cluster + 0.4f * (i % 10)
                };
                
                Document doc = new Document();
                doc.add(new KnnFloatVectorField(VECTOR_FIELD, vectors[i], VectorSimilarityFunction.EUCLIDEAN));
                doc.add(new StoredField(ID_FIELD, i));
                writer.addDocument(doc);
            }
        }
        return vectors;
    }

    private static void verifyIndex(Path indexPath, float[][] originalVectors) throws IOException {
        try (Directory dir = FSDirectory.open(indexPath);
             IndexReader reader = DirectoryReader.open(dir)) {
            
            if (reader.numDocs() != NUM_DOCS) {
                throw new AssertionError("Expected " + NUM_DOCS + " docs, got " + reader.numDocs());
            }

            for (int docId = 0; docId < reader.maxDoc(); docId++) {
                Document doc = reader.storedFields().document(docId);
                int originalId = doc.getField(ID_FIELD).numericValue().intValue();
                
                var leafReader = reader.leaves().get(0).reader();
                var vectorValues = leafReader.getFloatVectorValues(VECTOR_FIELD);
                var iterator = vectorValues.iterator();
                iterator.advance(docId);
                int ord = iterator.index();
                float[] vector = vectorValues.vectorValue(ord);
                
                float[] expected = originalVectors[originalId];
                for (int d = 0; d < DIM; d++) {
                    if (Math.abs(vector[d] - expected[d]) > 0.0001f) {
                        throw new AssertionError(
                            "Vector mismatch at docId=" + docId + ", originalId=" + originalId
                        );
                    }
                }
            }
        }
    }

    private static void deleteDir(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException e) { /* ignore */ }
                });
        }
    }
}
