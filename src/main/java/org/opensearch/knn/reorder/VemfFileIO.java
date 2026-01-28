/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.lucene90.IndexedDISI;
import org.apache.lucene.index.DocsWithFieldSet;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.packed.DirectMonotonicWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class for reading and writing .vemf metadata files.
 * 
 * The .vemf file contains metadata about vectors including the ordToDoc mapping.
 * After BP reordering, we need to update this mapping so that:
 * - newOrdToDoc[newOrd] = oldDocId where oldDocId = oldOrd (in dense case)
 * 
 * This converts a dense mapping (ordinal == docId) to a sparse mapping
 * with explicit ordToDoc entries.
 */
public class VemfFileIO {

    private static final String META_CODEC_NAME = "Lucene99FlatVectorsFormatMeta";
    private static final String VECTOR_DATA_CODEC_NAME = "Lucene99FlatVectorsFormatData";
    private static final int DIRECT_MONOTONIC_BLOCK_SHIFT = 16;

    /**
     * Metadata parsed from .vemf file header.
     */
    public record VemfMeta(
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

    /**
     * Read metadata from .vemf file.
     */
    public static VemfMeta readMetadata(String vemfPath) throws IOException {
        Path path = Paths.get(vemfPath);
        try (FSDirectory directory = FSDirectory.open(path.getParent());
             IndexInput input = directory.openInput(path.getFileName().toString(), IOContext.DEFAULT)) {
            
            // Skip codec header
            CodecUtil.checkIndexHeader(input, META_CODEC_NAME, 0, 0, new byte[16], "");
            
            // Read field info
            int fieldNumber = input.readInt();
            int vectorEncoding = input.readInt();
            int similarityFunction = input.readInt();
            long vectorDataOffset = input.readVLong();
            long vectorDataLength = input.readVLong();
            int dimension = input.readVInt();
            int size = input.readInt();
            
            // Read ordToDoc configuration
            long docsWithFieldOffset = input.readLong();
            long docsWithFieldLength = input.readLong();
            short jumpTableEntryCount = input.readShort();
            byte denseRankPower = input.readByte();
            
            boolean isEmpty = docsWithFieldOffset == -2;
            boolean isDense = docsWithFieldOffset == -1;
            
            return new VemfMeta(
                fieldNumber, vectorEncoding, similarityFunction,
                vectorDataOffset, vectorDataLength, dimension, size,
                docsWithFieldOffset, docsWithFieldLength,
                jumpTableEntryCount, denseRankPower,
                isDense, isEmpty
            );
        }
    }

    /**
     * Rewrite .vemf and append ordToDoc mapping to .vec file after BP reordering.
     * 
     * For dense case (all docs have vectors), after reordering:
     * - Vector at new ordinal i came from old ordinal newOrder[i]
     * - Old ordinal == old docId (dense case)
     * - So newOrdToDoc[i] = newOrder[i]
     * 
     * @param srcVemfPath source .vemf file path
     * @param srcVecPath source .vec file path  
     * @param dstVemfPath destination .vemf file path
     * @param dstVecPath destination .vec file path (ordToDoc appended here)
     * @param newOrder permutation array: newOrder[newIdx] = oldIdx
     */
    public static void writeReordered(
        String srcVemfPath,
        String srcVecPath,
        String dstVemfPath,
        String dstVecPath,
        int[] newOrder
    ) throws IOException {
        VemfMeta srcMeta = readMetadata(srcVemfPath);
        
        if (srcMeta.isEmpty()) {
            throw new IllegalArgumentException("Cannot reorder empty vector field");
        }
        
        Path srcVemf = Paths.get(srcVemfPath);
        Path dstVemf = Paths.get(dstVemfPath);
        Path dstVec = Paths.get(dstVecPath);
        
        // Build the new ordToDoc mapping
        // newOrdToDoc[newOrd] = docId that the vector at newOrd belongs to
        // In dense case: oldOrd == oldDocId, so newOrdToDoc[newOrd] = newOrder[newOrd]
        int[] newOrdToDoc = new int[srcMeta.size()];
        for (int newOrd = 0; newOrd < srcMeta.size(); newOrd++) {
            int oldOrd = newOrder[newOrd];
            // In dense case, oldOrd == oldDocId
            // In sparse case, we'd need to read the original ordToDoc mapping
            newOrdToDoc[newOrd] = oldOrd;
        }
        
        // Sort to get docIds in ascending order for DocsWithFieldSet
        // We need to track which newOrd maps to which docId
        int[] sortedDocIds = newOrdToDoc.clone();
        java.util.Arrays.sort(sortedDocIds);
        
        try (FSDirectory dstVemfDir = FSDirectory.open(dstVemf.getParent());
             FSDirectory dstVecDir = FSDirectory.open(dstVec.getParent());
             IndexOutput metaOut = dstVemfDir.createOutput(dstVemf.getFileName().toString(), IOContext.DEFAULT);
             IndexOutput vecOut = dstVecDir.openChecksumOutput(dstVec.getFileName().toString())) {
            
            // First, copy the vector data from source (already reordered by VecFileIO)
            // We need to append the ordToDoc mapping after the vector data
            Path srcVec = Paths.get(srcVecPath);
            try (FSDirectory srcVecDir = FSDirectory.open(srcVec.getParent());
                 IndexInput srcVecIn = srcVecDir.openInput(srcVec.getFileName().toString(), IOContext.DEFAULT)) {
                // Copy everything from source vec file
                vecOut.copyBytes(srcVecIn, srcVecIn.length());
            }
            
            // Write .vemf header
            CodecUtil.writeIndexHeader(metaOut, META_CODEC_NAME, 0, new byte[16], "");
            
            // Write field metadata (same as source)
            metaOut.writeInt(srcMeta.fieldNumber());
            metaOut.writeInt(srcMeta.vectorEncoding());
            metaOut.writeInt(srcMeta.similarityFunction());
            metaOut.writeVLong(srcMeta.vectorDataOffset());
            metaOut.writeVLong(srcMeta.vectorDataLength());
            metaOut.writeVInt(srcMeta.dimension());
            metaOut.writeInt(srcMeta.size());
            
            // Now write the sparse ordToDoc configuration
            // Even if original was dense, after reordering we need sparse mapping
            writeOrdToDocMapping(metaOut, vecOut, newOrdToDoc, srcMeta.size());
            
            // Write end marker and footer
            metaOut.writeInt(-1);
            CodecUtil.writeFooter(metaOut);
            CodecUtil.writeFooter(vecOut);
        }
    }

    /**
     * Write ordToDoc mapping in sparse format.
     */
    private static void writeOrdToDocMapping(
        IndexOutput metaOut,
        IndexOutput vecOut,
        int[] ordToDoc,
        int count
    ) throws IOException {
        // Build DocsWithFieldSet from ordToDoc
        DocsWithFieldSet docsWithField = new DocsWithFieldSet();
        
        // We need docIds in sorted order for DocsWithFieldSet
        // Create pairs of (docId, ord) and sort by docId
        int[][] pairs = new int[count][2];
        for (int ord = 0; ord < count; ord++) {
            pairs[ord][0] = ordToDoc[ord]; // docId
            pairs[ord][1] = ord;
        }
        java.util.Arrays.sort(pairs, (a, b) -> Integer.compare(a[0], b[0]));
        
        // Add docIds in sorted order
        for (int i = 0; i < count; i++) {
            docsWithField.add(pairs[i][0]);
        }
        
        // Write docsWithField (IndexedDISI format)
        long docsWithFieldOffset = vecOut.getFilePointer();
        short jumpTableEntryCount = IndexedDISI.writeBitSet(
            docsWithField.iterator(), vecOut, IndexedDISI.DEFAULT_DENSE_RANK_POWER);
        long docsWithFieldLength = vecOut.getFilePointer() - docsWithFieldOffset;
        
        // Write ordToDoc mapping using DirectMonotonicWriter
        long ordToDocOffset = vecOut.getFilePointer();
        DirectMonotonicWriter ordToDocWriter = DirectMonotonicWriter.getInstance(
            metaOut, vecOut, count, DIRECT_MONOTONIC_BLOCK_SHIFT);
        
        // Write docIds in ordinal order (not sorted order)
        for (int ord = 0; ord < count; ord++) {
            ordToDocWriter.add(ordToDoc[ord]);
        }
        ordToDocWriter.finish();
        long ordToDocLength = vecOut.getFilePointer() - ordToDocOffset;
        
        // Write metadata
        metaOut.writeLong(docsWithFieldOffset);
        metaOut.writeLong(docsWithFieldLength);
        metaOut.writeShort(jumpTableEntryCount);
        metaOut.writeByte(IndexedDISI.DEFAULT_DENSE_RANK_POWER);
        metaOut.writeLong(ordToDocOffset);
        metaOut.writeVInt(DIRECT_MONOTONIC_BLOCK_SHIFT);
        metaOut.writeLong(ordToDocLength);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: VemfFileIO <vemf-path>");
            System.out.println("  Dumps .vemf metadata");
            return;
        }
        
        VemfMeta meta = readMetadata(args[0]);
        System.out.println("VemfMeta:");
        System.out.println("  fieldNumber: " + meta.fieldNumber());
        System.out.println("  vectorEncoding: " + meta.vectorEncoding());
        System.out.println("  similarityFunction: " + meta.similarityFunction());
        System.out.println("  vectorDataOffset: " + meta.vectorDataOffset());
        System.out.println("  vectorDataLength: " + meta.vectorDataLength());
        System.out.println("  dimension: " + meta.dimension());
        System.out.println("  size: " + meta.size());
        System.out.println("  docsWithFieldOffset: " + meta.docsWithFieldOffset());
        System.out.println("  isDense: " + meta.isDense());
        System.out.println("  isEmpty: " + meta.isEmpty());
    }
}
