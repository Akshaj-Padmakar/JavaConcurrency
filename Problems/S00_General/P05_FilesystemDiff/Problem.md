# Filesystem Diff

> Interview form (e.g. Rubrik): _"Filesystem Snapshot Diff"_ — walk a snapshot tree and diff two of
> them. Very on-brand for a backup/data-protection company: efficient, correct snapshot diffing is
> close to their core product.

## Problem

Part 1 — **traverse and print** the contents of a filesystem snapshot starting from a root
directory, similar to `ls -R` / `tree`.

Part 2 — **diff two snapshots** taken at `t0` and `t1` to detect what changed: added, removed, and
modified files/directories.

### Edge cases called out up front

- **Symlinks** — a directory symlink can point back at an ancestor (or itself); naive recursive
  traversal loops forever. Also: what does "changed" mean for a symlink — its target, not its
  target's content.
- **Hardlinks** — multiple directory entries can point at the *same* underlying data. Doesn't break
  traversal, but breaks anything that aggregates (e.g. total size) unless you dedupe by identity, not
  by path.

## Clarifying questions worth asking before coding

- Live filesystem walk, or an already-serialized snapshot/metadata format? (Changes the problem from
  "algorithm" to "systems design.")
- What counts as "changed" — content bytes, metadata (mtime/owner/permissions), or either?
- Scale — thousands of files, or billions? Decides whether "load both trees into memory" is viable.
- Should renames/moves be detected as such, or is delete-old-path + add-new-path acceptable?

## Requirements

- Print a real directory tree without following symlinks into other directories (avoids the cycle
  trap; matches `ls -R`/`tree` default behavior).
- Diff must never conflate a hardlink's *path* disappearing with its *data* disappearing.
- Diff should skip unchanged subtrees entirely rather than re-comparing every file underneath one
  that didn't change (matters a lot at real scale — most files between two backups are untouched).

## Points to Ponder / extensions

- **Merkle-style pruning.** If every directory carries a hash of its own contents (children's names,
  types, and hashes), two directories with equal hashes are provably identical without visiting a
  single child. This is the single biggest lever for making diff scale — same idea as a git tree
  object, or how ZFS/btrfs incremental send avoids re-scanning unchanged data.
- **Rename/move detection.** A naive diff reports a moved file as delete+add. Matching added and
  removed entries by content hash (or by `(device, inode)` if the snapshot mechanism preserves inode
  numbers across snapshots — true for ZFS/btrfs COW snapshots, not necessarily true for independently
  generated dumps) turns that into a single MOVED entry.
- **Hardlink accounting.** One of N hardlinks to a file disappearing — is that a delete? The path is
  gone, the data isn't. Matters a lot to a company whose product is deduplicated backup storage.
- **Parallelizing the walk.** Fan out DFS across a bounded worker pool, one subtree per task —
  directly related to the producer/consumer and bounded-concurrency patterns elsewhere in this repo.
- **Streaming instead of materializing both trees.** If both snapshots' metadata is stored sorted by
  path, stream-merge them like Unix `diff` on two sorted files — never hold more than the current
  frontier in memory. Matters at billions-of-files scale.
- **Incremental tracking vs. brute-force diffing.** A real system would rather track changes as they
  happen (filesystem journal, `inotify`/`fanotify`, `zfs diff`-style built-in tracking) than
  recompute a full two-snapshot diff from scratch every time. When is brute-force diff still the
  right fallback?
- **Security angle.** A sudden burst of mass file modification in a diff is a classic ransomware
  signature — diffing isn't just for efficient backup, it can drive anomaly detection.
- Other edges: permission-denied subtrees mid-walk, special files (devices/FIFOs/sockets), very deep
  nesting (stack overflow risk in naive recursion), empty directories appearing/disappearing.
