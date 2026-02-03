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

/**
 * Utility class for reading and writing Lucene99FlatVectorsFormat .vec files.
 */
public class VecFileIO {

    private static final String META_CODEC_NAME = "Lucene99FlatVectorsFormatMeta";

    public record VecFileMeta(int dimension, int size, long dataOffset, long dataLength) {}

    /**
     * Read metadata from .vemf file.
     */
    public static VecFileMeta readMetadata(String vecPath) throws IOException {
        Path path = Paths.get(vecPath);
        String metaFileName = path.getFileName().toString().replace(".vec", ".vemf");
        
        try (FSDirectory directory = FSDirectory.open(path.getParent());
             IndexInput meta = directory.openInput(metaFileName, IOContext.DEFAULT)) {
            
            int headerLength = CodecUtil.headerLength(META_CODEC_NAME) + 16 + 1 + "NativeEngines990KnnVectorsFormat_0".length();
            meta.seek(headerLength);
            
            meta.readInt(); // fieldNumber
            meta.readInt(); // vectorEncoding
            meta.readInt(); // similarityFunction
            long dataOffset = meta.readVLong();
            long dataLength = meta.readVLong();
            int dimension = meta.readVInt();
            int size = meta.readInt();
            
            return new VecFileMeta(dimension, size, dataOffset, dataLength);
        }
    }

    /**
     * Load all vectors from a .vec file into memory.
     */
    public static float[][] loadVectors(String vecPath) throws IOException {
        Path path = Paths.get(vecPath);
        VecFileMeta meta = readMetadata(vecPath);
        
        float[][] vectors = new float[meta.size][meta.dimension];
        try (FSDirectory directory = FSDirectory.open(path.getParent());
             IndexInput vecInput = directory.openInput(path.getFileName().toString(), IOContext.DEFAULT)) {
            
            IndexInput slice = vecInput.slice("vectors", meta.dataOffset, meta.dataLength);
            for (int i = 0; i < meta.size; i++) {
                slice.readFloats(vectors[i], 0, meta.dimension);
            }
        }
        return vectors;
    }

    /**
     * Write vectors to a new .vec file in reordered order.
     * newOrder[newIdx] = oldIdx means vector at oldIdx goes to position newIdx.
     */
    public static void writeReordered(String srcPath, String dstPath, int[] newOrder) throws IOException {
        Path src = Paths.get(srcPath);
        Path dst = Paths.get(dstPath);
        VecFileMeta meta = readMetadata(srcPath);
        int vectorBytes = meta.dimension * Float.BYTES;

        try (FSDirectory srcDir = FSDirectory.open(src.getParent());
             FSDirectory dstDir = FSDirectory.open(dst.getParent());
             IndexInput vecInput = srcDir.openInput(src.getFileName().toString(), IOContext.DEFAULT);
             IndexOutput out = dstDir.createOutput(dst.getFileName().toString(), IOContext.DEFAULT)) {

            // Copy header unchanged
            byte[] header = new byte[(int) meta.dataOffset];
            vecInput.readBytes(header, 0, header.length);
            out.writeBytes(header, header.length);

            // Write vectors in new order
            IndexInput slice = vecInput.slice("vectors", meta.dataOffset, meta.dataLength);
            byte[] buffer = new byte[vectorBytes];
            
            for (int newIdx = 0; newIdx < meta.size; newIdx++) {
                int oldIdx = newOrder[newIdx];
                slice.seek((long) oldIdx * vectorBytes);
                slice.readBytes(buffer, 0, vectorBytes);
                out.writeBytes(buffer, vectorBytes);
            }

            // Copy footer unchanged
            vecInput.seek(meta.dataOffset + meta.dataLength);
            byte[] footer = new byte[(int) (vecInput.length() - vecInput.getFilePointer())];
            vecInput.readBytes(footer, 0, footer.length);
            out.writeBytes(footer, footer.length);
        }
    }

    /**
     * Write vectors from array to a new .vec file in reordered order.
     */
    public static void writeReorderedFromArray(float[][] vectors, int[] newOrder, String dstPath) throws IOException {
        Path dst = Paths.get(dstPath);
        int n = vectors.length;
        int dim = vectors[0].length;

        try (FSDirectory dstDir = FSDirectory.open(dst.getParent());
             IndexOutput out = dstDir.createOutput(dst.getFileName().toString(), IOContext.DEFAULT)) {

            // Write minimal header (just enough for k-NN to read)
            // Format: [header][vectors][footer]
            // We write a simple format that VecFileIO can read back
            CodecUtil.writeIndexHeader(out, "Lucene99FlatVectorsFormatData", 0, new byte[16], "");
            
            long dataOffset = out.getFilePointer();
            
            // Write vectors in new order
            for (int newIdx = 0; newIdx < n; newIdx++) {
                int oldIdx = newOrder[newIdx];
                float[] vec = vectors[oldIdx];
                for (float v : vec) {
                    out.writeInt(Float.floatToIntBits(v));
                }
            }
            
            CodecUtil.writeFooter(out);
        }
    }
}
