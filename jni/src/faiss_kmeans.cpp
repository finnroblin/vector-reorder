/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#include "org_opensearch_knn_reorder_FaissKMeansService.h"
#include <faiss/Clustering.h>
#include <faiss/IndexFlat.h>
#include <vector>

// Metric types matching SpaceType
static const int METRIC_L2 = 0;
static const int METRIC_INNER_PRODUCT = 1;

JNIEXPORT jintArray JNICALL Java_org_opensearch_knn_reorder_FaissKMeansService_kmeans(
    JNIEnv* env, jclass cls,
    jlong vectorsAddress, jint numVectors, jint dimension, jint numClusters, jint numIterations)
{
    float* vectors = reinterpret_cast<float*>(vectorsAddress);
    int n = numVectors;
    int d = dimension;
    int k = numClusters;

    faiss::ClusteringParameters cp;
    cp.niter = numIterations;
    cp.verbose = false;

    faiss::Clustering clustering(d, k, cp);
    faiss::IndexFlatL2 index(d);

    clustering.train(n, vectors, index);

    // Get assignments by searching for nearest centroid
    std::vector<faiss::idx_t> assignments(n);
    std::vector<float> distances(n);
    index.search(n, vectors, 1, distances.data(), assignments.data());

    // Convert to jintArray
    jintArray result = env->NewIntArray(n);
    std::vector<jint> jassignments(assignments.begin(), assignments.end());
    env->SetIntArrayRegion(result, 0, n, jassignments.data());

    return result;
}

JNIEXPORT jobject JNICALL Java_org_opensearch_knn_reorder_FaissKMeansService_kmeansWithDistances(
    JNIEnv* env, jclass cls,
    jlong vectorsAddress, jint numVectors, jint dimension, jint numClusters, jint numIterations, jint metricType)
{
    float* vectors = reinterpret_cast<float*>(vectorsAddress);
    int n = numVectors;
    int d = dimension;
    int k = numClusters;

    faiss::ClusteringParameters cp;
    cp.niter = numIterations;
    cp.verbose = false;

    faiss::Clustering clustering(d, k, cp);
    
    // Use appropriate index based on metric type
    faiss::Index* index;
    if (metricType == METRIC_INNER_PRODUCT) {
        index = new faiss::IndexFlatIP(d);
    } else {
        index = new faiss::IndexFlatL2(d);
    }

    clustering.train(n, vectors, *index);

    // Get assignments and distances by searching for nearest centroid
    std::vector<faiss::idx_t> assignments(n);
    std::vector<float> distances(n);
    index->search(n, vectors, 1, distances.data(), assignments.data());
    
    delete index;

    // Create KMeansResult object
    jclass resultClass = env->FindClass("org/opensearch/knn/reorder/KMeansResult");
    jmethodID constructor = env->GetMethodID(resultClass, "<init>", "([I[F)V");
    
    jintArray assignmentsArray = env->NewIntArray(n);
    std::vector<jint> jassignments(assignments.begin(), assignments.end());
    env->SetIntArrayRegion(assignmentsArray, 0, n, jassignments.data());
    
    jfloatArray distancesArray = env->NewFloatArray(n);
    env->SetFloatArrayRegion(distancesArray, 0, n, distances.data());
    
    return env->NewObject(resultClass, constructor, assignmentsArray, distancesArray);
}

JNIEXPORT jlong JNICALL Java_org_opensearch_knn_reorder_FaissKMeansService_storeVectors(
    JNIEnv* env, jclass cls, jobjectArray vectors)
{
    int n = env->GetArrayLength(vectors);
    if (n == 0) return 0;
    
    jfloatArray firstRow = (jfloatArray)env->GetObjectArrayElement(vectors, 0);
    int d = env->GetArrayLength(firstRow);
    
    float* data = new float[n * d];
    
    for (int i = 0; i < n; i++) {
        jfloatArray row = (jfloatArray)env->GetObjectArrayElement(vectors, i);
        env->GetFloatArrayRegion(row, 0, d, data + i * d);
    }
    
    return reinterpret_cast<jlong>(data);
}

JNIEXPORT void JNICALL Java_org_opensearch_knn_reorder_FaissKMeansService_freeVectors(
    JNIEnv* env, jclass cls, jlong address)
{
    delete[] reinterpret_cast<float*>(address);
}
