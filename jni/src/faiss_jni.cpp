/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#include "org_opensearch_knn_reorder_FaissKMeansService.h"
#include "org_opensearch_knn_reorder_FaissIndexService.h"
#include "faiss_core.h"
#include <vector>
#include <string>

// ============================================================================
// FaissKMeansService JNI
// ============================================================================

JNIEXPORT jintArray JNICALL Java_org_opensearch_knn_reorder_FaissKMeansService_kmeans(
    JNIEnv* env, jclass cls,
    jlong vectorsAddress, jint numVectors, jint dimension, jint numClusters, jint numIterations)
{
    float* vectors = reinterpret_cast<float*>(vectorsAddress);
    
    std::vector<int> assignments = faiss_core::kmeans(
        vectors, numVectors, dimension, numClusters, numIterations);

    jintArray result = env->NewIntArray(numVectors);
    env->SetIntArrayRegion(result, 0, numVectors, assignments.data());
    return result;
}

JNIEXPORT jobject JNICALL Java_org_opensearch_knn_reorder_FaissKMeansService_kmeansWithDistances(
    JNIEnv* env, jclass cls,
    jlong vectorsAddress, jint numVectors, jint dimension, jint numClusters, jint numIterations, jint metricType)
{
    float* vectors = reinterpret_cast<float*>(vectorsAddress);
    
    faiss_core::KMeansResult result = faiss_core::kmeansWithDistances(
        vectors, numVectors, dimension, numClusters, numIterations,
        static_cast<faiss_core::MetricType>(metricType));

    jclass resultClass = env->FindClass("org/opensearch/knn/reorder/KMeansResult");
    jmethodID constructor = env->GetMethodID(resultClass, "<init>", "([I[F)V");
    
    jintArray assignmentsArray = env->NewIntArray(numVectors);
    env->SetIntArrayRegion(assignmentsArray, 0, numVectors, result.assignments.data());
    
    jfloatArray distancesArray = env->NewFloatArray(numVectors);
    env->SetFloatArrayRegion(distancesArray, 0, numVectors, result.distances.data());
    
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

// ============================================================================
// FaissIndexService JNI
// ============================================================================

JNIEXPORT void JNICALL Java_org_opensearch_knn_reorder_FaissIndexService_buildAndWriteIndex(
    JNIEnv* env, jclass cls,
    jlong vectorsAddress, jint numVectors, jint dimension, jintArray idsJ,
    jstring indexDescriptionJ, jstring spaceTypeJ, jint efConstruction, jint efSearch, jstring outputPathJ)
{
    float* vectors = reinterpret_cast<float*>(vectorsAddress);
    
    // Marshal strings
    const char* indexDescCStr = env->GetStringUTFChars(indexDescriptionJ, nullptr);
    const char* spaceTypeCStr = env->GetStringUTFChars(spaceTypeJ, nullptr);
    const char* outputPathCStr = env->GetStringUTFChars(outputPathJ, nullptr);
    
    std::string indexDesc(indexDescCStr);
    std::string spaceType(spaceTypeCStr);
    std::string outputPath(outputPathCStr);
    
    env->ReleaseStringUTFChars(indexDescriptionJ, indexDescCStr);
    env->ReleaseStringUTFChars(spaceTypeJ, spaceTypeCStr);
    env->ReleaseStringUTFChars(outputPathJ, outputPathCStr);
    
    // Marshal IDs
    jint* idsPtr = env->GetIntArrayElements(idsJ, nullptr);
    int numIds = env->GetArrayLength(idsJ);
    std::vector<int64_t> ids(idsPtr, idsPtr + numIds);
    env->ReleaseIntArrayElements(idsJ, idsPtr, JNI_ABORT);
    
    // Determine metric
    faiss_core::MetricType metric = faiss_core::L2;
    if (spaceType == "innerproduct" || spaceType == "cosinesimil") {
        metric = faiss_core::INNER_PRODUCT;
    }
    
    faiss_core::buildAndWriteIndex(vectors, numVectors, dimension, ids,
                                    indexDesc, metric, efConstruction, efSearch, outputPath);
}
