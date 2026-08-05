# Tree Color Inheritance — Revision Sheet

> See [Problem.md](Problem.md). Implementation: `TreeNode.java` (model), `TreeColorStore.java`
> (sequential + parallel push-down), `Test/TreeColorStoreTest.java`.

## One-line idea

Store the effective color, don't recompute it on read. `getColor` is a plain field access —
`O(1)`. The cost moves to the write side: `assignColor`/`updateParent` do a bounded breadth-first
push-down that **stops the instant it reaches a node with its own explicit color** — that node (and
everything under it) already opted out and is unaffected by anything above it.

## State model

| Field on `TreeNode`  | Meaning                                                             |
| ---------------------- | ---------------------------------------------------------------------- |
| `assignedColor`        | `null` unless this node was explicitly colored — the "opted out" flag |
| `effectiveColor`       | what `getColor()` returns; always non-null once the node exists      |
| `childrenOf` (in the store) | `parentId -> List<childId>`, maintained **incrementally**, not rebuilt per call |

The two-field model is the whole trick: without a separate `assignedColor`, there's no way to tell
"this node's color happens to equal its parent's because it inherited it" apart from "this node was
deliberately set to that exact color" — and the push-down needs that distinction to know where to
stop.

## Why `childrenOf` must be incremental, not rebuilt per push

An earlier draft of this rebuilt a `parentId -> children` index from scratch inside every
push-down call. That makes every write `O(total tree size)` regardless of how small the actually
affected subtree is — silently defeating the entire "bounded write" requirement. `addNode` and
`updateParent` now maintain `childrenOf` incrementally instead, so a push-down's cost is genuinely
proportional to the number of nodes it touches, not the size of the whole tree. Verified directly:
`testAssignColorTouchedCountIsBounded` builds a root with 20 children, gives 15 of them their own
explicit color, and asserts the next root-level `assignColor` touches exactly 6 nodes (the root +
the 5 that didn't opt out) — not 21.

## The parallel push-down

Once a node's effective color is settled, its un-explicit-colored children are independent of each
other — no shared state between sibling subtrees. `assignColorParallel` fans the same BFS out
across a bounded `ExecutorService`, reusing the active-count-`AtomicInteger` + `CountDownLatch`
idiom already established in `P01_MultiThreadedDFS` and `P05_FilesystemDiff`'s parallel walk:
`shutdown()` + `awaitTermination()` alone doesn't work here because child tasks are discovered and
submitted dynamically while the walk is still running; the increment has to happen **before**
`execute()`, not after, or a parent can decrement to zero and fire the latch while a child task is
still in flight.

**Verified, not just argued:** `testParallelAssignColorMatchesSequentialOnLargeRandomTree` builds
two structurally identical 4,000-node random trees (same seed) with ~10% of nodes pre-assigned
their own color, runs `assignColor` sequentially on one and `assignColorParallel` on the other with
the same target node and color, and asserts every single node's effective color matches between the
two, plus the touched-count matches. Ran the whole suite 15 times in a loop — 15/15 clean.

`nodes`/`childrenOf` are `ConcurrentHashMap`s (not plain `HashMap`) specifically so this holds up —
even though no two threads ever write the *same* node during one push (each node has exactly one
parent, so it's discovered and processed by exactly one task), the maps themselves are read
concurrently by many worker threads, and a plain `HashMap` doesn't guarantee those reads see prior
writes without extra synchronization.

## Scope — what this deliberately does NOT implement

- **No real batched/DB-backed traversal.** The Problem.md "graph doesn't fit in memory" extension
  describes fetching children in bounded batches from a backing store instead of an in-memory map.
  This implementation's BFS already processes one bounded frontier at a time in principle, but it's
  still backed by an in-memory `ConcurrentHashMap`, not a paginated DB query — the algorithmic
  shape carries over directly, the I/O layer doesn't.
- **No protection against concurrent, overlapping structural operations.** `assignColorParallel` is
  safe to call when nothing else is concurrently mutating the tree. Two operations racing on
  overlapping subtrees (e.g. a color assignment on an ancestor racing an `updateParent` moving one
  of its descendants) is a genuinely harder problem — per-subtree locking or an optimistic
  versioning scheme, not attempted here. `getColor()` calls concurrent with an in-progress push are
  fine (never throw, never see a torn value, thanks to `volatile` fields on `TreeNode`) but may
  return a value that's about to change — treated as acceptable eventual consistency, not a bug, in
  this scope.

## 30-second recall

> `effectiveColor` stored, not recomputed — `O(1)` reads. Writes are a BFS from the changed node
> that stops descending at any node with its own `assignedColor` (the "opted out" boundary) — that
> single rule is what makes the write bounded *and* correct, not just fast. `childrenOf` has to be
> maintained incrementally or the "bounded write" claim is a lie. Parallel version fans the BFS out
> across a bounded pool using the same active-count+latch idiom as the repo's other parallel-walk
> problems; verified against the sequential version on a 4,000-node random tree, exact match,
> 15/15 runs. Explicitly out of scope: real paginated DB-backed traversal, and safety against
> concurrent overlapping structural mutations.
