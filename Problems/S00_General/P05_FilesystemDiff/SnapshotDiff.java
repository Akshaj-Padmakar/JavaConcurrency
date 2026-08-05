package Problems.S00_General.P05_FilesystemDiff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Diffs two {@link FsNode} snapshot trees taken at t0 and t1.
 *
 * Identical subtrees are pruned via the Merkle content hash (a directory whose hash matches is
 * skipped without visiting a single child) -- on a real snapshot where most files are untouched
 * between backups, this is the difference between an O(N) walk and touching almost nothing.
 *
 * As a bonus pass, ADDED/REMOVED file pairs with identical content hash are re-reported as MOVED,
 * instead of a spurious delete+add for a simple rename.
 */
public class SnapshotDiff {

    public enum ChangeType { ADDED, REMOVED, MODIFIED, TYPE_CHANGED, MOVED }

    public static final class Change {
        public final ChangeType type;
        public final String path;
        public final String detail;

        Change(ChangeType type, String path, String detail) {
            this.type = type;
            this.path = path;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return detail == null ? type + " " + path : type + " " + path + " (" + detail + ")";
        }
    }

    private static final class PathNode {
        final String path;
        final FsNode node;

        PathNode(String path, FsNode node) {
            this.path = path;
            this.node = node;
        }
    }

    public List<Change> diff(FsNode oldRoot, FsNode newRoot) {
        List<Change> changes = new ArrayList<>();
        List<PathNode> addedFiles = new ArrayList<>();
        List<PathNode> removedFiles = new ArrayList<>();
        diffRec(oldRoot, newRoot, "", changes, addedFiles, removedFiles);
        detectMoves(changes, addedFiles, removedFiles);
        return changes;
    }

    private void diffRec(FsNode oldNode, FsNode newNode, String path, List<Change> changes,
            List<PathNode> addedFiles, List<PathNode> removedFiles) {
        if (oldNode == null && newNode == null) {
            return;
        }
        if (oldNode == null) {
            recordAdded(newNode, path, changes, addedFiles);
            return;
        }
        if (newNode == null) {
            recordRemoved(oldNode, path, changes, removedFiles);
            return;
        }
        if (oldNode.getType() != newNode.getType()) {
            changes.add(new Change(ChangeType.TYPE_CHANGED, path, oldNode.getType() + " -> " + newNode.getType()));
            return; // don't try to diff further under a type change
        }
        if (oldNode.getContentHash().equals(newNode.getContentHash())) {
            return; // Merkle prune: identical (sub)tree, nothing below here changed
        }

        switch (oldNode.getType()) {
            case DIRECTORY:
                Set<String> names = new TreeSet<>();
                names.addAll(oldNode.getChildren().keySet());
                names.addAll(newNode.getChildren().keySet());
                for (String name : names) {
                    diffRec(oldNode.getChildren().get(name), newNode.getChildren().get(name),
                            path + "/" + name, changes, addedFiles, removedFiles);
                }
                break;
            case FILE:
                changes.add(new Change(ChangeType.MODIFIED, path,
                        "size " + oldNode.getSize() + " -> " + newNode.getSize()));
                break;
            case SYMLINK:
                changes.add(new Change(ChangeType.MODIFIED, path,
                        "target " + oldNode.getLinkTarget() + " -> " + newNode.getLinkTarget()));
                break;
            case OTHER:
                changes.add(new Change(ChangeType.MODIFIED, path, "special file changed"));
                break;
        }
    }

    private void recordAdded(FsNode node, String path, List<Change> changes, List<PathNode> addedFiles) {
        changes.add(new Change(ChangeType.ADDED, path, null));
        if (node.getType() == FsNode.Type.FILE) {
            addedFiles.add(new PathNode(path, node));
        } else if (node.getType() == FsNode.Type.DIRECTORY) {
            for (Map.Entry<String, FsNode> e : node.getChildren().entrySet()) {
                recordAdded(e.getValue(), path + "/" + e.getKey(), changes, addedFiles);
            }
        }
    }

    private void recordRemoved(FsNode node, String path, List<Change> changes, List<PathNode> removedFiles) {
        changes.add(new Change(ChangeType.REMOVED, path, null));
        if (node.getType() == FsNode.Type.FILE) {
            removedFiles.add(new PathNode(path, node));
        } else if (node.getType() == FsNode.Type.DIRECTORY) {
            for (Map.Entry<String, FsNode> e : node.getChildren().entrySet()) {
                recordRemoved(e.getValue(), path + "/" + e.getKey(), changes, removedFiles);
            }
        }
    }

    private void detectMoves(List<Change> changes, List<PathNode> addedFiles, List<PathNode> removedFiles) {
        Map<String, PathNode> removedByHash = new HashMap<>();
        for (PathNode r : removedFiles) {
            removedByHash.putIfAbsent(r.node.getContentHash(), r); // first match only -- keep it simple
        }

        List<String[]> moves = new ArrayList<>(); // {oldPath, newPath}
        Set<String> usedRemovedPaths = new HashSet<>();
        for (PathNode a : addedFiles) {
            PathNode r = removedByHash.get(a.node.getContentHash());
            if (r != null && !usedRemovedPaths.contains(r.path)) {
                usedRemovedPaths.add(r.path);
                moves.add(new String[] { r.path, a.path });
            }
        }
        if (moves.isEmpty()) {
            return;
        }

        Set<String> removedPaths = new HashSet<>();
        Set<String> addedPaths = new HashSet<>();
        for (String[] m : moves) {
            removedPaths.add(m[0]);
            addedPaths.add(m[1]);
        }
        changes.removeIf(c -> (c.type == ChangeType.ADDED && addedPaths.contains(c.path))
                || (c.type == ChangeType.REMOVED && removedPaths.contains(c.path)));
        for (String[] m : moves) {
            changes.add(new Change(ChangeType.MOVED, m[1], "was " + m[0]));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Self-contained demo: builds two temp snapshots (with a symlink and a hardlink), mutates
    // one into the other, then walks + diffs them.
    // ---------------------------------------------------------------------------------------
    public static void main(String[] args) throws IOException {
        Path oldSnap = Files.createTempDirectory("snapshot_old_");
        Path newSnap = Files.createTempDirectory("snapshot_new_");
        try {
            buildOldSnapshot(oldSnap);
            buildNewSnapshotFrom(oldSnap, newSnap);

            SnapshotWalker walker = new SnapshotWalker();
            FsNode oldRoot = walker.walk(oldSnap);
            FsNode newRoot = walker.walk(newSnap);

            System.out.println("=== Snapshot t0 (" + oldSnap + ") ===");
            walker.print(oldRoot);
            System.out.println("Physical size (hardlink-deduped): " + walker.totalPhysicalSize(oldRoot) + " bytes");

            System.out.println();
            System.out.println("=== Snapshot t1 (" + newSnap + ") ===");
            walker.print(newRoot);
            System.out.println("Physical size (hardlink-deduped): " + walker.totalPhysicalSize(newRoot) + " bytes");

            System.out.println();
            System.out.println("=== Diff t0 -> t1 ===");
            List<Change> changes = new SnapshotDiff().diff(oldRoot, newRoot);
            changes.sort(Comparator.comparing((Change c) -> c.type).thenComparing(c -> c.path));
            for (Change c : changes) {
                System.out.println(c);
            }
        } finally {
            deleteRecursively(oldSnap);
            deleteRecursively(newSnap);
        }
    }

    private static void buildOldSnapshot(Path root) throws IOException {
        Files.writeString(root.resolve("readme.txt"), "hello world", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("to-be-deleted.txt"), "gone soon", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("to-be-renamed.txt"), "same content, new path", StandardCharsets.UTF_8);

        Path sub = Files.createDirectory(root.resolve("sub"));
        Path original = Files.writeString(sub.resolve("original.txt"), "shared data", StandardCharsets.UTF_8);

        try {
            Files.createLink(sub.resolve("hardlink-to-original.txt"), original); // same inode, +1 link count
        } catch (IOException | UnsupportedOperationException ex) {
            System.out.println("(hardlinks unsupported on this filesystem, skipping that part of the demo)");
        }

        try {
            Files.createSymbolicLink(root.resolve("link-to-readme"), Paths.get("readme.txt"));
        } catch (IOException | UnsupportedOperationException ex) {
            System.out.println("(symlinks unsupported on this filesystem, skipping that part of the demo)");
        }
    }

    /** Copies oldSnap -> newSnap, then applies exactly the changes we want the diff to report. */
    private static void buildNewSnapshotFrom(Path oldSnap, Path newSnap) throws IOException {
        copyRecursively(oldSnap, newSnap);

        Files.deleteIfExists(newSnap.resolve("to-be-deleted.txt"));
        Files.move(newSnap.resolve("to-be-renamed.txt"), newSnap.resolve("renamed.txt"));
        Files.writeString(newSnap.resolve("readme.txt"), "hello world, updated", StandardCharsets.UTF_8);
        Files.writeString(newSnap.resolve("brand-new.txt"), "wasn't here before", StandardCharsets.UTF_8);

        Path link = newSnap.resolve("link-to-readme");
        if (Files.isSymbolicLink(link)) {
            Files.delete(link);
            Files.createSymbolicLink(link, Paths.get("brand-new.txt")); // retarget the symlink
        }
    }

    private static void copyRecursively(Path src, Path dst) throws IOException {
        copyRecursively(src, dst, new HashMap<>());
    }

    /** Preserves hardlink relationships across the copy: a second link to an already-copied inode
     *  becomes a new hardlink at the destination, not an independent copy. */
    private static void copyRecursively(Path src, Path dst, Map<Object, Path> copiedInodes) throws IOException {
        if (Files.isSymbolicLink(src)) {
            Files.createSymbolicLink(dst, Files.readSymbolicLink(src));
        } else if (Files.isDirectory(src)) {
            Files.createDirectories(dst);
            try (var stream = Files.newDirectoryStream(src)) {
                for (Path child : stream) {
                    copyRecursively(child, dst.resolve(child.getFileName()), copiedInodes);
                }
            }
        } else {
            Object inodeKey = readInodeKeyOrNull(src);
            Path existing = inodeKey == null ? null : copiedInodes.get(inodeKey);
            if (existing != null) {
                Files.createLink(dst, existing); // re-create the hardlink, don't duplicate the data
            } else {
                Files.copy(src, dst);
                if (inodeKey != null) {
                    copiedInodes.put(inodeKey, dst);
                }
            }
        }
    }

    private static Object readInodeKeyOrNull(Path path) {
        try {
            return Files.getAttribute(path, "unix:dev") + ":" + Files.getAttribute(path, "unix:ino");
        } catch (UnsupportedOperationException | IOException ex) {
            return null;
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isDirectory(root) && !Files.isSymbolicLink(root)) {
            try (var stream = Files.newDirectoryStream(root)) {
                for (Path child : stream) {
                    deleteRecursively(child);
                }
            }
        }
        Files.delete(root);
    }
}
