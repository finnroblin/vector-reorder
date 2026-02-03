/*
 * Forces NativeEngines990KnnVectorsFormat for all vector fields.
 */
package org.opensearch.knn.reorder;

import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.opensearch.knn.index.codec.KNN990Codec.NativeEngines990KnnVectorsFormat;

/**
 * PerFieldKnnVectorsFormat that always uses NativeEngines990KnnVectorsFormat.
 */
public class ForceNativeEnginesFormat extends PerFieldKnnVectorsFormat {
    
    private final KnnVectorsFormat format = new NativeEngines990KnnVectorsFormat();
    
    @Override
    public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
        return format;
    }
}
