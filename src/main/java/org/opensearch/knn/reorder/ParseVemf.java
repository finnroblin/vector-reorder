/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Parse and dump the .vemf metadata file.
 */
public class ParseVemf {

    public static void main(String[] args) throws Exception {
        String vemfPath = args.length > 0 ? args[0] : "/Users/finnrobl/Documents/k-NN-2/vector-reorder/raw_test_files/_z_NativeEngines990KnnVectorsFormat_0.vemf";
        
        Path path = Paths.get(vemfPath);
        try (FSDirectory directory = FSDirectory.open(path.getParent());
             IndexInput input = directory.openInput(path.getFileName().toString(), IOContext.DEFAULT)) {
            
            System.out.println("File length: " + input.length());
            
            // Skip codec header (we know it's Lucene99FlatVectorsFormatMeta)
            // Header: magic (4) + codec name length (1) + codec name + version (4) + object id (16)
            int codecNameLen = "Lucene99FlatVectorsFormatMeta".length();
            int headerLen = 4 + 1 + codecNameLen + 4 + 16;
            
            // Then field name
            int fieldNameLen = "NativeEngines990KnnVectorsFormat_0".length();
            int fieldHeaderLen = 1 + fieldNameLen;
            
            input.seek(headerLen + fieldHeaderLen);
            
            System.out.println("Position after headers: " + input.getFilePointer());
            
            int fieldNumber = input.readInt();
            int vectorEncoding = input.readInt();
            int similarityFunction = input.readInt();
            
            System.out.println("fieldNumber: " + fieldNumber);
            System.out.println("vectorEncoding: " + vectorEncoding);
            System.out.println("similarityFunction: " + similarityFunction);
            
            long vectorDataOffset = input.readVLong();
            long vectorDataLength = input.readVLong();
            int dimension = input.readVInt();
            int size = input.readInt();
            
            System.out.println("vectorDataOffset: " + vectorDataOffset);
            System.out.println("vectorDataLength: " + vectorDataLength);
            System.out.println("dimension: " + dimension);
            System.out.println("size: " + size);
            
            // OrdToDoc configuration
            long docsWithFieldOffset = input.readLong();
            long docsWithFieldLength = input.readLong();
            short jumpTableEntryCount = input.readShort();
            byte denseRankPower = input.readByte();
            
            System.out.println("\nOrdToDoc configuration:");
            System.out.println("docsWithFieldOffset: " + docsWithFieldOffset);
            System.out.println("docsWithFieldLength: " + docsWithFieldLength);
            System.out.println("jumpTableEntryCount: " + jumpTableEntryCount);
            System.out.println("denseRankPower: " + denseRankPower);
            
            if (docsWithFieldOffset == -2) {
                System.out.println("=> EMPTY (no vectors)");
            } else if (docsWithFieldOffset == -1) {
                System.out.println("=> DENSE (ordinal == docID)");
            } else {
                System.out.println("=> SPARSE (explicit ordToDoc mapping)");
            }
        }
    }
}
