/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.reorder;

/**
 * Result of k-means clustering containing cluster assignments and distances to centroids.
 */
public record KMeansResult(int[] assignments, float[] distances) {}
