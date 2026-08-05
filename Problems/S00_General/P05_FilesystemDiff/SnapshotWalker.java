package Problems.S00_General.P05_FilesystemDiff;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Walks a real directory into an in-memory {@link FsNode} tree ("ls -R" / "tree" style), and prints
 * it. Directory symlinks are NOT followed while walking (matches ls -R/tree default behavior) -- a
 * symlink is always recorded as a leaf pointing at its target, which also sidesteps the classic
 * infinite-loop trap of a symlink pointing back at an ancestor directory.
 */
public class SnapshotWalker {

    private static final int BUFFER_SIZE = 64 * 1024;

    public FsNode walk(Path root) throws IOException {
        String name = root.getFileName() == null ? root.toString() : root.getFileName().toString();
        return buildNode(root, name);
    }

    private FsNode buildNode(Path path, String name) throws IOException {
        if (Files.isSymbolicLink(path)) {
            String target = Files.readSymbolicLink(path).toString();
            return FsNode.symlink(name, target, sha256(target.getBytes()));
        }
        if (Files.isDirectory(path)) {
            TreeMap<String, FsNode> children = new TreeMap<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path child : stream) {
                    String childName = child.getFileName().toString();
                    children.put(childName, buildNode(child, childName));
                }
            }
            return FsNode.directory(name, children, hashDirectory(children));
        }
        if (Files.isRegularFile(path)) {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            String hash = hashFile(path);
            Object inodeKey = readInodeKey(path);
            return FsNode.file(name, attrs.size(), attrs.lastModifiedTime().toMillis(), hash, inodeKey);
        }
        // device nodes, FIFOs, sockets, etc. -- not content-diffable, tracked by presence/type only
        return FsNode.other(name);
    }

    private String hashFile(Path path) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        }
        return toHex(digest.digest());
    }

    private String hashDirectory(TreeMap<String, FsNode> children) {
        MessageDigest digest = newSha256();
        for (var entry : children.entrySet()) { // TreeMap -> sorted, so this is deterministic
            digest.update(entry.getKey().getBytes());
            digest.update(entry.getValue().getType().name().getBytes());
            digest.update(entry.getValue().getContentHash().getBytes());
        }
        return toHex(digest.digest());
    }

    private String sha256(byte[] data) {
        MessageDigest digest = newSha256();
        digest.update(data);
        return toHex(digest.digest());
    }

    private MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Hardlink identity: same (device, inode) => same physical data, regardless of path. */
    private Object readInodeKey(Path path) {
        try {
            Object ino = Files.getAttribute(path, "unix:ino");
            Object dev = Files.getAttribute(path, "unix:dev");
            return dev + ":" + ino;
        } catch (UnsupportedOperationException | IOException ex) {
            return null; // non-POSIX filesystem -- hardlink dedup unavailable, every entry is unique
        }
    }

    /** Sum of physical bytes, counting each hardlinked inode exactly once. */
    public long totalPhysicalSize(FsNode root) {
        return sizeRec(root, new HashSet<>());
    }

    private long sizeRec(FsNode node, Set<Object> seenInodes) {
        switch (node.getType()) {
            case FILE:
                if (node.getInodeKey() != null && !seenInodes.add(node.getInodeKey())) {
                    return 0; // already counted this physical file via a different hardlink
                }
                return node.getSize();
            case DIRECTORY:
                long total = 0;
                for (FsNode child : node.getChildren().values()) {
                    total += sizeRec(child, seenInodes);
                }
                return total;
            default:
                return 0;
        }
    }

    /** "tree"-style recursive print. */
    public void print(FsNode root) {
        System.out.println(describe(root));
        if (root.getType() == FsNode.Type.DIRECTORY) {
            printChildren(root, "");
        }
    }

    private void printChildren(FsNode node, String prefix) {
        List<FsNode> kids = new ArrayList<>(node.getChildren().values());
        for (int i = 0; i < kids.size(); i++) {
            FsNode child = kids.get(i);
            boolean last = (i == kids.size() - 1);
            System.out.println(prefix + (last ? "└── " : "├── ") + describe(child));
            if (child.getType() == FsNode.Type.DIRECTORY) {
                printChildren(child, prefix + (last ? "    " : "│   "));
            }
        }
    }

    private String describe(FsNode node) {
        switch (node.getType()) {
            case DIRECTORY:
                return node.getName() + "/";
            case SYMLINK:
                return node.getName() + " -> " + node.getLinkTarget();
            case FILE:
                return node.getName() + " (" + node.getSize() + " bytes)";
            default:
                return node.getName() + " [other]";
        }
    }
}
