#!/usr/bin/env python3
"""
Generate corrected ground truth neighbors file.

The HDF5 ground truth uses positions in the 'train' array, but OpenSearch
indexed vectors in a different order. This script creates a mapping from
HDF5 positions to OpenSearch _id values, then rewrites the neighbors.

Usage:
    python3 generate_corrected_neighbors.py <hdf5_path> <vec_path> <output_hdf5>
"""

import h5py
import numpy as np
import struct
import sys
from tqdm import tqdm

def load_vec_file(vec_path, dim=128, offset=92):
    """Load all vectors from .vec file."""
    vectors = []
    with open(vec_path, 'rb') as f:
        f.seek(offset)
        while True:
            data = f.read(dim * 4)
            if len(data) < dim * 4:
                break
            vec = struct.unpack(f'{dim}f', data)
            vectors.append(np.array(vec, dtype=np.float32))
    return np.array(vectors)

def build_hdf5_to_docid_mapping(hdf5_train, vec_vectors):
    """Build mapping: hdf5_position -> docid (vec file position)."""
    n = len(hdf5_train)
    mapping = np.full(n, -1, dtype=np.int32)
    
    # Build hash of vec vectors for fast lookup
    # Use first 4 floats as key
    vec_hash = {}
    for docid, vec in enumerate(vec_vectors):
        key = tuple(vec[:4].astype(np.float32))
        if key not in vec_hash:
            vec_hash[key] = []
        vec_hash[key].append((docid, vec))
    
    print("Building HDF5 -> docID mapping...")
    for hdf5_pos in tqdm(range(n)):
        target = hdf5_train[hdf5_pos]
        key = tuple(target[:4].astype(np.float32))
        
        if key in vec_hash:
            for docid, vec in vec_hash[key]:
                if np.allclose(target, vec, atol=0.01):
                    mapping[hdf5_pos] = docid
                    break
    
    found = np.sum(mapping >= 0)
    print(f"Mapped {found}/{n} vectors ({100*found/n:.1f}%)")
    return mapping

def main():
    if len(sys.argv) < 4:
        print(__doc__)
        sys.exit(1)
    
    hdf5_path = sys.argv[1]
    vec_path = sys.argv[2]
    output_path = sys.argv[3]
    
    print(f"Loading HDF5 from {hdf5_path}...")
    with h5py.File(hdf5_path, 'r') as f:
        hdf5_train = f['train'][:]
        hdf5_neighbors = f['neighbors'][:]
        hdf5_distances = f['distances'][:]
        hdf5_test = f['test'][:]
    
    print(f"Loading .vec file from {vec_path}...")
    vec_vectors = load_vec_file(vec_path, dim=hdf5_train.shape[1])
    print(f"Loaded {len(vec_vectors)} vectors")
    
    # Build mapping
    mapping = build_hdf5_to_docid_mapping(hdf5_train, vec_vectors)
    
    # Translate neighbors
    print("Translating neighbors...")
    corrected_neighbors = np.zeros_like(hdf5_neighbors)
    for i in tqdm(range(len(hdf5_neighbors))):
        for j in range(len(hdf5_neighbors[i])):
            hdf5_pos = hdf5_neighbors[i][j]
            docid = mapping[hdf5_pos]
            corrected_neighbors[i][j] = docid
    
    # Write output
    print(f"Writing corrected HDF5 to {output_path}...")
    with h5py.File(output_path, 'w') as f:
        f.create_dataset('train', data=hdf5_train)
        f.create_dataset('test', data=hdf5_test)
        f.create_dataset('neighbors', data=corrected_neighbors)
        f.create_dataset('distances', data=hdf5_distances)
    
    print("Done!")
    
    # Verify
    print("\nVerification:")
    print(f"Original neighbors[0][:5]: {hdf5_neighbors[0][:5]}")
    print(f"Corrected neighbors[0][:5]: {corrected_neighbors[0][:5]}")

if __name__ == '__main__':
    main()
