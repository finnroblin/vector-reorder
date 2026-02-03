/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Permutes a .faiss file to match reordered vectors from clustering.
 * 
 * FAISS file structure (for HNSW with ID mapping):
 * - IxMp header (ID mapping wrapper)
 * - IHNf/IHNs (HNSW index with flat storage)
 *   - Common header (dimension, ntotal, metric)
 *   - HNSW graph (neighbors, levels, offsets)
 *   - Flat vector storage (IxF2/IxFI)
 * - ID mapping array
 */
public class FaissFilePermuter {

    // FAISS index type markers (4 bytes each)
    private static final String IXMP = "IxMp";  // ID map wrapper (float)
    private static final String IBMP = "IBMp";  // ID map wrapper (binary)
    private static final String IHNF = "IHNf";  // HNSW flat float
    private static final String IHNS = "IHNs";  // HNSW scalar quantized
    private static final String IBHF = "IBHf";  // HNSW binary flat
    private static final String IXF2 = "IxF2";  // Flat L2
    private static final String IXFI = "IxFI";  // Flat inner product

    /**
     * Parsed structure of a FAISS file for permutation.
     */
    public static class FaissStructure {
        // File layout offsets
        public long headerEnd;           // End of IxMp common header
        public long hnswStart;           // Start of IHNf/IHNs section
        public long hnswHeaderEnd;       // End of HNSW common header
        public long assignProbasEnd;     // End of assignProbas array
        public long cumNeighborsEnd;     // End of cumulative neighbors array
        public long levelsStart;         // Start of levels section
        public long levelsEnd;           // End of levels section
        public long offsetsStart;        // Start of offsets (DirectMonotonic)
        public long offsetsEnd;          // End of offsets
        public long neighborsStart;      // Start of neighbor lists
        public long neighborsEnd;        // End of neighbor lists
        public long flatVectorsStart;    // Start of flat vector storage
        public long flatVectorsEnd;      // End of flat vectors
        public long idMappingStart;      // Start of ID mapping
        public long fileEnd;             // End of file

        // Metadata
        public boolean isBinary;         // True if binary index (IBMp)
        public String indexType;         // IxMp, IHNf, etc.
        public String hnswType;          // IHNf or IHNs
        public String flatType;          // IxF2 or IxFI or IBxF
        public int dimension;
        public int numVectors;
        public int[] cumNeighborsPerLevel;
        public int maxLevel;
        public int entryPoint;
        public int efConstruction;
        public int efSearch;

        @Override
        public String toString() {
            return String.format(
                "FaissStructure{type=%s, hnsw=%s, flat=%s, dim=%d, n=%d, maxLevel=%d, entry=%d, efC=%d, efS=%d}",
                indexType, hnswType, flatType, dimension, numVectors, maxLevel, entryPoint, efConstruction, efSearch
            );
        }
    }

    // FAISS uses little-endian format
    private static int readIntLE(IndexInput input) throws IOException {
        byte[] b = new byte[4];
        input.readBytes(b, 0, 4);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
    }

    private static long readLongLE(IndexInput input) throws IOException {
        byte[] b = new byte[8];
        input.readBytes(b, 0, 8);
        return (b[0] & 0xFFL) | ((b[1] & 0xFFL) << 8) | ((b[2] & 0xFFL) << 16) | ((b[3] & 0xFFL) << 24)
             | ((b[4] & 0xFFL) << 32) | ((b[5] & 0xFFL) << 40) | ((b[6] & 0xFFL) << 48) | ((b[7] & 0xFFL) << 56);
    }

    /**
     * Parse the structure of a .faiss file without loading all data.
     */
    public static FaissStructure parseStructure(String faissPath) throws IOException {
        Path path = Paths.get(faissPath);
        Path dir = path.getParent();
        String fileName = path.getFileName().toString();

        FaissStructure s = new FaissStructure();

        try (FSDirectory directory = FSDirectory.open(dir);
             IndexInput input = directory.openInput(fileName, IOContext.DEFAULT)) {

            s.fileEnd = input.length();

            // Read top-level index type (4 bytes)
            s.indexType = readIndexType(input);
            
            if (!IXMP.equals(s.indexType) && !IBMP.equals(s.indexType)) {
                throw new IOException("Expected IxMp or IBMp, got: " + s.indexType);
            }
            
            s.isBinary = IBMP.equals(s.indexType);

            if (s.isBinary) {
                // IBMp header: dimension(4) + code_size(4) + ntotal(8) + is_trained(1) + metric(4)
                s.dimension = readIntLE(input);
                readIntLE(input); // code_size (int, not long)
                s.numVectors = Math.toIntExact(readLongLE(input));
                input.readByte(); // is_trained
                readIntLE(input);  // metric_type
            } else {
                // IxMp header: dimension(4) + ntotal(8) + dummy(8) + dummy(8) + is_trained(1) + metric(4)
                s.dimension = readIntLE(input);
                s.numVectors = Math.toIntExact(readLongLE(input));
                readLongLE(input); // dummy
                readLongLE(input); // dummy
                input.readByte(); // is_trained
                readIntLE(input);  // metric_type
            }
            s.headerEnd = input.getFilePointer();

            // Read nested HNSW index
            s.hnswStart = input.getFilePointer();
            s.hnswType = readIndexType(input);
            
            if (!IHNF.equals(s.hnswType) && !IHNS.equals(s.hnswType) && !IBHF.equals(s.hnswType)) {
                throw new IOException("Expected IHNf/IHNs/IBHf, got: " + s.hnswType);
            }

            // HNSW common header (same for float and binary)
            if (IBHF.equals(s.hnswType)) {
                // Binary HNSW: dimension(4) + code_size(4) + ntotal(8) + is_trained(1) + metric(4)
                readIntLE(input);  // dimension
                readIntLE(input);  // code_size (int, not long)
                readLongLE(input); // ntotal
                input.readByte(); // is_trained
                readIntLE(input);  // metric_type
            } else {
                // Float HNSW: dimension(4) + ntotal(8) + dummy(8) + dummy(8) + is_trained(1) + metric(4)
                readIntLE(input);  // dimension
                readLongLE(input); // ntotal
                readLongLE(input); // dummy
                readLongLE(input); // dummy
                input.readByte(); // is_trained
                readIntLE(input);  // metric_type
            }
            s.hnswHeaderEnd = input.getFilePointer();

            // HNSW graph structure
            // assignProbas (double array)
            long assignProbasSize = readLongLE(input);
            input.skipBytes(assignProbasSize * Double.BYTES);
            s.assignProbasEnd = input.getFilePointer();

            // cumulative neighbors per level (int array)
            long cumNeighborsSize = readLongLE(input);
            s.cumNeighborsPerLevel = new int[(int) cumNeighborsSize];
            if (cumNeighborsSize > 0) {
                input.readInts(s.cumNeighborsPerLevel, 0, (int) cumNeighborsSize);
            }
            s.cumNeighborsEnd = input.getFilePointer();

            // levels section (int per vector)
            s.levelsStart = input.getFilePointer();
            long levelsSize = readLongLE(input);
            input.skipBytes(levelsSize * Integer.BYTES);
            s.levelsEnd = input.getFilePointer();

            // offsets (long array - raw longs, NOT DirectMonotonic in file)
            s.offsetsStart = input.getFilePointer();
            long offsetsCount = readLongLE(input);
            input.skipBytes(offsetsCount * Long.BYTES);
            s.offsetsEnd = input.getFilePointer();

            // neighbors section (int array)
            s.neighborsStart = input.getFilePointer();
            long neighborsSize = readLongLE(input);
            input.skipBytes(neighborsSize * Integer.BYTES);
            s.neighborsEnd = input.getFilePointer();

            // HNSW params: entryPoint(4) + maxLevel(4) + efConstruction(4) + efSearch(4) + dummy(4)
            s.entryPoint = readIntLE(input);
            s.maxLevel = readIntLE(input);
            s.efConstruction = readIntLE(input);
            s.efSearch = readIntLE(input);
            readIntLE(input); // dummy

            // Flat vectors section
            s.flatVectorsStart = input.getFilePointer();
            s.flatType = readIndexType(input);
            
            if (s.isBinary) {
                // IBxF (IndexBinaryFlat): dimension(4) + code_size(4) + ntotal(8) + is_trained(1) + metric(4)
                readIntLE(input);  // dimension
                int codeSize = readIntLE(input);  // code_size
                readLongLE(input); // ntotal
                input.readByte(); // is_trained
                readIntLE(input);  // metric_type

                // Binary vector data: size(8) + data
                long vectorDataSize = readLongLE(input);
                input.skipBytes(vectorDataSize);  // Already in bytes
            } else {
                // IxF2/IxFI (IndexFlat): dimension(4) + ntotal(8) + dummy(8) + dummy(8) + is_trained(1) + metric(4)
                readIntLE(input);  // dimension
                readLongLE(input); // ntotal
                readLongLE(input); // dummy
                readLongLE(input); // dummy
                input.readByte(); // is_trained
                readIntLE(input);  // metric_type

                // Float vector data
                long vectorDataSize = readLongLE(input);
                input.skipBytes(vectorDataSize * Float.BYTES);
            }
            s.flatVectorsEnd = input.getFilePointer();

            // ID mapping (long array in IxMp)
            s.idMappingStart = input.getFilePointer();
            // The rest until Lucene footer is ID mapping
        }

        return s;
    }

    /**
     * Permute a .faiss file according to the given new order.
     * 
     * @param faissPath path to original .faiss file
     * @param newOrder newOrder[newIdx] = oldIdx (from clustering)
     * @param outputPath path for output .faiss file
     */
    public static void permute(String faissPath, int[] newOrder, String outputPath) throws IOException {
        FaissStructure s = parseStructure(faissPath);
        
        // Build inverse mapping: inverse[oldIdx] = newIdx
        int[] inverse = new int[newOrder.length];
        for (int newIdx = 0; newIdx < newOrder.length; newIdx++) {
            inverse[newOrder[newIdx]] = newIdx;
        }

        Path inPath = Paths.get(faissPath);
        Path outPath = Paths.get(outputPath);

        try (FSDirectory inDir = FSDirectory.open(inPath.getParent());
             FSDirectory outDir = FSDirectory.open(outPath.getParent());
             IndexInput input = inDir.openInput(inPath.getFileName().toString(), IOContext.DEFAULT);
             IndexOutput output = outDir.createOutput(outPath.getFileName().toString(), IOContext.DEFAULT)) {

            // Copy header unchanged (IxMp header + IHNf header + assignProbas + cumNeighbors)
            copyBytes(input, output, 0, s.cumNeighborsEnd);

            // Permute levels section
            permuteLevels(input, output, s, newOrder);

            // Rewrite offsets (need to recompute based on permuted levels)
            // For now, copy unchanged - TODO: proper recomputation
            copyBytes(input, output, s.offsetsStart, s.offsetsEnd - s.offsetsStart);

            // Remap neighbor IDs in neighbor lists
            remapNeighbors(input, output, s, inverse);

            // Copy HNSW params (entryPoint needs remapping)
            output.writeInt(inverse[s.entryPoint]);  // Remap entry point
            input.seek(s.neighborsEnd + 4);  // Skip original entryPoint
            copyBytes(input, output, input.getFilePointer(), s.flatVectorsStart - input.getFilePointer());

            // Permute flat vectors
            permuteVectors(input, output, s, newOrder);

            // Copy ID mapping (permute if needed)
            permuteIdMapping(input, output, s, newOrder);
        }
    }

    private static void permuteLevels(IndexInput input, IndexOutput output, FaissStructure s, int[] newOrder) 
            throws IOException {
        input.seek(s.levelsStart);
        long count = readLongLE(input);
        output.writeLong(count);

        // Read all levels
        int[] levels = new int[(int) count];
        input.readInts(levels, 0, (int) count);

        // Write in new order
        for (int newIdx = 0; newIdx < newOrder.length; newIdx++) {
            output.writeInt(levels[newOrder[newIdx]]);
        }
    }

    private static void remapNeighbors(IndexInput input, IndexOutput output, FaissStructure s, int[] inverse) 
            throws IOException {
        input.seek(s.neighborsStart);
        long count = readLongLE(input);
        output.writeLong(count);

        // Read, remap, and write each neighbor ID
        for (long i = 0; i < count; i++) {
            int neighborId = readIntLE(input);
            if (neighborId >= 0 && neighborId < inverse.length) {
                output.writeInt(inverse[neighborId]);
            } else {
                output.writeInt(neighborId);  // Keep -1 terminators unchanged
            }
        }
    }

    private static void permuteVectors(IndexInput input, IndexOutput output, FaissStructure s, int[] newOrder) 
            throws IOException {
        input.seek(s.flatVectorsStart);
        
        // Copy flat index header
        byte[] header = new byte[4 + 4 + 8 + 8 + 8 + 1 + 4];  // type + common header
        input.readBytes(header, 0, header.length);
        output.writeBytes(header, header.length);

        // Read vector count and data
        long vectorCount = readLongLE(input);
        output.writeLong(vectorCount);

        int bytesPerVector = s.dimension * Float.BYTES;
        long dataStart = input.getFilePointer();

        // Read all vectors
        byte[][] vectors = new byte[(int) vectorCount][bytesPerVector];
        for (int i = 0; i < vectorCount; i++) {
            input.readBytes(vectors[i], 0, bytesPerVector);
        }

        // Write in new order
        for (int newIdx = 0; newIdx < newOrder.length; newIdx++) {
            output.writeBytes(vectors[newOrder[newIdx]], bytesPerVector);
        }
    }

    private static void permuteIdMapping(IndexInput input, IndexOutput output, FaissStructure s, int[] newOrder) 
            throws IOException {
        input.seek(s.idMappingStart);
        long count = readLongLE(input);
        output.writeLong(count);

        // Read all mappings
        long[] mapping = new long[(int) count];
        for (int i = 0; i < count; i++) {
            mapping[i] = readLongLE(input);
        }

        // Write in new order (the mapping values stay the same, just reordered)
        for (int newIdx = 0; newIdx < newOrder.length; newIdx++) {
            output.writeLong(mapping[newOrder[newIdx]]);
        }

        // Copy any remaining bytes (Lucene footer)
        long remaining = s.fileEnd - input.getFilePointer();
        if (remaining > 0) {
            copyBytes(input, output, input.getFilePointer(), remaining);
        }
    }

    private static String readIndexType(IndexInput input) throws IOException {
        byte[] bytes = new byte[4];
        input.readBytes(bytes, 0, 4);
        return new String(bytes);
    }

    private static void copyBytes(IndexInput input, IndexOutput output, long offset, long length) throws IOException {
        input.seek(offset);
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            input.readBytes(buffer, 0, toRead);
            output.writeBytes(buffer, toRead);
            remaining -= toRead;
        }
    }

    /**
     * Read the ID mapping array from a .faiss file.
     * @return array where idMapping[faissOrd] = docID
     */
    public static long[] readIdMapping(String faissPath) throws IOException {
        FaissStructure s = parseStructure(faissPath);
        Path path = Paths.get(faissPath);
        
        try (FSDirectory directory = FSDirectory.open(path.getParent());
             IndexInput input = directory.openInput(path.getFileName().toString(), IOContext.DEFAULT)) {
            
            input.seek(s.idMappingStart);
            long count = readLongLE(input);
            long[] mapping = new long[(int) count];
            for (int i = 0; i < count; i++) {
                mapping[i] = readLongLE(input);
            }
            return mapping;
        }
    }

    /**
     * Read HNSW parameters from a FAISS file.
     * @return int[2] where [0]=efConstruction, [1]=efSearch
     */
    public static int[] readHnswParams(String faissPath) throws IOException {
        FaissStructure s = parseStructure(faissPath);
        return new int[] { s.efConstruction, s.efSearch };
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: FaissFilePermuter <faiss-file> [parse|permute]");
            System.exit(1);
        }

        String faissPath = args[0];
        String command = args.length > 1 ? args[1] : "parse";

        if ("parse".equals(command)) {
            FaissStructure s = parseStructure(faissPath);
            System.out.println(s);
            System.out.println("Header end: " + s.headerEnd);
            System.out.println("HNSW start: " + s.hnswStart);
            System.out.println("Levels: " + s.levelsStart + " - " + s.levelsEnd);
            System.out.println("Offsets: " + s.offsetsStart + " - " + s.offsetsEnd);
            System.out.println("Neighbors: " + s.neighborsStart + " - " + s.neighborsEnd);
            System.out.println("Flat vectors: " + s.flatVectorsStart + " - " + s.flatVectorsEnd);
            System.out.println("ID mapping: " + s.idMappingStart);
            System.out.println("File end: " + s.fileEnd);
        }
    }
}
