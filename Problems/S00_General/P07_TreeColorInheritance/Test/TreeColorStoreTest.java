package Problems.S00_General.P07_TreeColorInheritance.Test;

import Problems.S00_General.P07_TreeColorInheritance.TreeColorStore;

import java.util.Random;

public class TreeColorStoreTest {

    public static void main(String[] args) throws InterruptedException {
        testGetColorInheritsFromNearestExplicitAncestor();
        testExplicitColorBlocksPushDown();
        testAssignColorTouchedCountIsBounded();
        testUpdateParentRecomputesEffectiveColorAndPushesDown();
        testUpdateParentRejectsCycle();
        testAddNodeInheritsParentsCurrentEffectiveColorAtInsertTime();
        testParallelAssignColorMatchesSequentialOnLargeRandomTree();

        System.out.println("All TreeColorStore tests passed.");
    }

    private static void testGetColorInheritsFromNearestExplicitAncestor() {
        TreeColorStore store = new TreeColorStore();
        store.addRoot(1);
        store.addNode(2, 1);
        store.addNode(3, 2);

        assertEquals("fresh nodes have no color", TreeColorStore.NO_COLOR, store.getColor(3));

        store.assignColor(1, "RED");
        assertEquals("child inherits from root", "RED", store.getColor(2));
        assertEquals("grandchild inherits transitively", "RED", store.getColor(3));

        store.assignColor(1, "BLUE");
        assertEquals("re-assigning the root repropagates", "BLUE", store.getColor(3));
    }

    private static void testExplicitColorBlocksPushDown() {
        TreeColorStore store = new TreeColorStore();
        store.addRoot(1);
        store.addNode(2, 1);
        store.addNode(3, 2); // grandchild, under 2, no color of its own

        store.assignColor(1, "RED");
        store.assignColor(2, "GREEN"); // node 2 opts out
        assertEquals("node 2 keeps its own explicit color", "GREEN", store.getColor(2));
        assertEquals("node 3 inherits from 2, not the root", "GREEN", store.getColor(3));

        store.assignColor(1, "BLUE"); // root changes again
        assertEquals("node 2's explicit color is unaffected by the root changing", "GREEN", store.getColor(2));
        assertEquals("node 3 (under the opted-out node 2) is also unaffected", "GREEN", store.getColor(3));
    }

    private static void testAssignColorTouchedCountIsBounded() {
        TreeColorStore store = new TreeColorStore();
        store.addRoot(1);
        for (int i = 2; i <= 21; i++) { // 20 children of the root
            store.addNode(i, 1);
        }
        // give 15 of them their own explicit color -- push-down from the root must stop at each
        for (int i = 2; i <= 16; i++) {
            store.assignColor(i, "PRE-SET-" + i);
        }

        int touched = store.assignColor(1, "ROOT-COLOR");
        // touched = root itself + the 5 children that DIDN'T opt out (17..21)
        assertEquals("push-down must stop at every opted-out child, not walk the whole subtree", 6, touched);
        assertEquals("opted-out child keeps its own color", "PRE-SET-2", store.getColor(2));
        assertEquals("non-opted-out child inherits the new root color", "ROOT-COLOR", store.getColor(17));
    }

    private static void testUpdateParentRecomputesEffectiveColorAndPushesDown() {
        TreeColorStore store = new TreeColorStore();
        store.addRoot(1);
        store.addNode(2, 1);
        store.addNode(3, 1);
        store.addNode(4, 2); // 4 is under 2, currently
        store.addNode(5, 4); // grandchild under 4, no explicit color

        store.assignColor(2, "GREEN");
        store.assignColor(3, "PURPLE");
        assertEquals("4 currently inherits GREEN from 2", "GREEN", store.getColor(4));

        store.updateParent(4, 3); // move 4 from under 2 to under 3
        assertEquals("4 now inherits PURPLE from its new parent 3", "PURPLE", store.getColor(4));
        assertEquals("5 (under 4) follows transitively", "PURPLE", store.getColor(5));

        store.assignColor(5, "OWN-COLOR"); // 5 opts out
        store.updateParent(4, 2); // move 4 back under 2
        assertEquals("4 picks up GREEN again after moving back", "GREEN", store.getColor(4));
        assertEquals("5 keeps its own explicit color despite 4 moving again", "OWN-COLOR", store.getColor(5));
    }

    private static void testUpdateParentRejectsCycle() {
        TreeColorStore store = new TreeColorStore();
        store.addRoot(1);
        store.addNode(2, 1);
        store.addNode(3, 2);

        boolean threw = false;
        try {
            store.updateParent(1, 3); // would make the root a descendant of its own grandchild
        } catch (IllegalArgumentException ex) {
            threw = true;
        }
        assertTrue("moving a node under its own descendant must be rejected", threw);
    }

    private static void testAddNodeInheritsParentsCurrentEffectiveColorAtInsertTime() {
        TreeColorStore store = new TreeColorStore();
        store.addRoot(1);
        store.assignColor(1, "RED");
        store.addNode(2, 1);
        assertEquals("a brand new node inherits its parent's CURRENT effective color at insert time",
                "RED", store.getColor(2));

        store.assignColor(1, "BLUE"); // root changes again, after 2 was added
        assertEquals("existing node 2 still tracks the root going forward", "BLUE", store.getColor(2));
    }

    private static void testParallelAssignColorMatchesSequentialOnLargeRandomTree() throws InterruptedException {
        long seed = 42;
        TreeColorStore sequential = buildRandomTree(seed, 4000);
        TreeColorStore parallel = buildRandomTree(seed, 4000); // identical structure, same seed

        int seqTouched = sequential.assignColor(1, "CROSS-CHECK-COLOR");
        int parTouched = parallel.assignColorParallel(1, "CROSS-CHECK-COLOR", 8);

        assertEquals("sequential and parallel push-down must touch the same number of nodes",
                seqTouched, parTouched);
        for (int id = 1; id <= sequential.size(); id++) {
            assertEquals("node " + id + " must have the same effective color in both stores",
                    sequential.getColor(id), parallel.getColor(id));
        }
    }

    private static TreeColorStore buildRandomTree(long seed, int nodeCount) {
        TreeColorStore store = new TreeColorStore();
        Random rnd = new Random(seed);
        store.addRoot(1);
        for (int id = 2; id <= nodeCount; id++) {
            int parentId = 1 + rnd.nextInt(id - 1); // parent is always an already-added node
            store.addNode(id, parentId);
            if (rnd.nextInt(10) == 0) { // ~10% of nodes get their own explicit color
                store.assignColor(id, "PRESET-" + id);
            }
        }
        return store;
    }

    private static void assertTrue(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError("FAILED: " + message);
        }
    }

    private static void assertEquals(String message, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("FAILED: " + message + " (expected " + expected + ", got " + actual + ")");
        }
    }
}
