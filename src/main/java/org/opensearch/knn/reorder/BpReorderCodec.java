/*
 * Minimal codec for BP reordering that writes native FAISS indexes.
 */
package org.opensearch.knn.reorder;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene103.Lucene103Codec;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;

/**
 * Codec that uses standard Lucene for everything except vectors,
 * which use NativeEngines990KnnVectorsFormat to build FAISS indexes.
 */
public class BpReorderCodec extends FilterCodec {
    
    private final KnnVectorsFormat knnVectorsFormat;
    
    public BpReorderCodec() {
        super("BpReorderCodec", new Lucene103Codec());
        // For now, just use standard Lucene vectors format
        // TODO: integrate NativeEngines990KnnVectorsFormat
        this.knnVectorsFormat = new Lucene103Codec().knnVectorsFormat();
    }
    
    @Override
    public KnnVectorsFormat knnVectorsFormat() {
        return knnVectorsFormat;
    }
}
