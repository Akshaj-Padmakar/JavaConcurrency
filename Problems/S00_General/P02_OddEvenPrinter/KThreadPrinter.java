package Problems.S00_General.P02_OddEvenPrinter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/* 
 * Given k threads, start from 0, and print the i th value with i % k-th thread.
*/
public class KThreadPrinter {
    private final int n;
    private final int k;
    private int current = 0;
    private final Lock lock = new ReentrantLock();
    private final List<Condition> threadConditions = new ArrayList<>();

    public KThreadPrinter(int n, int k) {
        this.n = n;
        this.k = k;

        for (int i = 0; i < k; i++) {
            threadConditions.add(lock.newCondition());
        }
    }

    public void solve() throws InterruptedException {
        List<Thread> kThreads = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            kThreads.add(new Thread(new IRunnable(i), i + "-Thread"));
        }
        for (Thread thread : kThreads) {
            thread.start();
        }
        for (Thread thread : kThreads) {
            thread.join();
        }
    }

    private class IRunnable implements Runnable {
        private final int i;

        public IRunnable(int i) {
            this.i = i;
        }

        @Override
        public void run() {
            while (true) {
                lock.lock();
                try {
                    while (current <= n && current % k != i) {
                        threadConditions.get(i).await();
                    }
                    if (current > n) {
                        signalNextThread();
                        break;
                    }
                    printCurrentValueAndThread();
                    current++;
                    signalNextThread();
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                } finally {
                    lock.unlock();
                }
            }
        }

        private void signalNextThread() {
            threadConditions.get((i + 1) % k).signal();
        }
    }

    private void printCurrentValueAndThread() {
        System.out.println(
                "current = " + current + ", printed by Thread = " + Thread.currentThread().getName());
    }
}
