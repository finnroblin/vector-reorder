/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#include "../src/faiss_core.h"
#include <faiss/Index.h>
#include <faiss/index_io.h>
#include <iostream>
#include <cassert>
#include <cmath>
#include <cstdio>

void test_kmeans() {
    std::cout << "=== Test: kmeans ===" << std::endl;
    
    int n = 150, d = 2, k = 3;
    std::vector<float> vectors(n * d);
    
    // 3 clusters around (1,1), (5,5), (9,9)
    for (int i = 0; i < 50; i++) {
        vectors[i * 2] = 1.0f + 0.1f * (i % 10 - 5);
        vectors[i * 2 + 1] = 1.0f + 0.1f * (i / 10 - 2);
    }
    for (int i = 50; i < 100; i++) {
        vectors[i * 2] = 5.0f + 0.1f * ((i - 50) % 10 - 5);
        vectors[i * 2 + 1] = 5.0f + 0.1f * ((i - 50) / 10 - 2);
    }
    for (int i = 100; i < 150; i++) {
        vectors[i * 2] = 9.0f + 0.1f * ((i - 100) % 10 - 5);
        vectors[i * 2 + 1] = 9.0f + 0.1f * ((i - 100) / 10 - 2);
    }
    
    auto assignments = faiss_core::kmeans(vectors.data(), n, d, k, 20);
    
    assert(assignments.size() == n);
    assert(assignments[0] == assignments[25]);   // same cluster
    assert(assignments[50] == assignments[75]);  // same cluster
    assert(assignments[0] != assignments[50]);   // different clusters
    
    std::cout << "✓ kmeans passed" << std::endl << std::endl;
}

void test_kmeans_with_distances() {
    std::cout << "=== Test: kmeansWithDistances ===" << std::endl;
    
    int n = 100, d = 2, k = 2;
    std::vector<float> vectors(n * d);
    
    for (int i = 0; i < 50; i++) {
        vectors[i * 2] = 0.0f + 0.1f * i;
        vectors[i * 2 + 1] = 0.0f;
    }
    for (int i = 50; i < 100; i++) {
        vectors[i * 2] = 10.0f + 0.1f * (i - 50);
        vectors[i * 2 + 1] = 0.0f;
    }
    
    auto result = faiss_core::kmeansWithDistances(vectors.data(), n, d, k, 20, faiss_core::L2);
    
    assert(result.assignments.size() == n);
    assert(result.distances.size() == n);
    assert(result.assignments[0] == result.assignments[25]);
    assert(result.assignments[0] != result.assignments[75]);
    
    std::cout << "✓ kmeansWithDistances passed" << std::endl << std::endl;
}

void test_build_and_write_index() {
    std::cout << "=== Test: buildAndWriteIndex ===" << std::endl;
    
    int n = 100, d = 8;
    std::vector<float> vectors(n * d);
    std::vector<int64_t> ids(n);
    
    for (int i = 0; i < n * d; i++) {
        vectors[i] = static_cast<float>(rand()) / RAND_MAX;
    }
    for (int i = 0; i < n; i++) {
        ids[i] = i * 10;  // non-sequential IDs
    }
    
    std::string path = "/tmp/test_core_index.faiss";
    
    faiss_core::buildAndWriteIndex(vectors.data(), n, d, ids,
                                    "HNSW16,Flat", faiss_core::L2, 40, 40, path);
    
    // Verify by loading
    faiss::Index* loaded = faiss::read_index(path.c_str());
    assert(loaded->ntotal == n);
    assert(loaded->d == d);
    
    // Test search
    std::vector<faiss::idx_t> labels(3);
    std::vector<float> distances(3);
    loaded->search(1, vectors.data(), 3, distances.data(), labels.data());
    
    assert(labels[0] == 0);  // first vector maps to ID 0
    
    delete loaded;
    std::remove(path.c_str());
    
    std::cout << "✓ buildAndWriteIndex passed" << std::endl << std::endl;
}

int main() {
    std::cout << "\n======================================" << std::endl;
    std::cout << "FAISS Core Function Tests" << std::endl;
    std::cout << "======================================\n" << std::endl;
    
    test_kmeans();
    test_kmeans_with_distances();
    test_build_and_write_index();
    
    std::cout << "======================================" << std::endl;
    std::cout << "All tests passed!" << std::endl;
    std::cout << "======================================\n" << std::endl;
    
    return 0;
}
