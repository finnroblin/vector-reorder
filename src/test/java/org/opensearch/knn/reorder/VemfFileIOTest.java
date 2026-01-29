/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test for VemfFileIO - reading and rewriting .vemf files.
 */
public class VemfFileIOTest {

    private static final String TEST_FILES_DIR = "raw_test_files";
    private static final String VEC_FILE = "_z_NativeEngines990KnnVectorsFormat_0.vec";
    private static final String VEMF_FILE = "_z_NativeEngines990KnnVectorsFormat_0.vemf";
    private static final String OUTPUT_DIR = "test_output";

    public static void main(String[] args) throws Exception {
        Path testDir = Paths.get(TEST_FILES_DIR);
        if (!Files.exists(testDir)) {
            System.err.println("Test files directory not found: " + testDir.toAbsolutePath());
            System.exit(1);
        }

        System.out.println("=== VemfFileIO Test ===\n");
        
        testReadMetadata();
        testWriteReordered();
        
        System.out.println("\n=== All tests passed ===");
    }

    private static void testReadMetadata() throws Exception {
        System.out.println("Test: Read .vemf metadata");
        
        Path vemfPath = Paths.get(TEST_FILES_DIR, VEMF_FILE);
        VemfFileIO.VemfMeta meta = VemfFileIO.readMetadata(vemfPath.toString());
        
        System.out.println("  segmentSuffix: '" + meta.segmentSuffix() + "'");
        System.out.println("  fieldNumber: " + meta.fieldNumber());
        System.out.println("  dimension: " + meta.dimension());
        System.out.println("  size: " + meta.size());
        System.out.println("  isDense: " + meta.isDense());
        
        assert meta.dimension() == 128 : "Expected dimension 128";
        assert meta.size() == 1000000 : "Expected 1M vectors";
        assert meta.isDense() : "Expected dense mapping";
        
        System.out.println("✓ Read metadata test passed\n");
    }

    private static void testWriteReordered() throws Exception {
        System.out.println("Test: Write reordered .vemf and .vord files");
        
        Path outputDir = Paths.get(OUTPUT_DIR);
        Files.createDirectories(outputDir);
        
        Path srcVemfPath = Paths.get(TEST_FILES_DIR, VEMF_FILE);
        Path srcVecPath = Paths.get(TEST_FILES_DIR, VEC_FILE);
        Path dstVemfPath = outputDir.resolve("reordered.vemf");
        Path dstVecPath = outputDir.resolve("reordered.vec");
        Path dstVordPath = outputDir.resolve("reordered.vord");
        
        VemfFileIO.VemfMeta srcMeta = VemfFileIO.readMetadata(srcVemfPath.toString());
        int testSize = 100;
        
        // Create reverse permutation for testing
        int[] newOrder = new int[testSize];
        for (int i = 0; i < testSize; i++) {
            newOrder[i] = testSize - 1 - i;
        }
        
        // Copy subset of vec file
        copyVecSubset(srcVecPath.toString(), dstVecPath.toString(), testSize, srcMeta.dimension());
        
        // Write reordered .vemf and .vord using VemfFileIO
        VemfFileIO.writeReordered(srcVemfPath.toString(), dstVemfPath.toString(), dstVecPath.toString(), newOrder);
        
        // Verify files exist
        assert Files.exists(dstVemfPath) : ".vemf not created";
        assert Files.exists(dstVordPath) : ".vord not created";
        
        // Read back .vemf
        VemfFileIO.VemfMeta dstMeta = VemfFileIO.readMetadata(dstVemfPath.toString());
        System.out.println("  Output .vemf isDense: " + dstMeta.isDense());
        assert dstMeta.isDense() : ".vemf should be dense format";
        
        // Read back .vord and verify mapping
        int[] docToOrd = VemfFileIO.readDocToOrd(dstVordPath.toString());
        System.out.println("  Output .vord size: " + docToOrd.length);
        assert docToOrd.length == testSize : "docToOrd size mismatch";
        
        // Verify the mapping is correct
        // newOrder[newOrd] = docId, so docToOrd[docId] = newOrd
        // For reverse: newOrder = [99, 98, ..., 0]
        // docToOrd[99] = 0, docToOrd[98] = 1, ..., docToOrd[0] = 99
        for (int newOrd = 0; newOrd < testSize; newOrd++) {
            int docId = newOrder[newOrd];
            assert docToOrd[docId] == newOrd : "Mapping error at docId=" + docId;
        }
        System.out.println("  docToOrd mapping verified correct");
        
        // Cleanup
        Files.deleteIfExists(dstVemfPath);
        Files.deleteIfExists(dstVecPath);
        Files.deleteIfExists(dstVordPath);
        Files.deleteIfExists(outputDir);
        
        System.out.println("✓ Write reordered test passed\n");
    }

    private static void copyVecSubset(String srcPath, String dstPath, int count, int dim) throws Exception {
        VecFileIO.VecFileMeta meta = VecFileIO.readMetadata(srcPath);
        int vectorBytes = dim * Float.BYTES;
        
        Path src = Paths.get(srcPath);
        Path dst = Paths.get(dstPath);
        
        try (var srcDir = org.apache.lucene.store.FSDirectory.open(src.getParent());
             var dstDir = org.apache.lucene.store.FSDirectory.open(dst.getParent());
             var srcIn = srcDir.openInput(src.getFileName().toString(), org.apache.lucene.store.IOContext.DEFAULT);
             var dstOut = dstDir.createOutput(dst.getFileName().toString(), org.apache.lucene.store.IOContext.DEFAULT)) {
            
            // Copy header
            byte[] header = new byte[(int) meta.dataOffset()];
            srcIn.readBytes(header, 0, header.length);
            dstOut.writeBytes(header, header.length);
            
            // Copy subset of vectors
            var slice = srcIn.slice("vectors", meta.dataOffset(), meta.dataLength());
            byte[] buffer = new byte[vectorBytes];
            for (int i = 0; i < count; i++) {
                slice.readBytes(buffer, 0, vectorBytes);
                dstOut.writeBytes(buffer, vectorBytes);
            }
            
            // Copy footer
            srcIn.seek(meta.dataOffset() + meta.dataLength());
            byte[] footer = new byte[(int) (srcIn.length() - srcIn.getFilePointer())];
            srcIn.readBytes(footer, 0, footer.length);
            dstOut.writeBytes(footer, footer.length);
        }
    }
}
