package Problems.S00_General.P01_MultiThreadedDFS.Test;

import Problems.S00_General.P01_MultiThreadedDFS.MultiThreadedDFS;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class MultiThreadedDFSTest {

    public static void main(String[] args) {
        testSharedChildVisitedOnce();
        testCycleBackToStartVisitedOnce();
        testDuplicateEdgesVisitedOnce();
        testMissingAdjacencyListStillVisitsNode();
        testNullStartDoesNothing();
        testEmptyGraphDoesNothing();

        System.out.println("All MultiThreadedDFS tests passed.");
    }

    private static void testSharedChildVisitedOnce() {
        Map<MultiThreadedDFS.Node, List<MultiThreadedDFS.Node>> graph = new HashMap<>();
        MultiThreadedDFS dfs = new MultiThreadedDFS(graph, 4);
        VisitRecorder recorder = new VisitRecorder();

        CountingNode a = new CountingNode(dfs, "A", recorder);
        CountingNode b = new CountingNode(dfs, "B", recorder);
        CountingNode c = new CountingNode(dfs, "C", recorder);
        CountingNode d = new CountingNode(dfs, "D", recorder);

        graph.put(a, listOf(b, c));
        graph.put(b, listOf(d));
        graph.put(c, listOf(d));
        graph.put(d, Collections.emptyList());

        dfs.multiThreadedDFS(a);

        assertVisitCount("shared child A", recorder, "A", 1);
        assertVisitCount("shared child B", recorder, "B", 1);
        assertVisitCount("shared child C", recorder, "C", 1);
        assertVisitCount("shared child D", recorder, "D", 1);
        assertTotalVisits("shared child total", recorder, 4);
    }

    private static void testCycleBackToStartVisitedOnce() {
        Map<MultiThreadedDFS.Node, List<MultiThreadedDFS.Node>> graph = new HashMap<>();
        MultiThreadedDFS dfs = new MultiThreadedDFS(graph, 3);
        VisitRecorder recorder = new VisitRecorder();

        CountingNode a = new CountingNode(dfs, "A", recorder);
        CountingNode b = new CountingNode(dfs, "B", recorder);
        CountingNode c = new CountingNode(dfs, "C", recorder);

        graph.put(a, listOf(b));
        graph.put(b, listOf(c));
        graph.put(c, listOf(a));

        dfs.multiThreadedDFS(a);

        assertVisitCount("cycle A", recorder, "A", 1);
        assertVisitCount("cycle B", recorder, "B", 1);
        assertVisitCount("cycle C", recorder, "C", 1);
        assertTotalVisits("cycle total", recorder, 3);
    }

    private static void testDuplicateEdgesVisitedOnce() {
        Map<MultiThreadedDFS.Node, List<MultiThreadedDFS.Node>> graph = new HashMap<>();
        MultiThreadedDFS dfs = new MultiThreadedDFS(graph, 3);
        VisitRecorder recorder = new VisitRecorder();

        CountingNode a = new CountingNode(dfs, "A", recorder);
        CountingNode b = new CountingNode(dfs, "B", recorder);

        graph.put(a, listOf(b, b, b));
        graph.put(b, Collections.emptyList());

        dfs.multiThreadedDFS(a);

        assertVisitCount("duplicate edge A", recorder, "A", 1);
        assertVisitCount("duplicate edge B", recorder, "B", 1);
        assertTotalVisits("duplicate edge total", recorder, 2);
    }

    private static void testMissingAdjacencyListStillVisitsNode() {
        Map<MultiThreadedDFS.Node, List<MultiThreadedDFS.Node>> graph = new HashMap<>();
        MultiThreadedDFS dfs = new MultiThreadedDFS(graph, 2);
        VisitRecorder recorder = new VisitRecorder();

        CountingNode a = new CountingNode(dfs, "A", recorder);
        CountingNode b = new CountingNode(dfs, "B", recorder);

        graph.put(a, listOf(b));

        dfs.multiThreadedDFS(a);

        assertVisitCount("missing adjacency A", recorder, "A", 1);
        assertVisitCount("missing adjacency B", recorder, "B", 1);
        assertTotalVisits("missing adjacency total", recorder, 2);
    }

    private static void testNullStartDoesNothing() {
        Map<MultiThreadedDFS.Node, List<MultiThreadedDFS.Node>> graph = new HashMap<>();
        MultiThreadedDFS dfs = new MultiThreadedDFS(graph, 2);
        VisitRecorder recorder = new VisitRecorder();

        CountingNode a = new CountingNode(dfs, "A", recorder);
        graph.put(a, Collections.emptyList());

        dfs.multiThreadedDFS(null);

        assertTotalVisits("null start total", recorder, 0);
    }

    private static void testEmptyGraphDoesNothing() {
        Map<MultiThreadedDFS.Node, List<MultiThreadedDFS.Node>> graph = new HashMap<>();
        MultiThreadedDFS dfs = new MultiThreadedDFS(graph, 2);
        VisitRecorder recorder = new VisitRecorder();
        CountingNode a = new CountingNode(dfs, "A", recorder);

        dfs.multiThreadedDFS(a);

        assertTotalVisits("empty graph total", recorder, 0);
    }

    private static List<MultiThreadedDFS.Node> listOf(MultiThreadedDFS.Node... nodes) {
        List<MultiThreadedDFS.Node> result = new ArrayList<>();
        Collections.addAll(result, nodes);
        return result;
    }

    private static void assertVisitCount(String testName, VisitRecorder recorder, String nodeId, int expected) {
        int actual = recorder.countFor(nodeId);
        if (actual != expected) {
            throw new AssertionError(testName + " expected " + expected + " visits for " + nodeId + " but got " + actual);
        }
    }

    private static void assertTotalVisits(String testName, VisitRecorder recorder, int expected) {
        int actual = recorder.totalVisits();
        if (actual != expected) {
            throw new AssertionError(testName + " expected " + expected + " total visits but got " + actual);
        }
    }

    private static class CountingNode extends MultiThreadedDFS.Node {
        private final VisitRecorder recorder;

        private CountingNode(MultiThreadedDFS dfs, String id, VisitRecorder recorder) {
            dfs.super(id);
            this.recorder = recorder;
        }

        @Override
        public void doWork() {
            recorder.record(getId());
        }
    }

    private static class VisitRecorder {
        private final ConcurrentHashMap<String, AtomicInteger> visitCounts = new ConcurrentHashMap<>();

        private void record(String nodeId) {
            visitCounts.computeIfAbsent(nodeId, ignored -> new AtomicInteger()).incrementAndGet();
        }

        private int countFor(String nodeId) {
            AtomicInteger count = visitCounts.get(nodeId);
            return count == null ? 0 : count.get();
        }

        private int totalVisits() {
            int total = 0;
            for (AtomicInteger count : visitCounts.values()) {
                total += count.get();
            }
            return total;
        }
    }
}
