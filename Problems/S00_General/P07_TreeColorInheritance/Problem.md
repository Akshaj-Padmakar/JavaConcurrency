# Tree Color Inheritance

> Interview form: "hierarchical value inheritance" — the same underlying shape as CSS property
> inheritance, filesystem ACL inheritance, or a config-override hierarchy: each node's effective
> value is either its own explicit value, or inherited from the nearest ancestor that has one.

## Problem

Maintain a tree where every node has a color:

- A node starts with **no explicit color** and, in that state, **inherits its parent's effective
  color**.
- Assigning a color to a node **explicitly overrides** anything inherited — that node "opts out"
  of tracking its ancestors from then on.

```java
void addNode(int id, int parentId);       // parent assumed to already exist
void assignColor(int id, String color);   // explicit override
String getColor(int id);                  // the EFFECTIVE color (own, or inherited)
void updateParent(int id, int newParentId);
```

### Worked example

```
Root -> A -> {A1, A2}
     -> B -> {B1, B2}
     -> C
```
Assign `RED` to `A`: `A1` and `A2` become `RED` too (inherited). Now explicitly assign `GREEN` to
`A1`: it keeps `GREEN` even if `A`'s color changes again later — `A1` opted out.

## Requirements

- `getColor` should be fast — this models a **read-heavy** system where lookups vastly outnumber
  color changes.
- `assignColor`/`updateParent` may do more work, but that work must be **bounded by the affected
  subtree**, not the whole tree — a node (and everything under it) that already has its own
  explicit color must be left alone by an ancestor's change.

## Points to Ponder

- **Two fields, not one.** Why does each node need to track *both* "my own explicit color" and
  "my current effective color"? What breaks if you only store one?
- **Push vs. pull.** The requirements above push you toward eager push-down on write (`O(1)`
  reads, bounded writes). What would the alternative — computing effective color lazily by walking
  up parents on every `getColor` — cost instead, and when would *that* be the better trade-off?
- **The one rule that makes push-down correct, not just fast.** A write must stop descending the
  instant it reaches a node with its own explicit color. What state does the implementation need
  to make that check `O(1)` per node instead of re-deriving it?
- **`updateParent` is really two operations.** Moving a node can change (a) its own effective
  color, and (b) potentially every un-explicit-colored descendant's, transitively. What if the
  moved node itself has an explicit color — does the move need to touch anything below it at all?
- **Cycle safety.** What has to be checked before actually moving a node under a new parent?

## At real scale — the graph doesn't fit in memory

Say the tree has millions of nodes and very wide fanout at some levels, but a **bounded depth**
(e.g. never more than ~10 levels root-to-leaf), and you can only hold a small working set in memory
at once.

- The eager push-down algorithm is still correct here — the question is how to run it without
  materializing the whole affected subtree at once. What does a **batched, breadth-first** version
  look like, where each step fetches one bounded frontier of children (e.g. `parent_id IN (...)`
  against a backing store) instead of recursing freely?
- **Schema.** What's a reasonable adjacency-list schema for this (`id`, `parent_id`,
  `assigned_color` nullable, `effective_color`), and what should be indexed, given "fetch children
  of X" is the one query the push-down runs over and over?
- **Why not do the walk inside the database** with a recursive query? What does forcing the walk
  into application code buy you that you'd lose otherwise?
- **Parallelizing the push-down.** Once a node's new effective color is settled, its
  un-explicit-colored children are independent of each other and of every other branch at the same
  level — no shared state between them. What's the concurrency pattern for fanning that out across
  a bounded worker pool, and how do you know when the whole walk has actually finished (a plain
  `ExecutorService.shutdown()` + `awaitTermination()` isn't enough when child tasks are discovered
  and submitted dynamically while the walk is still in flight)?
- **Concurrent, overlapping structural changes.** What could go wrong if a color assignment on one
  ancestor and a `updateParent` call affecting one of its descendants happen at the same time, on
  overlapping parts of the tree? Is a single global lock an acceptable answer, or does it defeat
  the point of parallelizing in the first place?
