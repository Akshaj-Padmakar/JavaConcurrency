package Problems.S00_General.P01_MultiThreadedDFS.Test;

import Problems.S00_General.P01_MultiThreadedDFS.MultiThreadedDFS;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MultiThreadedDFSTest {

    public static void main(String[] args) throws InterruptedException {

        Map<MultiThreadedDFS.Node, List<MultiThreadedDFS.Node>> graph = new HashMap<>();

        MultiThreadedDFS dfs = new MultiThreadedDFS(6, graph, 3);

        // Create nodes
        MultiThreadedDFS.Node n1 = dfs.new Node("1");
        MultiThreadedDFS.Node n2 = dfs.new Node("2");
        MultiThreadedDFS.Node n3 = dfs.new Node("3");
        MultiThreadedDFS.Node n4 = dfs.new Node("4");
        MultiThreadedDFS.Node n5 = dfs.new Node("5");
        MultiThreadedDFS.Node n6 = dfs.new Node("6");

        /*
         * 1
         * / \
         * 2 3
         * / \ / \
         * 4 5 5 6
         * 
         * Shared node 5 tests visited logic.
         */

        graph.put(n1, new ArrayList<>());
        graph.put(n2, new ArrayList<>());
        graph.put(n3, new ArrayList<>());
        graph.put(n4, new ArrayList<>());
        graph.put(n5, new ArrayList<>());
        graph.put(n6, new ArrayList<>());

        graph.get(n1).add(n2);
        graph.get(n1).add(n3);

        graph.get(n2).add(n4);
        graph.get(n2).add(n5);

        graph.get(n3).add(n5);
        graph.get(n3).add(n6);

        System.out.println("Starting Multithreaded DFS...");

        dfs.multiThreadedDFS(n1);

        // Wait for async execution
        Thread.sleep(3000);

        System.out.println("✅ Traversal Finished.");
    }
}