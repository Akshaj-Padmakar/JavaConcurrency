package Problems.S00_General.P01_MultiThreadedDFS;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MultiThreadedDFS {

    Map<Node, List<Node>> graph;

    private ExecutorService threadPool;
    private Map<Node, Boolean> vis;

    private final AtomicInteger activeTask = new AtomicInteger(0);
    private final CountDownLatch completionLatch = new CountDownLatch(1); // reference counting for recursive work.

    public MultiThreadedDFS(Map<Node, List<Node>> graph) {
        this(graph, 5);
    }

    public MultiThreadedDFS(Map<Node, List<Node>> graph, int threadCnt) {
        this.graph = graph;
        this.threadPool = Executors.newFixedThreadPool(threadCnt);

        this.vis = new HashMap<>();
    }

    public void multiThreadedDFS(Node startNode) {
        if (graph == null || graph.isEmpty() || startNode == null) {
            System.out.println("[ERROR], Graph is null.");
            return;
        }

        if (!markVisited(startNode)) {
            return;
        }

        activeTask.incrementAndGet();
        threadPool.execute(new dfs(startNode, new Node("-1")));

        try {
            completionLatch.await();
        } catch (InterruptedException ex) {
            ex.printStackTrace();
            Thread.currentThread().interrupt();
        }
        threadPool.shutdown();

    }

    private class dfs implements Runnable {
        private Node node;
        private Node par;

        private dfs(Node node, Node par) {
            this.node = node;
            this.par = par;
        }

        @Override
        public void run() {
            try {
                node.doWork(); // do computational heavy work.

                System.out.println("Visited node: " + this.node.getId() + " and previousNode: " + this.par.getId()
                        + " and using Thread: " + Thread.currentThread().getName());

                for (Node ch : graph.getOrDefault(node, Collections.emptyList())) {
                    if (ch == null || !markVisited(ch)) {
                        continue;
                    }
                    activeTask.incrementAndGet();
                    threadPool.execute(new dfs(ch, node));
                }
            } finally {
                if (activeTask.decrementAndGet() == 0) {
                    completionLatch.countDown();
                }
            }
        }

    }

    private boolean markVisited(Node node) {
        synchronized (vis) {
            if (vis.getOrDefault(node, false)) {
                return false;
            }
            vis.put(node, true);
            return true;
        }
    }

    public class Node {
        private String id;

        public Node(String id) {
            this.id = id;
        }

        public String getId() {
            return this.id;
        }

        public void doWork() { // Override by different nodes.
            try {
                System.out.println("Node-" + this.id + " is working...");
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            }
            System.out.println("Node-" + this.id + " is done!");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            } else if (!(o instanceof Node)) {
                return false;
            }

            Node nodeO = (Node) o;
            return this.id.equals(nodeO.getId());
        }

        @Override
        public int hashCode() {
            return this.id.hashCode();
        }
    }
}
