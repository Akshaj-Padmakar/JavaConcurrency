package Problems.S02_LessClassical.P06_RiverCrossing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class RiverCrossing {
    private final int hackerCnt;
    private final int serfCnt;

    private final Lock lock = new ReentrantLock();
    private Condition serfWaitingCondition = lock.newCondition();
    private Condition hackerWaitingCondition = lock.newCondition();
    private Condition onBoardWaitCondition = lock.newCondition();

    private int hackerWaiting = 0;
    private int serfWaiting = 0;
    private int hackerRelease = 0;
    private int serfRelease = 0;
    private int generation = 0;
    private int onBoard = 0;

    public RiverCrossing(int hackerCnt, int serfCnt) {
        this.hackerCnt = hackerCnt;
        this.serfCnt = serfCnt;
    }

    private class HackerRunnable implements Runnable {
        private final int id;

        private HackerRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            lock.lock();
            try {
                hackerWaiting++;
                tryBoarding();
                while (hackerRelease == 0) {
                    hackerWaitingCondition.await();
                }
                hackerRelease--;
                System.out.println("Hacker-" + this.id + " is released....; gen=" + generation);
                barrier();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }
    }

    private class SerfRunnable implements Runnable {
        private final int id;

        private SerfRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            lock.lock();
            try {
                serfWaiting++;
                tryBoarding();
                while (serfRelease == 0) {
                    serfWaitingCondition.await();
                }
                serfRelease--;
                System.out.println("Serf-" + this.id + " is released....; gen=" + generation);
                barrier();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }
    }

    private void tryBoarding() {
        if (hackerRelease != 0 || serfRelease != 0) {
            // safe guard, some hacker/serf have already been released.
            // if say 4 serf and 4 hacker are released, they can intertwine to form 3S 1H
            return;
        }
        if (serfWaiting >= 4) {
            serfWaiting -= 4;
            serfRelease += 4;
            serfWaitingCondition.signalAll();
        } else if (hackerWaiting >= 4) {
            hackerWaiting -= 4;
            hackerRelease += 4;
            hackerWaitingCondition.signalAll();
        } else if (hackerWaiting >= 2 && serfWaiting >= 2) {
            serfWaiting -= 2;
            hackerWaiting -= 2;
            hackerRelease += 2;
            serfRelease += 2;
            hackerWaitingCondition.signalAll();
            serfWaitingCondition.signalAll();
        }
    }

    private void barrier() throws InterruptedException {
        int currentGen = generation;
        onBoard++;
        if (onBoard == 4) {
            generation++;
            onBoard = 0;
            onBoardWaitCondition.signalAll();
            tryBoarding();
        } else {
            while (currentGen == generation) {
                onBoardWaitCondition.await();
            }
        }
    }

    public void solve() throws InterruptedException {
        List<Thread> hackerThreads = new ArrayList<>();
        List<Thread> serfThreads = new ArrayList<>();
        for (int i = 0; i < this.hackerCnt; i++) {
            hackerThreads.add(new Thread(new HackerRunnable(i), "Hacker-Thread-" + i));
        }

        for (int i = 0; i < this.serfCnt; i++) {
            serfThreads.add(new Thread(new SerfRunnable(i), "Serf-Thread-" + i));
        }

        for (Thread t : hackerThreads) {
            t.start();
        }
        for (Thread t : serfThreads) {
            t.start();
        }

        for (Thread t : hackerThreads) {
            t.join();
        }
        for (Thread t : serfThreads) {
            t.join();
        }
    }

    public static void main(String args[]) throws InterruptedException {
        new RiverCrossing(10, 10).solve();
    }

}
