package Problems.S00_General.P01_MultiThreadedDFS;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MultiThreadedDFS {
    private final int n = 5;
    private Map<Integer, List<Integer>> g;
    private List<Boolean> vis;
    private ExecutorService threadPool = Executors.newFixedThreadPool(3);

    public static void main(String[] args) throws InterruptedException {
        new MultiThreadedDFS().solve();
    }

    private void solve() throws InterruptedException {
        intializeAndCreateGraph();
        multiThreadedDFS();

        Thread.sleep(1000);
        threadPool.shutdown();
    }

    private void intializeAndCreateGraph() {
        g = new HashMap<>();
        vis = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            g.put(i, new ArrayList<>());
            vis.add(false);
        }
        addEdge(1, 2);
        addEdge(1, 3);
        addEdge(2, 3);
        addEdge(3, 4);
        addEdge(3, 5);
        addEdge(4, 2);
    }

    private void addEdge(int i, int j) {
        g.get(i).add(j);
    }

    private void multiThreadedDFS() {
        threadPool.execute(new dfs(1, -1));
    }

    private class dfs implements Runnable {
        int node;
        int par;

        public dfs(int node, int par) {
            this.node = node;
            this.par = par;
        }

        @Override
        public void run() {
            synchronized (vis) { // Lock the visited array.
                if (vis.get(node)) {
                    return;
                }
                System.out.println("Visited node: " + this.node + " and previousNode: " + this.par
                        + " and using Thread: " + Thread.currentThread().getName());

                vis.add(this.node, true);
            }
            for (Integer ch : g.get(node)) {
                threadPool.execute(new dfs(ch, node));
            }
        }
    }
}
