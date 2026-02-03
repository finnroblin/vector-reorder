#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/config.sh"

REORDER_TYPE=${1:?Usage: $0 <kmeans|bp>}

FOLDER=$(ls --color=never "$NODE_DIR" | grep -v '^\.' | head -1)
INDEX_PATH="${NODE_DIR}/${FOLDER}/0/index"

VEC_FILES=$(ls "$INDEX_PATH"/*_NativeEngines990KnnVectorsFormat_0.vec | tr '\n' ',')
VEC_FILES=${VEC_FILES%,}

FAISS_FILES=$(ls "$INDEX_PATH"/*_165_train.faiss | tr '\n' ',')
FAISS_FILES=${FAISS_FILES%,}

echo "Running $REORDER_TYPE reorder..."
cd "$VECTOR_REORDER_DIR"

if [ "$REORDER_TYPE" = "kmeans" ]; then
    ./gradlew kmeansReorder -Pvec="$VEC_FILES" -Pfaiss="$FAISS_FILES" -Pspace=l2 -PefSearch=100 -PefConstruction=100 -Pm=16
elif [ "$REORDER_TYPE" = "bp" ]; then
    ./gradlew bpReorder -Pvec="$VEC_FILES" -Pfaiss="$FAISS_FILES" -Pspace=l2 -PefSearch=100 -PefConstruction=100 -Pm=16
else
    echo "Unknown reorder type: $REORDER_TYPE"
    exit 1
fi

# Swap reordered files into place
for FAISS_FILE in $(ls "$INDEX_PATH"/*_165_train.faiss); do
    mv "$FAISS_FILE" "${FAISS_FILE}.old"
    mv "${FAISS_FILE%.faiss}_reordered.faiss" "$FAISS_FILE"
done

for VEC_FILE in $(ls "$INDEX_PATH"/*_NativeEngines990KnnVectorsFormat_0.vec); do
    mv "$VEC_FILE" "${VEC_FILE}.old"
    mv "${VEC_FILE%.vec}_reordered.vec" "$VEC_FILE"
done

echo "Reorder complete:"
ls -la "$INDEX_PATH"
