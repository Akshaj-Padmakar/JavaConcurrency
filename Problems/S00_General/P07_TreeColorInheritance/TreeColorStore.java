package Problems.S00_General.P07_TreeColorInheritance;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Each node's effective color is either its own explicitly assigned color, or -- via its nearest
 * ancestor that has one -- inherited. Optimized for a read-heavy workload: getColor() is O(1)
 * (the effective color is stored, not recomputed by walking parents), at the cost of
 * assignColor()/updateParent() doing a write bounded by the affected subtree, not the whole tree.
 *
 * childrenOf is maintained incrementally (updated in addNode/updateParent), not rebuilt per call
 * -- rebuilding it from scratch on every push-down would make every write O(total tree size)
 * regardless of how small the actually-affected subtree is, defeating the entire point.
 */
public class TreeColorStore {

    public static final String NO_COLOR = "NONE";

    private final Map<Integer, TreeNode> nodes = new ConcurrentHashMap<>();
    private final Map<Integer, List<Integer>> childrenOf = new ConcurrentHashMap<>();

    public void addRoot(int id) {
        if (nodes.putIfAbsent(id, new TreeNode(id, -1, NO_COLOR)) != null) {
            throw new IllegalArgumentException("Node " + id + " already exists");
        }
        childrenOf.put(id, new CopyOnWriteArrayList<>());
    }

    public void addNode(int id, int parentId) {
        TreeNode parent = requireNode(parentId);
        if (nodes.putIfAbsent(id, new TreeNode(id, parentId, parent.getEffectiveColor())) != null) {
            throw new IllegalArgumentException("Node " + id + " already exists");
        }
        childrenOf.put(id, new CopyOnWriteArrayList<>());
        childrenOf.get(parentId).add(id);
    }

    public String getColor(int id) {
        return requireNode(id).getEffectiveColor();
    }

    /** Returns how many nodes' effective color actually changed -- proof the write is bounded, not O(whole tree). */
    public int assignColor(int id, String color) {
        TreeNode node = requireNode(id);
        node.setAssignedColor(color);
        node.setEffectiveColor(color);
        return pushDownFrom(node);
    }

    public int updateParent(int id, int newParentId) {
        TreeNode node = requireNode(id);
        TreeNode newParent = requireNode(newParentId);
        if (id == newParentId || isDescendant(newParentId, id)) {
            throw new IllegalArgumentException("Cannot move node " + id + " under its own descendant " + newParentId);
        }
        childrenOf.get(node.getParentId()).remove((Integer) id);
        node.setParentId(newParentId);
        childrenOf.get(newParentId).add(id);

        if (!node.hasExplicitColor()) {
            node.setEffectiveColor(newParent.getEffectiveColor());
        }
        return pushDownFrom(node);
    }

    /**
     * BFS from `start`, propagating its effective color to every descendant, EXCEPT it never
     * descends into a subtree whose root has its own explicit color -- that subtree already
     * correctly reflects its own assignment and is unaffected by anything above it.
     */
    private int pushDownFrom(TreeNode start) {
        int touched = 0;
        Queue<TreeNode> frontier = new ArrayDeque<>();
        frontier.add(start);

        while (!frontier.isEmpty()) {
            TreeNode current = frontier.poll();
            touched++;
            for (int childId : childrenOf.get(current.getId())) {
                TreeNode child = nodes.get(childId);
                if (child.hasExplicitColor()) {
                    continue; // opted out -- its own subtree is unaffected
                }
                child.setEffectiveColor(current.getEffectiveColor());
                frontier.add(child);
            }
        }
        return touched;
    }

    /**
     * Same algorithm, fanned out across a bounded worker pool: once a node's effective color is
     * settled, its un-explicit-colored children are independent of each other, so they can be
     * processed concurrently. Same active-count + CountDownLatch completion idiom as
     * P01_MultiThreadedDFS / P05_FilesystemDiff's parallel walk -- shutdown() + awaitTermination()
     * alone doesn't work because child tasks are submitted dynamically while the walk is running.
     *
     * Scope: safe to call when nothing else is concurrently restructuring the tree (no concurrent
     * addNode/updateParent/assignColor touching overlapping nodes). Making overlapping structural
     * operations safe against each other is a separate, harder problem (see Solution.md).
     */
    public int assignColorParallel(int id, String color, int poolSize) throws InterruptedException {
        TreeNode node = requireNode(id);
        node.setAssignedColor(color);
        node.setEffectiveColor(color);

        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        AtomicInteger touched = new AtomicInteger(0);
        AtomicInteger active = new AtomicInteger(1); // counts the root task itself
        CountDownLatch done = new CountDownLatch(1);

        pool.execute(() -> pushNodeParallel(node, touched, pool, active, done));

        done.await();
        pool.shutdown();
        return touched.get();
    }

    private void pushNodeParallel(TreeNode current, AtomicInteger touched, ExecutorService pool,
            AtomicInteger active, CountDownLatch done) {
        try {
            touched.incrementAndGet();
            for (int childId : childrenOf.get(current.getId())) {
                TreeNode child = nodes.get(childId);
                if (child.hasExplicitColor()) {
                    continue;
                }
                child.setEffectiveColor(current.getEffectiveColor());
                active.incrementAndGet(); // MUST happen before execute(), not after
                pool.execute(() -> pushNodeParallel(child, touched, pool, active, done));
            }
        } finally {
            if (active.decrementAndGet() == 0) {
                done.countDown();
            }
        }
    }

    private boolean isDescendant(int candidateId, int ancestorId) {
        TreeNode n = nodes.get(candidateId);
        while (n != null && n.getParentId() != -1) {
            if (n.getParentId() == ancestorId) {
                return true;
            }
            n = nodes.get(n.getParentId());
        }
        return false;
    }

    private TreeNode requireNode(int id) {
        TreeNode n = nodes.get(id);
        if (n == null) {
            throw new IllegalArgumentException("No such node: " + id);
        }
        return n;
    }

    public int size() {
        return nodes.size();
    }
}
