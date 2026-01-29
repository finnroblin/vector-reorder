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
 * Handles .vemf file reading and rewriting after BP reordering.
 * 
 * PROBLEM: After BP reorder, ordToDoc[ord] = docId is NOT monotonic.
 * Lucene's DirectMonotonicWriter requires monotonic values.
 * 
 * SOLUTION: Write .vemf in dense format (ordToDoc is identity).
 * Store the actual docToOrd mapping in a separate .vord file.
 * 
 * .vord format:
 *   - Header (CodecUtil)
 *   - int count
 *   - int[count] docToOrd mapping
 *   - Footer (CodecUtil)
 * 
 * To look up vector for docId: ord = docToOrd[docId], then read vector at ord.
 */
public class VemfFileIO {

    private static final String META_CODEC_NAME = "Lucene99FlatVectorsFormatMeta";
    private static final String VORD_CODEC_NAME = "OpenSearchVectorOrdMapping";
    private static final int CODEC_MAGIC = 0x3fd76c17;

    public record VemfMeta(
        byte[] segmentId,
        String segmentSuffix,
        int fieldNumber,
        int vectorEncoding,
        int similarityFunction,
        long vectorDataOffset,
        long vectorDataLength,
        int dimension,
        int size,
        long docsWithFieldOffset,
        long docsWithFieldLength,
        short jumpTableEntryCount,
        byte denseRankPower,
        boolean isDense,
        boolean isEmpty
    ) {}

    private static int readBEInt(IndexInput in) throws IOException {
        return ((in.readByte() & 0xFF) << 24)
            | ((in.readByte() & 0xFF) << 16)
            | ((in.readByte() & 0xFF) << 8)
            | (in.readByte() & 0xFF);
    }

    public static VemfMeta readMetadata(String vemfPath) throws IOException {
        Path path = Paths.get(vemfPath);
        try (FSDirectory directory = FSDirectory.open(path.getParent());
             IndexInput input = directory.openInput(path.getFileName().toString(), IOContext.DEFAULT)) {
            
            int magic = readBEInt(input);
            if (magic != CODEC_MAGIC) {
                throw new IOException("Invalid codec magic: " + Integer.toHexString(magic));
            }
            
            String codecName = input.readString();
            if (!META_CODEC_NAME.equals(codecName)) {
                throw new IOException("Expected codec " + META_CODEC_NAME + ", got " + codecName);
            }
            
            int version = readBEInt(input);
            byte[] segmentId = new byte[16];
            input.readBytes(segmentId, 0, 16);
            
            int suffixLen = input.readByte() & 0xFF;
            byte[] suffixBytes = new byte[suffixLen];
            input.readBytes(suffixBytes, 0, suffixLen);
            String segmentSuffix = new String(suffixBytes);
            
            int fieldNumber = input.readInt();
            int vectorEncoding = input.readInt();
            int similarityFunction = input.readInt();
            long vectorDataOffset = input.readVLong();
            long vectorDataLength = input.readVLong();
            int dimension = input.readVInt();
            int size = input.readInt();
            
            long docsWithFieldOffset = input.readLong();
            long docsWithFieldLength = input.readLong();
            short jumpTableEntryCount = input.readShort();
            byte denseRankPower = input.readByte();
            
            boolean isEmpty = docsWithFieldOffset == -2;
            boolean isDense = docsWithFieldOffset == -1;
            
            return new VemfMeta(
                segmentId, segmentSuffix,
                fieldNumber, vectorEncoding, similarityFunction,
                vectorDataOffset, vectorDataLength, dimension, size,
                docsWithFieldOffset, docsWithFieldLength,
                jumpTableEntryCount, denseRankPower,
                isDense, isEmpty
            );
        }
    }

    /**
     * Rewrite .vemf and create .vord after BP reordering.
     * 
     * .vemf is written in dense format (ord == docId assumption).
     * .vord contains the actual docToOrd mapping for correct lookups.
     */
    public static void writeReordered(
        String srcVemfPath,
        String dstVemfPath,
        String dstVecPath,
        int[] newOrder
    ) throws IOException {
        VemfMeta srcMeta = readMetadata(srcVemfPath);
        
        if (srcMeta.isEmpty()) {
            throw new IllegalArgumentException("Cannot reorder empty vector field");
        }
        
        Path dstVemf = Paths.get(dstVemfPath);
        String vordPath = dstVemfPath.replace(".vemf", ".vord");
        Path dstVord = Paths.get(vordPath);
        
        // Compute docToOrd mapping
        // After reorder: vector at newOrd came from oldOrd = newOrder[newOrd]
        // In dense case: oldOrd == docId
        // So: ordToDoc[newOrd] = newOrder[newOrd]
        // Inverse: docToOrd[docId] = newOrd where newOrder[newOrd] == docId
        int count = newOrder.length;
        int[] docToOrd = new int[count];
        for (int newOrd = 0; newOrd < count; newOrd++) {
            int docId = newOrder[newOrd];
            docToOrd[docId] = newOrd;
        }
        
        try (FSDirectory dir = FSDirectory.open(dstVemf.getParent())) {
            // Write .vemf in dense format
            try (IndexOutput metaOut = dir.createOutput(dstVemf.getFileName().toString(), IOContext.DEFAULT)) {
                CodecUtil.writeIndexHeader(metaOut, META_CODEC_NAME, 0, srcMeta.segmentId(), srcMeta.segmentSuffix());
                
                metaOut.writeInt(srcMeta.fieldNumber());
                metaOut.writeInt(srcMeta.vectorEncoding());
                metaOut.writeInt(srcMeta.similarityFunction());
                metaOut.writeVLong(srcMeta.vectorDataOffset());
                // Recalculate data length based on count
                long newDataLength = (long) count * srcMeta.dimension() * Float.BYTES;
                metaOut.writeVLong(newDataLength);
                metaOut.writeVInt(srcMeta.dimension());
                metaOut.writeInt(count);
                
                // Dense format
                metaOut.writeLong(-1L);  // docsWithFieldOffset = -1 means dense
                metaOut.writeLong(0L);
                metaOut.writeShort((short) -1);
                metaOut.writeByte((byte) -1);
                
                metaOut.writeInt(-1);  // end marker
                CodecUtil.writeFooter(metaOut);
            }
            
            // Write .vord with docToOrd mapping
            try (IndexOutput vordOut = dir.createOutput(dstVord.getFileName().toString(), IOContext.DEFAULT)) {
                CodecUtil.writeIndexHeader(vordOut, VORD_CODEC_NAME, 0, srcMeta.segmentId(), srcMeta.segmentSuffix());
                
                vordOut.writeInt(docToOrd.length);
                for (int ord : docToOrd) {
                    vordOut.writeInt(ord);
                }
                
                CodecUtil.writeFooter(vordOut);
            }
        }
        
        System.out.println("Wrote .vemf (dense format) and .vord (docToOrd mapping)");
        System.out.println("  .vord: " + vordPath);
    }

    /**
     * Read docToOrd mapping from .vord file.
     */
    public static int[] readDocToOrd(String vordPath) throws IOException {
        Path path = Paths.get(vordPath);
        try (FSDirectory dir = FSDirectory.open(path.getParent());
             IndexInput input = dir.openInput(path.getFileName().toString(), IOContext.DEFAULT)) {
            
            // Skip header manually (magic + codec name + version + segmentId + suffix)
            int magic = readBEInt(input);
            if (magic != CODEC_MAGIC) {
                throw new IOException("Invalid magic in .vord");
            }
            String codec = input.readString();
            if (!VORD_CODEC_NAME.equals(codec)) {
                throw new IOException("Expected codec " + VORD_CODEC_NAME);
            }
            readBEInt(input); // version
            input.skipBytes(16); // segmentId
            int suffixLen = input.readByte() & 0xFF;
            input.skipBytes(suffixLen);
            
            int count = input.readInt();
            int[] docToOrd = new int[count];
            for (int i = 0; i < count; i++) {
                docToOrd[i] = input.readInt();
            }
            
            return docToOrd;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: VemfFileIO <vemf-path>");
            return;
        }
        
        VemfMeta meta = readMetadata(args[0]);
        System.out.println("VemfMeta:");
        System.out.println("  dimension: " + meta.dimension());
        System.out.println("  size: " + meta.size());
        System.out.println("  isDense: " + meta.isDense());
    }
}
