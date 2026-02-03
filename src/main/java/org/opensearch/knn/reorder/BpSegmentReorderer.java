/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnFieldVectorsWriter;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.lucene103.Lucene103Codec;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.CodecReader;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.Sorter;
import org.apache.lucene.index.SortingCodecReader;
import org.apache.lucene.misc.index.BpVectorReorderer;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Full BP reordering: reorders entire Lucene segment + writes k-NN files with matching segment ID.
 */
public class BpSegmentReorderer {

    private final String vectorField;
    private final BpVectorReorderer reorderer;

    private int hnswM = 16;
    private int efConstruction = 100;
    private int efSearch = 100;
    private QuantizationStateIO.OneBitState quantizationState;

    public BpSegmentReorderer(String vectorField) {
        this.vectorField = vectorField;
        this.reorderer = new BpVectorReorderer(vectorField);
    }

    public BpSegmentReorderer setQuantizationState(QuantizationStateIO.OneBitState state) { 
        this.quantizationState = state; 
        return this; 
    }

    public void reorder(Path srcPath, Path dstPath, int threadCount, String faissOutputPath) throws IOException {
        Executor executor = threadCount > 1 ? new ForkJoinPool(threadCount) : null;
        
        try (Directory srcDir = FSDirectory.open(srcPath);
             Directory dstDir = FSDirectory.open(dstPath);
             IndexReader reader = DirectoryReader.open(srcDir)) {
            
            // Get commit user data from source (contains translog_uuid, etc.)
            Map<String, String> commitUserData = new HashMap<>(((DirectoryReader) reader).getIndexCommit().getUserData());
            
            // Use codec that skips vectors (we'll write them ourselves)
            IndexWriterConfig iwc = new IndexWriterConfig();
            iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
            iwc.setCodec(new NoVectorsCodec());
            
            float[][] vectors = null;
            int[] newOrder = null;
            FieldInfo fieldInfo = null;
            
            try (IndexWriter writer = new IndexWriter(dstDir, iwc)) {
                // Set commit user data to preserve translog UUID
                writer.setLiveCommitData(commitUserData.entrySet());
                
                for (LeafReaderContext ctx : reader.leaves()) {
                    CodecReader codecReader = (CodecReader) ctx.reader();
                    Sorter.DocMap docMap = reorderer.computeDocMap(codecReader, null, executor);
                    
                    if (docMap != null) {
                        // Read vectors and field info before reordering
                        vectors = readVectors(codecReader);
                        newOrder = extractNewOrder(docMap, vectors.length);
                        fieldInfo = codecReader.getFieldInfos().fieldInfo(vectorField);
                        
                        writer.addIndexes(SortingCodecReader.wrap(codecReader, docMap, null));
                    } else {
                        writer.addIndexes(codecReader);
                    }
                }
            }
            
            if (vectors == null || newOrder == null || fieldInfo == null) {
                System.out.println("No reordering needed");
                return;
            }
            
            // Read new segment info
            SegmentInfos segmentInfos = SegmentInfos.readLatestCommit(dstDir);
            SegmentCommitInfo segInfo = segmentInfos.asList().get(0);
            byte[] segmentId = segInfo.info.getId();
            String segmentName = segInfo.info.name;
            
            System.out.println("New segment: " + segmentName + " (id=" + bytesToHex(segmentId) + ")");
            
            // Write k-NN files with new segment ID
            String knnSuffix = "NativeEngines990KnnVectorsFormat_0";
            String vecFile = dstPath.resolve(segmentName + "_" + knnSuffix + ".vec").toString();
            String vemfFile = dstPath.resolve(segmentName + "_" + knnSuffix + ".vemf").toString();
            String faissFile = dstPath.resolve(segmentName + "_" + fieldInfo.number + "_" + vectorField + ".faiss").toString();
            
            KnnFileWriter.writeVecFile(vectors, newOrder, segmentId, knnSuffix, vecFile);
            KnnFileWriter.writeVemfFile(vectors.length, vectors[0].length, fieldInfo.number,
                                        fieldInfo.getVectorEncoding().ordinal(),
                                        fieldInfo.getVectorSimilarityFunction().ordinal(),
                                        segmentId, knnSuffix, vemfFile);
            
            // Build FAISS index with proper naming
            if (faissOutputPath != null) {
                buildFaissIndex(vectors, newOrder, faissFile);
                System.out.println("FAISS index: " + faissFile);
            }
        }
    }

    private float[][] readVectors(CodecReader reader) throws IOException {
        FloatVectorValues vectorValues = reader.getFloatVectorValues(vectorField);
        if (vectorValues == null) return null;

        int n = vectorValues.size();
        float[][] vectors = new float[n][];
        var iterator = vectorValues.iterator();
        int ord = 0;
        while (iterator.nextDoc() != org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS) {
            vectors[ord] = vectorValues.vectorValue(ord).clone();
            ord++;
        }
        return vectors;
    }

    private int[] extractNewOrder(Sorter.DocMap docMap, int n) {
        int[] newOrder = new int[n];
        for (int newOrd = 0; newOrd < n; newOrd++) {
            newOrder[newOrd] = docMap.newToOld(newOrd);
        }
        return newOrder;
    }

    private void buildFaissIndex(float[][] vectors, int[] newOrder, String outputPath) throws IOException {
        int n = vectors.length;
        long[] idMapping = new long[n];
        for (int i = 0; i < n; i++) idMapping[i] = i;
        
        if (quantizationState != null) {
            BinaryFaissIndexRebuilder.rebuild(vectors, newOrder, idMapping, quantizationState,
                                              outputPath, hnswM, efConstruction, efSearch);
        } else {
            FaissIndexRebuilder.rebuild(vectors, newOrder, vectors[0].length, outputPath, 
                                        hnswM, efConstruction, efSearch, FaissIndexRebuilder.SPACE_L2);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Codec that discards vectors (we write them separately) */
    static class NoVectorsCodec extends FilterCodec {
        NoVectorsCodec() { super("Lucene103", new Lucene103Codec()); }
        @Override public KnnVectorsFormat knnVectorsFormat() { return new DiscardVectorsFormat(); }
    }
    
    /** Format that accepts but discards all vector writes */
    static class DiscardVectorsFormat extends KnnVectorsFormat {
        DiscardVectorsFormat() { super("Discard"); }
        
        @Override public int getMaxDimensions(String fieldName) { return 4096; }
        
        @Override
        public KnnVectorsWriter fieldsWriter(SegmentWriteState state) {
            return new KnnVectorsWriter() {
                @Override public KnnFieldVectorsWriter<?> addField(FieldInfo fieldInfo) { return null; }
                @Override public void mergeOneField(FieldInfo fieldInfo, MergeState mergeState) {}
                @Override public void flush(int maxDoc, Sorter.DocMap sortMap) {}
                @Override public void finish() {}
                @Override public void close() {}
                @Override public long ramBytesUsed() { return 0; }
            };
        }
        
        @Override
        public KnnVectorsReader fieldsReader(SegmentReadState state) {
            throw new UnsupportedOperationException();
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.out.println("Usage: BpSegmentReorderer <index-path> <vector-field> <output-path> [--faiss <path>] [--qstate <path>]");
            return;
        }

        String indexPath = args[0];
        String vectorField = args[1];
        String outputPath = args[2];
        String faissPath = null;
        String qstatePath = null;
        int threads = Runtime.getRuntime().availableProcessors();

        for (int i = 3; i < args.length; i++) {
            if ("--faiss".equals(args[i]) && i + 1 < args.length) faissPath = args[++i];
            else if ("--qstate".equals(args[i]) && i + 1 < args.length) qstatePath = args[++i];
            else if ("--threads".equals(args[i]) && i + 1 < args.length) threads = Integer.parseInt(args[++i]);
        }

        BpSegmentReorderer reorderer = new BpSegmentReorderer(vectorField);
        
        if (qstatePath != null) {
            System.out.println("Loading quantization state: " + qstatePath);
            Path qpath = Path.of(qstatePath);
            String fileName = qpath.getFileName().toString();
            String baseName = fileName.replace(".osknnqstate", "");
            int lastUnderscore = baseName.lastIndexOf('_');
            String segmentName = baseName.substring(0, lastUnderscore);
            String segmentSuffix = baseName.substring(lastUnderscore + 1);
            
            String vemfName = segmentName + "_" + segmentSuffix + ".vemf";
            Path vemfPath = qpath.getParent().resolve(vemfName);
            VemfFileIO.VemfMeta vemfMeta = VemfFileIO.readMetadata(vemfPath.toString());
            
            try (FSDirectory dir = FSDirectory.open(qpath.getParent())) {
                reorderer.setQuantizationState(QuantizationStateIO.readOneBitState(
                    dir, segmentName, segmentSuffix, vemfMeta.fieldNumber()));
            }
        }
        
        System.out.println("Reordering " + indexPath + " -> " + outputPath);
        if (faissPath != null) System.out.println("Building FAISS: " + faissPath);
        
        reorderer.reorder(Path.of(indexPath), Path.of(outputPath), threads, faissPath);
        System.out.println("Done.");
    }
}
