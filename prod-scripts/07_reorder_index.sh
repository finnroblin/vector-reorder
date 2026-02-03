#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/config.sh"

REORDER_TYPE=${1:?Usage: $0 <kmeans|bp>}

VEC_FILES=""
FAISS_FILES=""

for INDEX_FOLDER in "$NODE_DIR"/*; do
    [ -d "$INDEX_FOLDER" ] || continue
    [[ "$(basename "$INDEX_FOLDER")" == .* ]] && continue
    
    for SHARD in "$INDEX_FOLDER"/*/index; do
        [ -d "$SHARD" ] || continue
        
        if ls "$SHARD"/*_NativeEngines990KnnVectorsFormat_0.vec 1>/dev/null 2>&1; then
            for VEC in "$SHARD"/*_NativeEngines990KnnVectorsFormat_0.vec; do
                VEC_FILES="${VEC_FILES}${VEC},"
            done
            for FAISS in "$SHARD"/*_165_train.faiss; do
                FAISS_FILES="${FAISS_FILES}${FAISS},"
            done
        fi
    done
done

VEC_FILES=${VEC_FILES%,}
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
for INDEX_FOLDER in "$NODE_DIR"/*; do
    [ -d "$INDEX_FOLDER" ] || continue
    [[ "$(basename "$INDEX_FOLDER")" == .* ]] && continue
    
    for SHARD in "$INDEX_FOLDER"/*/index; do
        [ -d "$SHARD" ] || continue
        
        if ls "$SHARD"/*_NativeEngines990KnnVectorsFormat_0.vec 1>/dev/null 2>&1; then
            for FAISS_FILE in "$SHARD"/*_165_train.faiss; do
                mv "$FAISS_FILE" "${FAISS_FILE}.old"
                mv "${FAISS_FILE%.faiss}_reordered.faiss" "$FAISS_FILE"
            done
            
            for VEC_FILE in "$SHARD"/*_NativeEngines990KnnVectorsFormat_0.vec; do
                mv "$VEC_FILE" "${VEC_FILE}.old"
                mv "${VEC_FILE%.vec}_reordered.vec" "$VEC_FILE"
            done
            
            echo "Reordered shard: $SHARD"
            ls -la "$SHARD"
        fi
    done
done
