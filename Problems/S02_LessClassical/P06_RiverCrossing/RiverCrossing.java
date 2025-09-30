package Problems.S02_LessClassical.P06_RiverCrossing;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class RiverCrossing {
    
    private final int N; // Number of serfs
    private final int M; // number of hackers

   
 


    public class SerfRunnable implements Runnable {
        private int id;
        public SerfRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {

        }
    }

    public class HackerRunnable implements Runnable {
        private int id;
        public HackerRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {

        }
    }


    public RiverCrossing(int N, int M) {
        this.N = N;
        this.M = M;
    }


    public void solve() throws InterruptedException {
        List<Thread> serfThreads = new ArrayList<>();
        List<Thread> hackerThreads = new ArrayList<>();
        for(int i = 0; i < this.N; i++) {
            serfThreads.add(new Thread(new SerfRunnable(i), "Serf-Thread-" + i));
        }

        for(int i = 0; i < this.M; i++) {
            hackerThreads.add(new Thread(new HackerRunnable(i), "Hacker-Thread-" + i));
        }

        for(Thread t : serfThreads) {
            t.start();
        }

        for(Thread t : hackerThreads) {
            t.start();
        }

        for(Thread t : serfThreads) {
            t.join();
        }
        for(Thread t : hackerThreads) {
            t.join();
        }
    }
    public static void main(String[] args) throws InterruptedException {
        int N = 4;
        int M = 4;
        new RiverCrossing(N, M).solve();
    }
}
