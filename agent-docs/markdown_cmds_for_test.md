```
import h5py
import requests
import json

OS_URL = "http://localhost:9200"
INDEX = "sift-index"
HDF5_PATH = "/Users/finnrobl/Downloads/sift-128-euclidean.hdf5"

# Create index
requests.delete(f"{OS_URL}/{INDEX}")
requests.put(f"{OS_URL}/{INDEX}", json={
    "settings": {"index": {"knn": True, "number_of_shards": 1, "number_of_replicas": 0, "refresh_interval": "-1"}},
    "mappings": {"properties": {"train": {"type": "knn_vector", "dimension": 128}}}
}).json()

# Bulk ingest
with h5py.File(HDF5_PATH, 'r') as f:
    vectors = f['train'][:]

batch_size = 5000
for i in range(0, len(vectors), batch_size):
    bulk = "".join(
        json.dumps({"index": {"_index": INDEX, "_id": str(i+j)}}) + "\n" +
        json.dumps({"train": vec.tolist()}) + "\n"
        for j, vec in enumerate(vectors[i:i+batch_size])
    )
    requests.post(f"{OS_URL}/_bulk", data=bulk, headers={"Content-Type": "application/json"})
    print(f"Indexed {min(i+batch_size, len(vectors))}/{len(vectors)}")
    
# Refresh and force merge to 1 segment
requests.post(f"{OS_URL}/{INDEX}/_refresh")
requests.post(f"{OS_URL}/{INDEX}/_forcemerge?max_num_segments=2&wait_for_completion=true")
requests.get(f"{OS_URL}/{INDEX}/_count").json()

requests.post(f"{OS_URL}/{INDEX}/_flush")
requests.get(f"{OS_URL}/{INDEX}/_segments").json()
```

```
%%bash
export NODE_DIR="/Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/"
ls -la $NODE_DIR
export FOLDER=$(ls --color=never $NODE_DIR | grep -v '^\.' | head -1)
echo $FOLDER  
export INDEX_PATH="${NODE_DIR}${FOLDER}/0/index"
ls -la "$INDEX_PATH"

export INDEX_RAW_BACKUP="/Users/finnrobl/Documents/k-NN-2/index-backups"
# mkdir $INDEX_RAW_BACKUP
# cp -r $INDEX_PATH $INDEX_RAW_BACKUP
echo "INDEX BACKUP (2 segments):"
ls -la $INDEX_RAW_BACKUP/index

# now kill cluster, run reordering script
```

```
%%bash
export NODE_DIR="/Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/"
ls -la $NODE_DIR
export FOLDER=$(ls --color=never $NODE_DIR | grep -v '^\.' | head -1)
echo $FOLDER  
export INDEX_PATH="${NODE_DIR}${FOLDER}/0/index"

VEC_FILES=$(ls "$INDEX_PATH"/*_NativeEngines990KnnVectorsFormat_0.vec | tr '\n' ',')
# remove trailing comma
VEC_FILES=${VEC_FILES%,}
# bp faiss reorder
# VEC_FILES=$(ls "$INDEX_DIR"/*_NativeEngines990KnnVectorsFormat_0.vec 2>/dev/null | tr '\n' ',')
echo "Vecs: $VEC_FILES"

# get faiss files
FAISS_FILES=$(ls "$INDEX_PATH"/*_165_train.faiss | tr '\n' ',')
FAISS_FILES=${FAISS_FILES%,}
echo "FAiss: $FAISS_FILES"

cd /Users/finnrobl/Documents/k-NN-2/vector-reorder
# ./gradlew kmeansReorder -Pvec=$VEC_FILES -Pfaiss=$FAISS_FILES -Pspace=l2 -PefSearch=100 -PefConstruction=100 -Pm=16

echo "After reorder contents:"
ls -la "$INDEX_PATH"

FAISS_FILES=$(ls "$INDEX_PATH"/*_165_train.faiss)
for FAISS_FILE in $FAISS_FILES; do 
    mv "$FAISS_FILE" "${FAISS_FILE}.old"
    mv "${FAISS_FILE%.faiss}_reordered.faiss" "$FAISS_FILE"
done

VEC_FILES=$(ls "$INDEX_PATH"/*_NativeEngines990KnnVectorsFormat_0.vec)
for VEC_FILE in $VEC_FILES; do
    mv "$VEC_FILE" "${VEC_FILE}.old"
    mv "${VEC_FILE%.vec}_reordered.vec" "$VEC_FILE"
done

echo "After replacement contents:"
ls -la "$INDEX_PATH"
```

```
# run kmeans bench
!opensearch-benchmark execute-test --workload-path=/Users/finnrobl/Documents/opensearch-benchmark-workloads/vectorsearch --target-hosts localhost:9200 --workload-params /Users/finnrobl/Documents/opensearch-benchmark-workloads/vectorsearch/params/faiss-sift-128-l2.json --pipeline benchmark-only --test-procedure=search-only --kill-running-processes --results-format=csv
```

```
%%bash
# move the original non-reordered docs back to the cluster before we run bp reordering

export NODE_DIR="/Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/"
ls -la $NODE_DIR
export FOLDER=$(ls --color=never $NODE_DIR | grep -v '^\.' | head -1)
echo $FOLDER  
export INDEX_PATH="${NODE_DIR}${FOLDER}/0/index"
ls -la "$INDEX_PATH"

export RAW_BACKUPS="/Users/finnrobl/Documents/k-NN-2/index-backups"
ls -la "$RAW_BACKUPS/index"

# remove old index with kmeans reordered code
rm -rf "$INDEX_PATH"
cp -r "$RAW_BACKUPS/index" "$INDEX_PATH"

echo "replaced idx:"
ls -la "$INDEX_PATH"
```

```
#run osb on non-reordered cluster to see baseline recall
!opensearch-benchmark execute-test --workload-path=/Users/finnrobl/Documents/opensearch-benchmark-workloads/vectorsearch --target-hosts localhost:9200 --workload-params /Users/finnrobl/Documents/opensearch-benchmark-workloads/vectorsearch/params/faiss-sift-128-l2.json --pipeline benchmark-only --test-procedure=search-only --kill-running-processes --results-format=csv
```

```
%%bash
export NODE_DIR="/Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/"
ls -la $NODE_DIR
export FOLDER=$(ls --color=never $NODE_DIR | grep -v '^\.' | head -1)
echo $FOLDER  
export INDEX_PATH="${NODE_DIR}${FOLDER}/0/index"

VEC_FILES=$(ls "$INDEX_PATH"/*_NativeEngines990KnnVectorsFormat_0.vec | tr '\n' ',')
# remove trailing comma
VEC_FILES=${VEC_FILES%,}
# bp faiss reorder
# VEC_FILES=$(ls "$INDEX_DIR"/*_NativeEngines990KnnVectorsFormat_0.vec 2>/dev/null | tr '\n' ',')
echo "Vecs: $VEC_FILES"

# get faiss files
FAISS_FILES=$(ls "$INDEX_PATH"/*_165_train.faiss | tr '\n' ',')
FAISS_FILES=${FAISS_FILES%,}
echo "FAiss: $FAISS_FILES"

cd /Users/finnrobl/Documents/k-NN-2/vector-reorder
# ./gradlew bpReorder -Pvec=$VEC_FILES -Pfaiss=$FAISS_FILES -Pspace=l2 -PefSearch=100 -PefConstruction=100 -Pm=16

echo "After reorder contents:"
ls -la "$INDEX_PATH"

FAISS_FILES=$(ls "$INDEX_PATH"/*_165_train.faiss)
for FAISS_FILE in $FAISS_FILES; do 
    mv "$FAISS_FILE" "${FAISS_FILE}.old"
    mv "${FAISS_FILE%.faiss}_reordered.faiss" "$FAISS_FILE"
done

VEC_FILES=$(ls "$INDEX_PATH"/*_NativeEngines990KnnVectorsFormat_0.vec)
for VEC_FILE in $VEC_FILES; do
    mv "$VEC_FILE" "${VEC_FILE}.old"
    mv "${VEC_FILE%.vec}_reordered.vec" "$VEC_FILE"
done

echo "After replacement contents:"
ls -la "$INDEX_PATH"
```

```
# run OSB on bp reordered function
!opensearch-benchmark execute-test --workload-path=/Users/finnrobl/Documents/opensearch-benchmark-workloads/vectorsearch --target-hosts localhost:9200 --workload-params /Users/finnrobl/Documents/opensearch-benchmark-workloads/vectorsearch/params/faiss-sift-128-l2.json --pipeline benchmark-only --test-procedure=search-only --kill-running-processes --results-format=csv
```

```
%%bash
# looks good! now save kmeans clustered output

export NODE_DIR="/Users/finnrobl/Documents/k-NN-2/e2e_data/nodes/0/indices/"
ls -la $NODE_DIR
export FOLDER=$(ls --color=never $NODE_DIR | grep -v '^\.' | head -1)
echo $FOLDER  
export INDEX_PATH="${NODE_DIR}${FOLDER}/0/index"
ls -la "$INDEX_PATH"

export BP_REORDERED_BACKUP="/Users/finnrobl/Documents/k-NN-2/bp-reorder-backup"
mkdir $BP_REORDERED_BACKUP
cp -r $INDEX_PATH $BP_REORDERED_BACKUP
echo "BP_REORDERED_BACKUP BACKUP (2 segments):"
ls -la $BP_REORDERED_BACKUP/index
```

