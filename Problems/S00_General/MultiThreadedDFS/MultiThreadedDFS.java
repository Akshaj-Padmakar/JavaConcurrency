package Problems.S00_General.MultiThreadedDFS;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MultiThreadedDFS {
    private final int n = 5;
    private Map<Integer, List<Integer>> mp = new HashMap<>();
    private ExecutorService threadPool = Executors.newFixedThreadPool(3);
    private List<Boolean> vis = new ArrayList<>();

    public class dfs implements Runnable {
        private int node;
        private int previous;
        public dfs(int node, int previous){
            this.node = node;
            this.previous = previous;
        }

        @Override
        public void run() {
            synchronized(vis){
                if(vis.get(this.node)){
                    return;
                }
                System.out.println("Visited node: " + this.node + " and previousNode: " + this.previous + " and using Thread: " + Thread.currentThread().getName());
                vis.set(this.node, true);
            }

            for(Integer child : mp.get(this.node)){
                threadPool.execute(new dfs(child, node));
            }
        }
    }
    
    public void multiThreadedDFS(){
        threadPool.execute(new dfs(1, -1));
    }

    public void solve() throws InterruptedException {
        vis.add(false);
        for(int i = 1; i <= n; i++){
            mp.put(i, new ArrayList<>());
            vis.add(false);
        }
        addEdge(1, 2);
        addEdge(1, 3);
        addEdge(2, 3);
        addEdge(3, 4);
        addEdge(3, 5);
        addEdge(4, 2);

        multiThreadedDFS();
        Thread.sleep(1000);
        threadPool.shutdown();
    }
    private void addEdge(int i, int j){
        mp.get(i).add(j);
    }
    public static void main(String[] args) throws InterruptedException {
        new MultiThreadedDFS().solve();
    }
}
