/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

import org.opensearch.Version;

/**
 * Test for QuantizationStateIO.
 */
public class QuantizationStateIOTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== QuantizationStateIO Test ===");
        System.out.println("OpenSearch V_3_2_0 id: " + Version.V_3_2_0.id);
        System.out.println("OpenSearch CURRENT id: " + Version.CURRENT.id);
        
        // Test quantization
        float[] vector = {1.2f, 3.4f, 5.6f, 2.1f, 4.3f, 6.5f, 1.1f, 2.2f};
        float[] thresholds = {2.0f, 3.0f, 4.0f, 2.5f, 4.0f, 5.0f, 1.5f, 2.0f};
        
        QuantizationStateIO.OneBitState state = new QuantizationStateIO.OneBitState(thresholds, null);
        
        byte[] quantized = QuantizationStateIO.quantize(vector, state);
        
        System.out.println("Vector: " + java.util.Arrays.toString(vector));
        System.out.println("Thresholds: " + java.util.Arrays.toString(thresholds));
        System.out.println("Quantized bytes: " + quantized.length);
        System.out.println("Quantized bits: " + toBinaryString(quantized));
        
        // Expected: 1.2<2.0=0, 3.4>3.0=1, 5.6>4.0=1, 2.1<2.5=0, 4.3>4.0=1, 6.5>5.0=1, 1.1<1.5=0, 2.2>2.0=1
        // = 01101101 = 0x6D
        System.out.println("Expected: 01101101 (0x6D = " + 0x6D + ")");
        System.out.println("Actual:   " + (quantized[0] & 0xFF));
        
        if ((quantized[0] & 0xFF) == 0x6D) {
            System.out.println("✓ Quantization test PASSED");
        } else {
            System.out.println("✗ Quantization test FAILED");
            System.exit(1);
        }
    }
    
    private static String toBinaryString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            for (int i = 7; i >= 0; i--) {
                sb.append((b >> i) & 1);
            }
            sb.append(" ");
        }
        return sb.toString().trim();
    }
}
