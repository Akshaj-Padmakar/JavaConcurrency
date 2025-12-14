# S00 | P01 | MultiThreaded DFS.

- Given a Directed/Undirected Graph, we want to visit all the nodes using the Depth first search algorithm using multiple threads.

## Solution.

### Method 1 :

- Spawning new threads for each new DFS, is an ok solution, but this will expload as soon as graph gets larger.
- Spawning new threads required it's own Thread stack which could by default be of size as large as 1 MB.

### Method 2 :

- Keeping a threadPool and submitting runnables to it seems like the best solution.
- We need to keep in mind if 2 nodes are called at the same time from different parent nodes, we need shared memory to ensure multiple DFS for same nodes are not called, since this could explode the DFS runtime way more than [O(n + m), n = number of nodes m = number of edges]
