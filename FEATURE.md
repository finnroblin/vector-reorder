The idea of this module is to import lucene and k-NN modules , with a main function.

It should use the constructs in MemoryOptimizedSearcher (KNN concept) to read a preexisting .vec file, perform a k-means clustering step, and then replace the .vec file with a new .vec file that's sorted by (cluster_id, distance_of_this_vector_to_cluster_id). The idea there is to improve spatial locality for k-NN search.

The interface will be take in (optional<.faiss file location>, .vec file location), and it should output a (optional<new .faiss file loc>, new .vec file loc). 

Write a boilerplate method with the goal that I can pass it an existing .vec file and it will print out the first 10 vectors.
