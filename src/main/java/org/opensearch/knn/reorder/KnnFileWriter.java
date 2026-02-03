/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexOutput;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Writes k-NN compatible .vec and .vemf files with specified segment ID.
 */
public class KnnFileWriter {

    private static final String VEC_CODEC = "Lucene99FlatVectorsFormatData";
    private static final String VEMF_CODEC = "Lucene99FlatVectorsFormatMeta";

    /**
     * Write .vec file with vectors in reordered order.
     */
    public static void writeVecFile(float[][] vectors, int[] newOrder, byte[] segmentId, 
                                    String segmentSuffix, String outputPath) throws IOException {
        Path path = Paths.get(outputPath);
        int n = vectors.length;
        int dim = vectors[0].length;
        
        try (FSDirectory dir = FSDirectory.open(path.getParent());
             IndexOutput out = dir.createOutput(path.getFileName().toString(), IOContext.DEFAULT)) {
            
            CodecUtil.writeIndexHeader(out, VEC_CODEC, 0, segmentId, segmentSuffix);
            
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

    /**
     * Write .vemf metadata file (dense format).
     */
    public static void writeVemfFile(int vectorCount, int dimension, int fieldNumber,
                                     int vectorEncoding, int similarityFunction,
                                     byte[] segmentId, String segmentSuffix, String outputPath) throws IOException {
        Path path = Paths.get(outputPath);
        
        try (FSDirectory dir = FSDirectory.open(path.getParent());
             IndexOutput out = dir.createOutput(path.getFileName().toString(), IOContext.DEFAULT)) {
            
            CodecUtil.writeIndexHeader(out, VEMF_CODEC, 0, segmentId, segmentSuffix);
            
            // Field info
            out.writeInt(fieldNumber);
            out.writeInt(vectorEncoding);
            out.writeInt(similarityFunction);
            
            // Vector data location - header size
            int headerSize = CodecUtil.headerLength(VEC_CODEC) + segmentSuffix.length();
            out.writeVLong(headerSize);  // vectorDataOffset
            out.writeVLong((long) vectorCount * dimension * Float.BYTES);  // vectorDataLength
            out.writeVInt(dimension);
            out.writeInt(vectorCount);
            
            // Dense format markers
            out.writeLong(-1L);  // docsWithFieldOffset = -1 means dense
            out.writeLong(0L);   // docsWithFieldLength
            out.writeShort((short) -1);  // jumpTableEntryCount
            out.writeByte((byte) -1);    // denseRankPower
            
            out.writeInt(-1);  // end marker
            
            CodecUtil.writeFooter(out);
        }
    }
}
