/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#include "faiss_core.h"
#include <faiss/Clustering.h>
#include <faiss/IndexFlat.h>
#include <faiss/IndexHNSW.h>
#include <faiss/IndexIDMap.h>
#include <faiss/IndexBinaryFlat.h>
#include <faiss/IndexBinaryHNSW.h>
#include <faiss/index_factory.h>
#include <faiss/index_io.h>

namespace faiss_core {

std::vector<int> kmeans(float* vectors, int numVectors, int dimension,
                        int numClusters, int numIterations) {
    faiss::ClusteringParameters cp;
    cp.niter = numIterations;
    cp.verbose = false;

    faiss::Clustering clustering(dimension, numClusters, cp);
    faiss::IndexFlatL2 index(dimension);
    clustering.train(numVectors, vectors, index);

    std::vector<faiss::idx_t> assignments(numVectors);
    std::vector<float> distances(numVectors);
    index.search(numVectors, vectors, 1, distances.data(), assignments.data());

    return std::vector<int>(assignments.begin(), assignments.end());
}

KMeansResult kmeansWithDistances(float* vectors, int numVectors, int dimension,
                                  int numClusters, int numIterations, MetricType metric) {
    faiss::ClusteringParameters cp;
    cp.niter = numIterations;
    cp.verbose = false;

    faiss::Clustering clustering(dimension, numClusters, cp);
    
    faiss::Index* index = (metric == INNER_PRODUCT) 
        ? static_cast<faiss::Index*>(new faiss::IndexFlatIP(dimension))
        : static_cast<faiss::Index*>(new faiss::IndexFlatL2(dimension));

    clustering.train(numVectors, vectors, *index);

    std::vector<faiss::idx_t> assignments(numVectors);
    std::vector<float> distances(numVectors);
    index->search(numVectors, vectors, 1, distances.data(), assignments.data());
    
    delete index;

    KMeansResult result;
    result.assignments = std::vector<int>(assignments.begin(), assignments.end());
    result.distances = distances;
    return result;
}

void buildAndWriteIndex(float* vectors, int numVectors, int dimension,
                        const std::vector<int64_t>& ids,
                        const std::string& indexDescription,
                        MetricType metric, int efConstruction, int efSearch,
                        const std::string& outputPath) {
    faiss::MetricType faissMetric = (metric == INNER_PRODUCT) 
        ? faiss::METRIC_INNER_PRODUCT : faiss::METRIC_L2;
    
    faiss::Index* index = faiss::index_factory(dimension, indexDescription.c_str(), faissMetric);
    
    if (auto* hnswIndex = dynamic_cast<faiss::IndexHNSW*>(index)) {
        hnswIndex->hnsw.efConstruction = efConstruction;
        hnswIndex->hnsw.efSearch = efSearch;
    }
    
    std::vector<faiss::idx_t> faissIds(ids.begin(), ids.end());
    
    faiss::IndexIDMap idMap(index);
    idMap.own_fields = true;
    idMap.add_with_ids(numVectors, vectors, faissIds.data());
    
    faiss::write_index(&idMap, outputPath.c_str());
}

void buildAndWriteBinaryIndex(uint8_t* vectors, int numVectors, int dimension,
                              const std::vector<int64_t>& ids,
                              int hnswM, int efConstruction, int efSearch,
                              const std::string& outputPath) {
    // dimension is in bits for binary index
    faiss::IndexBinaryHNSW* index = new faiss::IndexBinaryHNSW(dimension, hnswM);
    index->hnsw.efConstruction = efConstruction;
    index->hnsw.efSearch = efSearch;
    
    std::vector<faiss::idx_t> faissIds(ids.begin(), ids.end());
    
    faiss::IndexBinaryIDMap idMap(index);
    idMap.own_fields = true;
    idMap.add_with_ids(numVectors, vectors, faissIds.data());
    
    faiss::write_index_binary(&idMap, outputPath.c_str());
}

} // namespace faiss_core
