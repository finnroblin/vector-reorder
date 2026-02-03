/*
 * BP reorder using SortingCodecReader to rewrite entire segment.
 */
package org.opensearch.knn.reorder;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.index.*;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Reorders a Lucene segment using BP ordering via SortingCodecReader.
 */
public class SortingBpReorder {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: SortingBpReorder <srcIndexDir> <dstIndexDir> <vectorField>");
            System.exit(1);
        }
        
        Path srcPath = Path.of(args[0]);
        Path dstPath = Path.of(args[1]);
        String vectorField = args[2];
        
        new SortingBpReorder().reorder(srcPath, dstPath, vectorField);
    }
    
    public void reorder(Path srcPath, Path dstPath, String vectorField) throws IOException {
        try (Directory srcDir = FSDirectory.open(srcPath);
             Directory dstDir = FSDirectory.open(dstPath);
             DirectoryReader reader = DirectoryReader.open(srcDir)) {
            
            System.out.println("Source index: " + srcPath);
            System.out.println("Leaves: " + reader.leaves().size());
            
            for (LeafReaderContext ctx : reader.leaves()) {
                CodecReader codecReader = (CodecReader) ctx.reader();
                System.out.println("Leaf " + ctx.ord + ": " + codecReader.maxDoc() + " docs");
                
                // Read vectors for BP computation
                FloatVectorValues vectors = codecReader.getFloatVectorValues(vectorField);
                if (vectors == null) {
                    System.out.println("  No float vectors for field: " + vectorField);
                    continue;
                }
                
                int numVectors = vectors.size();
                int dim = vectors.dimension();
                System.out.println("  Vectors: " + numVectors + " x " + dim);
                
                // Load vectors into array
                float[][] vectorArray = new float[numVectors][dim];
                var iter = vectors.iterator();
                while (iter.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
                    int ord = iter.index();
                    System.arraycopy(vectors.vectorValue(ord), 0, vectorArray[ord], 0, dim);
                }
                
                // Compute BP ordering
                System.out.println("  Computing BP ordering...");
                long start = System.currentTimeMillis();
                int[] bpOrder = BpReorderer.computePermutation(vectorArray);
                System.out.println("  BP computed in " + (System.currentTimeMillis() - start) + " ms");
                
                // Create inverse mapping
                int[] inverseOrder = new int[bpOrder.length];
                for (int i = 0; i < bpOrder.length; i++) {
                    inverseOrder[bpOrder[i]] = i;
                }
                
                // Create DocMap
                Sorter.DocMap docMap = new Sorter.DocMap() {
                    @Override
                    public int oldToNew(int docID) {
                        return inverseOrder[docID];
                    }
                    
                    @Override
                    public int newToOld(int docID) {
                        return bpOrder[docID];
                    }
                    
                    @Override
                    public int size() {
                        return bpOrder.length;
                    }
                };
                
                // Wrap with SortingCodecReader
                CodecReader sortedReader = SortingCodecReader.wrap(codecReader, docMap, null);
                
                // Write to destination
                System.out.println("  Writing reordered segment...");
                IndexWriterConfig iwc = new IndexWriterConfig();
                iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
                // Use standard Lucene codec for now
                // iwc.setCodec(new BpReorderCodec());
                
                try (IndexWriter writer = new IndexWriter(dstDir, iwc)) {
                    writer.addIndexes(sortedReader);
                }
                
                System.out.println("  Done.");
            }
        }
    }
}
