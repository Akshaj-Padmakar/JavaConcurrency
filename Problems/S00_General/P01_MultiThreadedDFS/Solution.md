# Solution Notes

Last-day revision checklist:

- This is not strict DFS ordering. It is parallel graph exploration with DFS-style recursive fan-out.
- The key invariant is exactly-once processing: atomically mark a node visited before submitting work for it.
- Mark the start node before submitting the first task; otherwise a cycle back to the start can process it twice.
- Since child tasks are discovered dynamically, `shutdown()` plus `awaitTermination()` is not enough by itself.
- Use reference counting: increment before each task submission, decrement in `finally`, and count down the latch when the active count reaches zero.
- Keep the visited check and task submission order tight: `markVisited(child)` must happen before `activeTask.incrementAndGet()` and `execute(...)`.
- Always decrement active task count in `finally`, even when node work throws.
- Handle `null` graph, empty graph, `null` start node, duplicate edges, shared children, missing adjacency lists, and cycles.
- This implementation is one-shot because the executor is shut down and the latch cannot be reset after traversal.
- A cleaner production version can use `ConcurrentHashMap.newKeySet()` and `visited.add(node)` for atomic check-and-mark.
