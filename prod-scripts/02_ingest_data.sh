#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/config.sh"

echo "Creating index and ingesting data..."

python3 << 'EOF'
import h5py
import requests
import json
import os

OS_URL = os.environ["OS_URL"]
INDEX = os.environ["INDEX"]
HDF5_PATH = os.environ["HDF5_PATH"]

requests.delete(f"{OS_URL}/{INDEX}")
requests.put(f"{OS_URL}/{INDEX}", json={
    "settings": {"index": {"knn": True, "number_of_shards": 1, "number_of_replicas": 0, "refresh_interval": "-1"}},
    "mappings": {"properties": {"train": {"type": "knn_vector", "dimension": 128}}}
})

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

requests.post(f"{OS_URL}/{INDEX}/_refresh")
requests.post(f"{OS_URL}/{INDEX}/_forcemerge?max_num_segments=2&wait_for_completion=true")
requests.post(f"{OS_URL}/{INDEX}/_flush")
print(requests.get(f"{OS_URL}/{INDEX}/_count").json())
EOF

echo "Ingest complete."
