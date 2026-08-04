# Multi-Threaded DFS

Design and implement a multi-threaded graph traversal.

Given a directed graph represented as:

```java
Map<Node, List<Node>> graph
```

implement:

```java
void traverse(Node startNode)
```

The traversal should:

- Visit every node reachable from `startNode`.
- Process each reachable node exactly once, even if multiple parents point to it.
- Use a fixed-size thread pool.
- Allow tasks to recursively submit more traversal work.
- Return only after all reachable nodes have finished processing.
- Handle cycles safely.

This does not need to preserve strict DFS ordering. The goal is concurrent graph exploration with DFS-style recursive fan-out.

Think about:

- How to atomically check and mark a node as visited.
- How to know when all dynamically submitted tasks have completed.
- How and when to shut down the executor.
- What should happen if `startNode` is `null`.
