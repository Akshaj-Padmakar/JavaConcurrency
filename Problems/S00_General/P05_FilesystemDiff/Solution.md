# Filesystem Diff — Revision Sheet

> Interview form (e.g. Rubrik). See [Problem.md](Problem.md). Implementation: `FsNode.java` (model),
> `SnapshotWalker.java` (traverse + print), `SnapshotDiff.java` (diff + a self-contained runnable
> demo).

## One-line idea

Every node — file, directory, or symlink — carries a **content hash**. A directory's hash is a hash
of its children's (name, type, hash) triples in sorted order (Merkle tree, same idea as a git tree
object). Two nodes with equal hashes are provably identical, including everything underneath a
directory, without looking at either one further. That single property drives both traversal safety
and diff performance.

## Data model (`FsNode`)

| Field         | Meaning                                                                 |
| -------------- | ------------------------------------------------------------------------- |
| `type`         | `FILE` / `DIRECTORY` / `SYMLINK` / `OTHER` (devices, FIFOs, sockets...) |
| `contentHash`  | SHA-256: file → bytes; dir → sorted children triples; symlink → target string |
| `inodeKey`     | `"dev:ino"` via `unix:ino`/`unix:dev` attributes — hardlink identity, `null` on non-POSIX filesystems |
| `children`     | `TreeMap<String, FsNode>` — sorted, so hashing and diffing are deterministic |

## How the two flagged edge cases are actually handled

**Symlinks** — `SnapshotWalker.buildNode` checks `Files.isSymbolicLink` **before** checking
`isDirectory`, and never recurses into one; it records the target string and stops. This sidesteps
the classic infinite-loop trap (symlink → ancestor directory) entirely, matching `ls -R`/`tree`
default behavior, and makes "did this symlink change" mean "did its target string change," not
"did the thing it points at change."

**Hardlinks** — `readInodeKey` reads `(unix:dev, unix:ino)`; `totalPhysicalSize` walks the tree with
a `seenInodes` set and counts a file's bytes only the first time its inode is seen. Verified this
isn't just theoretical: the demo creates a real hardlink and prints deduped vs. non-deduped totals.

## Diff algorithm

Walk both trees in lockstep by directory. At each level: union the child names (via `TreeSet` for a
deterministic order), then for each name:

- present in one tree only → `ADDED`/`REMOVED` (recursively, if it's a directory)
- type differs between old/new → `TYPE_CHANGED` (deliberately **not** further diffed underneath —
  a file becoming a directory isn't a "modification" of anything in particular)
- `contentHash` equal → **stop, nothing below here changed** (the Merkle prune)
- otherwise recurse (dirs) or report `MODIFIED` (files: size; symlinks: target)

**Rename/move detection** is a post-pass, not part of the main walk: collect every `ADDED`/`REMOVED`
*file* alongside its content hash while diffing, then match removed-hash → added-hash pairs
afterward and re-report matched pairs as a single `MOVED` entry instead of a delete+add. Kept
deliberately simple — first-match pairing, not a full bipartite-matching solve — good enough to
demonstrate the idea without over-engineering it.

## Verified, not just argued

Ran the self-contained `main()` demo (creates two real temp directories via `Files.createTempDirectory`,
including an actual hardlink and symlink, mutates one into the other, cleans up after itself):

```
=== Diff t0 -> t1 ===
ADDED /brand-new.txt
REMOVED /to-be-deleted.txt
MODIFIED /link-to-readme (target readme.txt -> brand-new.txt)
MODIFIED /readme.txt (size 11 -> 20)
MOVED /renamed.txt (was /to-be-renamed.txt)
```
Every expected case fired correctly: a genuinely new file, a genuine deletion, a retargeted symlink,
a content change, and a rename correctly collapsed into `MOVED` instead of showing up as a spurious
delete+add. Physical-size dedup was checked by hand: old snapshot naive sum is 64 bytes, hardlink-
deduped total correctly reports 53 (exactly one 11-byte file not double-counted).

One implementation wrinkle the demo caught: the original `copyRecursively` helper (used only to
seed the two temp snapshots) used `Files.copy`, which does **not** preserve hardlink relationships —
so the "new" snapshot's copy of a hardlinked pair silently became two independent files. Fixed by
tracking already-copied inodes and re-creating the hardlink at the destination
(`Files.createLink`) instead of duplicating the data. Worth remembering generally: a plain recursive
copy is a common way to accidentally "unlink" hardlinks without realizing it.

## What's deliberately NOT handled here (would be the natural next extensions)

- **No parallelism.** Both traversal and diff are single-threaded recursion — the "fan out DFS
  across a bounded worker pool" extension from Problem.md isn't implemented, though the data model
  (independent subtrees, no shared mutable state during a walk) is already parallel-friendly.
  Deliberately kept single-threaded for clarity; this is the most natural place to add the previous
  problems' patterns (bounded concurrency, batched fan-out) if extending this.
- **Plain recursion, not iterative.** Both `buildNode` and `diffRec` recurse per directory level —
  correct, but a pathologically deep tree could exhaust the call stack. An iterative version with an
  explicit stack would remove that risk; not done here to keep the algorithm readable.
- **No streaming.** Both full trees are built in memory before diffing. Fine for a demo/interview
  answer; wouldn't hold up at the billions-of-files scale the Problem.md extensions discuss.
- **No permission-denied handling.** `Files.newDirectoryStream`/`Files.readAttributes` will throw
  on an inaccessible subtree, and nothing here catches it to skip-and-continue.

## 30-second recall

> Every node carries a content hash (Merkle-style: dir hash = hash of sorted children's
> name+type+hash). Equal hash ⇒ provably identical subtree ⇒ skip without recursing — the main
> lever for diff performance at scale. Symlinks are never followed while walking (checked before
> `isDirectory`, avoids the ancestor-cycle trap). Hardlinks are deduped for size accounting via
> `(dev, ino)`, not path. Diff walks both trees in lockstep by directory; a rename is detected as a
> post-pass matching `ADDED`/`REMOVED` files by content hash, not baked into the main walk. Verified
> end-to-end against real temp directories with an actual symlink and hardlink, not just reasoned
> about.
