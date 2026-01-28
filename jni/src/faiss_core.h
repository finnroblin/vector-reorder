/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <vector>
#include <string>
#include <cstdint>

namespace faiss_core {

// Metric types
enum MetricType { L2 = 0, INNER_PRODUCT = 1 };

// K-means result
struct KMeansResult {
    std::vector<int> assignments;
    std::vector<float> distances;
};

// Run k-means clustering, returns cluster assignments
std::vector<int> kmeans(float* vectors, int numVectors, int dimension, 
                        int numClusters, int numIterations);

// Run k-means with distances
KMeansResult kmeansWithDistances(float* vectors, int numVectors, int dimension,
                                  int numClusters, int numIterations, MetricType metric);

// Build HNSW index and write to file
void buildAndWriteIndex(float* vectors, int numVectors, int dimension,
                        const std::vector<int64_t>& ids,
                        const std::string& indexDescription,
                        MetricType metric, int efConstruction, int efSearch,
                        const std::string& outputPath);

} // namespace faiss_core
