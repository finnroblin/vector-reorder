#!/bin/bash
# Configuration for reorder benchmarking

export OS_URL="http://localhost:9200"
export INDEX="sift-index"
export HDF5_PATH="/Users/finnrobl/Downloads/sift-128-euclidean.hdf5"

export DATA_DIR="/home/ec2-user/k-NN/build/testclusters/integTest-0/distro/3.5.0-ARCHIVE/data"
export NODE_DIR="${DATA_DIR}/nodes/0/indices"
export OPENSEARCH_HOME="/Users/finnrobl/Documents/k-NN-2/k-NN/distribution/build/distribution/local/opensearch-3.0.0-SNAPSHOT"
export VECTOR_REORDER_DIR="/Users/finnrobl/Documents/k-NN-2/vector-reorder"

export BASELINE_BACKUPS="/Users/finnrobl/Documents/k-NN-2/BASELINE_backups"
export KMEANS_BACKUPS="/Users/finnrobl/Documents/k-NN-2/KMEANS_backups"
export BP_BACKUPS="/Users/finnrobl/Documents/k-NN-2/BP_backups"

export OSB_WORKLOAD_PATH="/Users/finnrobl/Documents/opensearch-benchmark-workloads/vectorsearch"
export OSB_PARAMS="/Users/finnrobl/Documents/opensearch-benchmark-workloads/vectorsearch/params/faiss-sift-128-l2.json"
