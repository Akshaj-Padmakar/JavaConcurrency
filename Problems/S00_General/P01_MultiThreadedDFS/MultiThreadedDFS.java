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
    private final int n;
    private Map<Node, List<Node>> g;
    private Map<Node, Boolean> vis;
    private ExecutorService threadPool;

    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final CountDownLatch completionLatch = new CountDownLatch(1); // Reference Counting for recursive work.

    public MultiThreadedDFS(int n, Map<Node, List<Node>> g) {
        this(n, g, 5);
    }

    public MultiThreadedDFS(int n, Map<Node, List<Node>> g, int threadCnt) {
        this.n = n;
        this.g = g;
        this.threadPool = Executors.newFixedThreadPool(threadCnt);
        this.vis = new HashMap<>();
        for (Map.Entry<Node, List<Node>> node : g.entrySet()) {
            vis.put(node.getKey(), false);
        }
    }

    public void multiThreadedDFS(Node startNode) {
        if (g.isEmpty()) {
            System.out.println("[EROOR] Graph is null !");
            return;
        }
        activeTasks.incrementAndGet();

        threadPool.execute(new dfs(startNode, new Node("-1")));

        try {
            completionLatch.await();
        } catch (InterruptedException ex) {
            ex.printStackTrace();
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
                synchronized (vis) {
                    if (vis.getOrDefault(node, false)) {
                        return;
                    }
                    vis.put(node, true);
                }
                node.doWork(); // Heavy computation work.
                System.out.println("Visited node: " + this.node.getId() + " and previousNode: " + this.par.getId()
                        + " and using Thread: " + Thread.currentThread().getName());

                for (Node ch : g.getOrDefault(node, Collections.emptyList())) {
                    if (ch == null) {
                        continue;
                    }
                    activeTasks.incrementAndGet(); // Always incremented before putting in threadPool.
                    threadPool.execute(new dfs(ch, node));
                }
            } finally {
                if (activeTasks.decrementAndGet() == 0) {
                    completionLatch.countDown(); // Signals API that execution is completed.
                }
            }
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

        public void doWork() {
            try {
                Thread.sleep(200);
                System.out.println("Node-" + this.id + "is working...");
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof Node))
                return false;
            Node node = (Node) o;
            return this.id.equals(node.id);
        }

        @Override
        public int hashCode() {
            return this.id.hashCode();
        }

    }
}
