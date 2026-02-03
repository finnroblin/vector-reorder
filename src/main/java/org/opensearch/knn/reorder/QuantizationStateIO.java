/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.Directory;
import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Reads quantization state from .osknnqstate files.
 * Supports OneBitScalarQuantizationState format.
 */
public class QuantizationStateIO {

    /**
     * Parsed quantization state for 1-bit scalar quantization.
     */
    public static class OneBitState {
        public final float[] meanThresholds;
        public final float[][] rotationMatrix; // may be null

        public OneBitState(float[] meanThresholds, float[][] rotationMatrix) {
            this.meanThresholds = meanThresholds;
            this.rotationMatrix = rotationMatrix;
        }

        public int getBytesPerVector() {
            return (meanThresholds.length + 7) / 8;
        }
    }

    /**
     * Read quantization state for a field from .osknnqstate file.
     */
    public static OneBitState readOneBitState(Directory dir, String segmentName, String segmentSuffix, int fieldNumber) throws IOException {
        String fileName = segmentName + "_" + segmentSuffix + ".osknnqstate";
        
        try (IndexInput input = dir.openInput(fileName, IOContext.READONCE)) {
            CodecUtil.retrieveChecksum(input);
            
            // Read index section position from footer area
            long footerStart = input.length() - CodecUtil.footerLength();
            input.seek(footerStart - Integer.BYTES - Long.BYTES);
            long indexStartPosition = input.readLong();
            
            // Read number of fields
            input.seek(indexStartPosition);
            int numFields = input.readInt();
            
            // Find our field
            long position = -1;
            int length = 0;
            for (int i = 0; i < numFields; i++) {
                int fn = input.readInt();
                int len = input.readInt();
                long pos = input.readVLong();
                if (fn == fieldNumber) {
                    position = pos;
                    length = len;
                    break;
                }
            }
            
            if (position == -1) {
                throw new IllegalArgumentException("Field " + fieldNumber + " not found in quantization state");
            }
            
            // Read state bytes
            input.seek(position);
            byte[] stateBytes = new byte[length];
            input.readBytes(stateBytes, 0, length);
            
            return parseOneBitState(stateBytes);
        }
    }

    private static OneBitState parseOneBitState(byte[] bytes) throws IOException {
        try (StreamInput in = StreamInput.wrap(bytes)) {
            int version = in.readVInt(); // version
            
            // Read ScalarQuantizationParams
            in.readVInt(); // sqType ordinal
            // For versions >= 3.2.0 (id=137237827), read enableRandomRotation and enableADC
            if (version >= 137237827) {
                in.readBoolean(); // enableRandomRotation
                in.readBoolean(); // enableADC
            }
            
            // Read meanThresholds
            float[] meanThresholds = in.readFloatArray();
            
            // Read rotation matrix (if present, for versions >= 3.2.0)
            float[][] rotationMatrix = null;
            if (version >= 137237827 && in.readBoolean()) {
                int dims = in.readVInt();
                rotationMatrix = new float[dims][];
                for (int i = 0; i < dims; i++) {
                    rotationMatrix[i] = in.readFloatArray();
                }
            }
            
            // Skip belowThresholdMeans and aboveThresholdMeans (not needed for quantization)
            
            return new OneBitState(meanThresholds, rotationMatrix);
        }
    }

    /**
     * Quantize a float vector to bytes using 1-bit scalar quantization.
     */
    public static byte[] quantize(float[] vector, OneBitState state) {
        float[] v = vector;
        if (state.rotationMatrix != null) {
            v = applyRotation(vector, state.rotationMatrix);
        }
        
        byte[] result = new byte[state.getBytesPerVector()];
        float[] thresholds = state.meanThresholds;
        
        for (int j = 0; j < v.length; j++) {
            if (v[j] > thresholds[j]) {
                int byteIndex = j >> 3;
                int bitIndex = 7 - (j & 7);
                result[byteIndex] |= (1 << bitIndex);
            }
        }
        return result;
    }

    private static float[] applyRotation(float[] vector, float[][] matrix) {
        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            float sum = 0;
            for (int j = 0; j < vector.length; j++) {
                sum += matrix[i][j] * vector[j];
            }
            result[i] = sum;
        }
        return result;
    }
}
