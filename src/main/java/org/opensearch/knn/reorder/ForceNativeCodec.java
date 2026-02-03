/*
 * Codec that forces NativeEngines990KnnVectorsFormat for all vector fields.
 */
package org.opensearch.knn.reorder;

import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene103.Lucene103Codec;

public class ForceNativeCodec extends FilterCodec {
    
    private final KnnVectorsFormat knnFormat = new ForceNativeEnginesFormat();
    
    public ForceNativeCodec() {
        super("ForceNativeCodec", new Lucene103Codec());
    }
    
    @Override
    public KnnVectorsFormat knnVectorsFormat() {
        return knnFormat;
    }
}
