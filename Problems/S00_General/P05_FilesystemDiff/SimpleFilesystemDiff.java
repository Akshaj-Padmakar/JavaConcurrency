package Problems.S00_General.P05_FilesystemDiff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Interview-realistic version -- what's actually writeable in ~45-60 minutes.
 *
 * No content hashing, no Merkle-tree pruning, no move detection: just Files.walkFileTree (which
 * ALREADY refuses to descend into symlinked directories unless you explicitly pass
 * FileVisitOption.FOLLOW_LINKS -- the classic symlink-cycle trap is handled by the standard
 * library, not something you hand-roll), collecting a flat, sorted Map<relativePath, FileInfo>.
 * "Changed" is decided by cheap metadata (size + mtime), not by reading file content -- the same
 * quick-check heuristic rsync uses. Diff is then just a comparison of two maps.
 *
 * See SnapshotWalker.java/SnapshotDiff.java for the fuller version (content hashing, Merkle
 * pruning, rename detection) if you have more than an hour.
 */
public class SimpleFilesystemDiff {

    public record FileInfo(String type, long size, long mtimeMillis, String symlinkTarget) {}

    /** Walks root into a flat, sorted map of relative-path -> metadata. */
    public static Map<String, FileInfo> snapshot(Path root) throws IOException {
        Map<String, FileInfo> out = new TreeMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root)) {
                    out.put(rel(root, dir), new FileInfo("DIR", 0, attrs.lastModifiedTime().toMillis(), null));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String type = attrs.isSymbolicLink() ? "SYMLINK" : attrs.isRegularFile() ? "FILE" : "OTHER";
                String target = attrs.isSymbolicLink() ? Files.readSymbolicLink(file).toString() : null;
                out.put(rel(root, file), new FileInfo(type, attrs.size(), attrs.lastModifiedTime().toMillis(), target));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                System.err.println("skipping unreadable: " + file + " (" + exc + ")");
                return FileVisitResult.CONTINUE; // one bad entry shouldn't abort the whole walk
            }
        });
        return out;
    }

    private static String rel(Path root, Path p) {
        return root.relativize(p).toString();
    }

    /**
     * Parallel version of snapshot(): one task per directory, fanning out to a bounded pool.
     * Same reference-counting shutdown idiom as P01_MultiThreadedDFS's Solution.md -- shutdown()
     * + awaitTermination() alone doesn't work here because child tasks are submitted dynamically
     * while the walk is still running.
     */
    public static Map<String, FileInfo> snapshotParallel(Path root, int poolSize) throws InterruptedException {
        ConcurrentHashMap<String, FileInfo> out = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        AtomicInteger active = new AtomicInteger(1); // counts the root task itself
        CountDownLatch done = new CountDownLatch(1);

        pool.execute(() -> walkDirParallel(root, root, out, pool, active, done));

        done.await();
        pool.shutdown();
        return new TreeMap<>(out); // re-sort now that we're single-threaded again
    }

    private static void walkDirParallel(Path root, Path dir, ConcurrentHashMap<String, FileInfo> out,
            ExecutorService pool, AtomicInteger active, CountDownLatch done) {
        try {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path child : stream) {
                    try {
                        // NOFOLLOW_LINKS: without walkFileTree doing it for us, WE must stop a
                        // symlinked directory from being descended into -- otherwise this is the
                        // classic cycle trap again, just multi-threaded this time.
                        BasicFileAttributes attrs =
                                Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        String relPath = rel(root, child);

                        if (attrs.isSymbolicLink()) {
                            String target = Files.readSymbolicLink(child).toString();
                            out.put(relPath, new FileInfo("SYMLINK", attrs.size(), attrs.lastModifiedTime().toMillis(), target));
                        } else if (attrs.isDirectory()) {
                            out.put(relPath, new FileInfo("DIR", 0, attrs.lastModifiedTime().toMillis(), null));
                            active.incrementAndGet(); // MUST happen before execute(), not after
                            pool.execute(() -> walkDirParallel(root, child, out, pool, active, done));
                        } else {
                            String type = attrs.isRegularFile() ? "FILE" : "OTHER";
                            out.put(relPath, new FileInfo(type, attrs.size(), attrs.lastModifiedTime().toMillis(), null));
                        }
                    } catch (IOException ex) {
                        System.err.println("skipping unreadable: " + child + " (" + ex + ")");
                    }
                }
            }
        } catch (IOException ex) {
            System.err.println("skipping unreadable dir: " + dir + " (" + ex + ")");
        } finally {
            // Always runs, even on the exception path above -- an unreadable directory must not
            // leave the active count stuck above zero forever.
            if (active.decrementAndGet() == 0) {
                done.countDown();
            }
        }
    }

    /** ls -R style flat listing (sorted, since the map is a TreeMap). */
    public static void printTree(Map<String, FileInfo> snapshot) {
        for (Map.Entry<String, FileInfo> e : snapshot.entrySet()) {
            FileInfo i = e.getValue();
            String suffix = switch (i.type()) {
                case "DIR" -> "/";
                case "SYMLINK" -> " -> " + i.symlinkTarget();
                case "FILE" -> " (" + i.size() + " bytes)";
                default -> "";
            };
            System.out.println(e.getKey() + suffix);
        }
    }

    /**
     * Straight map diff. Deliberately NOT a blind record.equals() across all four fields -- what
     * counts as "changed" differs by type: a FILE's mtime is a real (cheap) proxy for content
     * change, but a DIRECTORY's mtime just reflects "some child was added/removed," which the
     * diff already reports separately as its own ADDED/REMOVED line -- comparing it here would
     * just be noise. A SYMLINK only "changes" if its target string changes.
     */
    public static void diff(Map<String, FileInfo> oldSnap, Map<String, FileInfo> newSnap) {
        Set<String> allPaths = new TreeSet<>();
        allPaths.addAll(oldSnap.keySet());
        allPaths.addAll(newSnap.keySet());

        for (String path : allPaths) {
            FileInfo oldInfo = oldSnap.get(path);
            FileInfo newInfo = newSnap.get(path);
            if (oldInfo == null) {
                System.out.println("ADDED        " + path);
            } else if (newInfo == null) {
                System.out.println("REMOVED      " + path);
            } else if (!oldInfo.type().equals(newInfo.type())) {
                System.out.println("TYPE_CHANGED " + path + "  " + oldInfo.type() + " -> " + newInfo.type());
            } else if (isMeaningfullyDifferent(oldInfo, newInfo)) {
                System.out.println("MODIFIED     " + path + "  " + oldInfo + " -> " + newInfo);
            }
        }
    }

    private static boolean isMeaningfullyDifferent(FileInfo a, FileInfo b) {
        return switch (a.type()) {
            case "FILE" -> a.size() != b.size() || a.mtimeMillis() != b.mtimeMillis();
            case "SYMLINK" -> !java.util.Objects.equals(a.symlinkTarget(), b.symlinkTarget());
            default -> false; // DIR / OTHER: presence + type is all we track here
        };
    }

    /**
     * The hardlink edge case, answered as its own small bonus method instead of folded into the
     * core diff -- BasicFileAttributes.fileKey() is a JVM/OS-provided object that already encodes
     * (device, inode) identity with correct equals()/hashCode(); no need to hand-read
     * "unix:dev"/"unix:ino" attribute views yourself. Returns null on filesystems that don't
     * support it (e.g. some non-POSIX setups) -- in that case every entry is just counted as unique.
     */
    public static long totalPhysicalSize(Path root) throws IOException {
        Set<Object> seenKeys = new HashSet<>();
        long[] total = { 0 };
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    Object key = attrs.fileKey();
                    if (key == null || seenKeys.add(key)) {
                        total[0] += attrs.size();
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    // ---------------------------------------------------------------------------------------
    // Self-contained demo -- same shape as SnapshotDiff.java's, including a real hardlink and a
    // SELF-REFERENTIAL symlink (a directory symlink pointing at itself) to prove walkFileTree
    // really doesn't loop forever on the classic cycle trap.
    // ---------------------------------------------------------------------------------------
    public static void main(String[] args) throws IOException, InterruptedException {
        Path oldSnap = Files.createTempDirectory("simple_snapshot_old_");
        Path newSnap = Files.createTempDirectory("simple_snapshot_new_");
        try {
            buildOldSnapshot(oldSnap);
            buildNewSnapshotFrom(oldSnap, newSnap);

            Map<String, FileInfo> oldMap = snapshot(oldSnap);
            Map<String, FileInfo> newMap = snapshot(newSnap);

            System.out.println("=== Snapshot t0 (" + oldSnap + ") ===");
            printTree(oldMap);
            System.out.println("Physical size (hardlink-deduped): " + totalPhysicalSize(oldSnap) + " bytes");

            System.out.println();
            System.out.println("=== Snapshot t1 (" + newSnap + ") ===");
            printTree(newMap);
            System.out.println("Physical size (hardlink-deduped): " + totalPhysicalSize(newSnap) + " bytes");

            System.out.println();
            System.out.println("=== Diff t0 -> t1 ===");
            diff(oldMap, newMap);

            System.out.println();
            System.out.println("=== Parallel walk cross-check ===");
            Map<String, FileInfo> oldMapParallel = snapshotParallel(oldSnap, 4);
            Map<String, FileInfo> newMapParallel = snapshotParallel(newSnap, 4);
            boolean oldMatches = oldMap.equals(oldMapParallel);
            boolean newMatches = newMap.equals(newMapParallel);
            System.out.println("t0: sequential == parallel ? " + oldMatches);
            System.out.println("t1: sequential == parallel ? " + newMatches);
            if (!oldMatches || !newMatches) {
                System.out.println("!!! MISMATCH between sequential and parallel walk results");
            }
        } finally {
            deleteRecursively(oldSnap);
            deleteRecursively(newSnap);
        }
    }

    private static void buildOldSnapshot(Path root) throws IOException {
        Files.writeString(root.resolve("readme.txt"), "hello world", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("to-be-deleted.txt"), "gone soon", StandardCharsets.UTF_8);

        Path sub = Files.createDirectory(root.resolve("sub"));
        Path original = Files.writeString(sub.resolve("original.txt"), "shared data", StandardCharsets.UTF_8);

        try {
            Files.createLink(sub.resolve("hardlink-to-original.txt"), original);
        } catch (IOException | UnsupportedOperationException ex) {
            System.out.println("(hardlinks unsupported here, skipping that part of the demo)");
        }

        try {
            // self-referential: points at "sub" itself. Files.walkFileTree must not loop on this.
            Files.createSymbolicLink(sub.resolve("self-loop"), sub);
        } catch (IOException | UnsupportedOperationException ex) {
            System.out.println("(symlinks unsupported here, skipping that part of the demo)");
        }
    }

    private static void buildNewSnapshotFrom(Path oldSnap, Path newSnap) throws IOException {
        copyRecursively(oldSnap, newSnap, new java.util.HashMap<>());
        Files.deleteIfExists(newSnap.resolve("to-be-deleted.txt"));
        Files.writeString(newSnap.resolve("readme.txt"), "hello world, updated", StandardCharsets.UTF_8);
        Files.writeString(newSnap.resolve("brand-new.txt"), "wasn't here before", StandardCharsets.UTF_8);
    }

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
            Object key = Files.readAttributes(src, BasicFileAttributes.class).fileKey();
            Path existing = key == null ? null : copiedInodes.get(key);
            if (existing != null) {
                Files.createLink(dst, existing);
            } else {
                // COPY_ATTRIBUTES preserves mtime -- without it, every untouched file would show
                // up as "MODIFIED" in the diff purely because the copy itself is a new write.
                Files.copy(src, dst, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                if (key != null) {
                    copiedInodes.put(key, dst);
                }
            }
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
