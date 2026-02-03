/*
 * Codec that uses NativeEngines990KnnVectorsFormat for vectors only.
 * This produces .vec files compatible with k-NN without requiring OpenSearch runtime.
 */
package org.opensearch.knn.reorder;

import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene103.Lucene103Codec;
import org.apache.lucene.codecs.lucene99.Lucene99FlatVectorsFormat;
import org.apache.lucene.codecs.hnsw.DefaultFlatVectorScorer;

/**
 * Codec that writes vectors using NativeEngines990-compatible flat format.
 * Does NOT build FAISS index (that's done separately via JNI).
 */
public class NativeEnginesOnlyCodec extends FilterCodec {
    
    private final KnnVectorsFormat knnVectorsFormat;
    
    public NativeEnginesOnlyCodec() {
        super("NativeEnginesOnlyCodec", new Lucene103Codec());
        // Use the same flat format as NativeEngines990KnnVectorsFormat
        this.knnVectorsFormat = new FlatOnlyVectorsFormat();
    }
    
    @Override
    public KnnVectorsFormat knnVectorsFormat() {
        return knnVectorsFormat;
    }
    
    /**
     * Vectors format that only writes flat vectors (no HNSW graph).
     * The FAISS index is built separately.
     */
    private static class FlatOnlyVectorsFormat extends KnnVectorsFormat {
        private final Lucene99FlatVectorsFormat flatFormat;
        
        FlatOnlyVectorsFormat() {
            super("NativeEngines990KnnVectorsFormat");
            this.flatFormat = new Lucene99FlatVectorsFormat(new DefaultFlatVectorScorer());
        }
        
        @Override
        public org.apache.lucene.codecs.KnnVectorsWriter fieldsWriter(
                org.apache.lucene.index.SegmentWriteState state) throws java.io.IOException {
            return flatFormat.fieldsWriter(state);
        }
        
        @Override
        public org.apache.lucene.codecs.KnnVectorsReader fieldsReader(
                org.apache.lucene.index.SegmentReadState state) throws java.io.IOException {
            return flatFormat.fieldsReader(state);
        }
        
        @Override
        public int getMaxDimensions(String fieldName) {
            return 16000; // Same as k-NN FAISS max
        }
    }
}
