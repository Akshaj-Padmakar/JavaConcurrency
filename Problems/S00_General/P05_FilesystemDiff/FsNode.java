package Problems.S00_General.P05_FilesystemDiff;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * One entry in a filesystem snapshot tree.
 *
 * contentHash is a Merkle-style hash: for a FILE it's the hash of the bytes, for a DIRECTORY it's
 * the hash of (name, type, contentHash) of every child in sorted order, for a SYMLINK it's the hash
 * of the link target. Two nodes with equal contentHash are provably identical (including their whole
 * subtree, for directories) without comparing anything else.
 */
public final class FsNode {

    public enum Type { FILE, DIRECTORY, SYMLINK, OTHER }

    private final String name;
    private final Type type;
    private final long size;
    private final long mtimeMillis;
    private final String linkTarget;
    private final Object inodeKey; // dedup key for hardlinks ("dev:ino"); null if unsupported by the fs
    private final String contentHash;
    private final Map<String, FsNode> children;

    private FsNode(String name, Type type, long size, long mtimeMillis, String linkTarget,
            Object inodeKey, String contentHash, Map<String, FsNode> children) {
        this.name = name;
        this.type = type;
        this.size = size;
        this.mtimeMillis = mtimeMillis;
        this.linkTarget = linkTarget;
        this.inodeKey = inodeKey;
        this.contentHash = contentHash;
        this.children = children;
    }

    public static FsNode file(String name, long size, long mtimeMillis, String contentHash, Object inodeKey) {
        return new FsNode(name, Type.FILE, size, mtimeMillis, null, inodeKey, contentHash, null);
    }

    public static FsNode directory(String name, Map<String, FsNode> children, String contentHash) {
        return new FsNode(name, Type.DIRECTORY, 0, 0, null, null, contentHash,
                Collections.unmodifiableMap(new TreeMap<>(children)));
    }

    public static FsNode symlink(String name, String linkTarget, String contentHash) {
        return new FsNode(name, Type.SYMLINK, 0, 0, linkTarget, null, contentHash, null);
    }

    public static FsNode other(String name) {
        return new FsNode(name, Type.OTHER, 0, 0, null, null, "other:" + name, null);
    }

    public String getName() { return name; }
    public Type getType() { return type; }
    public long getSize() { return size; }
    public long getMtimeMillis() { return mtimeMillis; }
    public String getLinkTarget() { return linkTarget; }
    public Object getInodeKey() { return inodeKey; }
    public String getContentHash() { return contentHash; }

    /** Children in deterministic (sorted-by-name) order. Empty map for non-directory nodes. */
    public Map<String, FsNode> getChildren() {
        return children == null ? Collections.emptyMap() : children;
    }
}
