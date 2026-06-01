package Problems.S02_LessClassical.P06_RiverCrossing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class RiverCrossing {

    private final int N; // Number of serfs
    private final int M; // number of hackers

    private final Lock lock = new ReentrantLock();
    private final Semaphore serfSemaphore = new Semaphore(0);
    private final Semaphore hackerSemaphore = new Semaphore(0);

    private final CyclicBarrier barrier = new CyclicBarrier(4);

    private boolean boardingStarted = false;
    private Condition boardingCondition = lock.newCondition();

    private int serf = 0;
    private int hacker = 0;
    private int boatNumber = 0;

    public RiverCrossing(int N, int M) {
        this.N = N;
        this.M = M;
    }

    public class SerfRunnable implements Runnable {
        private int id;

        public SerfRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            boolean isCaptain = false;
            lock.lock();
            try {
                while (boardingStarted) {
                    boardingCondition.await();
                }
                serf++;

                if (serf >= 4) {
                    serf -= 4;
                    isCaptain = true;
                    serfSemaphore.release(4);
                    boatNumber++;
                    boardingStarted = true;
                } else if (serf >= 2 && hacker >= 2) {
                    serf -= 2;
                    hacker -= 2;
                    isCaptain = true;
                    serfSemaphore.release(2);
                    hackerSemaphore.release(2);
                    boatNumber++;
                    boardingStarted = true;
                }
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }

            try {
                serfSemaphore.acquire();
                board(this.id, "Serf");
                barrier.await();
                if (isCaptain) {
                    rowBoat(this.id, "Serf");
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            lock.lock();
            try {
                if (isCaptain) {
                    System.out.println("We have reached !!! boatNumber = " + boatNumber);
                    boardingStarted = false;
                    boardingCondition.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    public class HackerRunnable implements Runnable {
        private int id;

        public HackerRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            boolean isCaptain = false;
            lock.lock();
            try {
                while (boardingStarted) {
                    boardingCondition.await();
                }
                hacker++;

                if (hacker >= 4) {
                    hacker -= 4;
                    isCaptain = true;
                    hackerSemaphore.release(4);
                    boatNumber++;
                    boardingStarted = true;
                } else if (hacker >= 2 && serf >= 2) {
                    serf -= 2;
                    hacker -= 2;
                    isCaptain = true;
                    hackerSemaphore.release(2);
                    serfSemaphore.release(2);
                    boatNumber++;
                    boardingStarted = true;
                }
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }

            try {
                hackerSemaphore.acquire();
                board(id, "Hacker");
                barrier.await();
                if (isCaptain) {
                    rowBoat(id, "Hacker");
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            lock.lock();
            try {
                if (isCaptain) {
                    System.out.println("We have reached !!! boatNumber = " + boatNumber);
                    boardingStarted = false;
                    boardingCondition.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private void board(int id, String type) {
        System.out.println(type + id + " has boarded the boat. boatNumber = " + boatNumber);
    }

    private void rowBoat(int id, String type) {
        System.out.println(type + id + " is the captain, and rowing the boat, boatNumber = " + boatNumber);
    }

    public void solve() throws InterruptedException {
        List<Thread> serfThreads = new ArrayList<>();
        List<Thread> hackerThreads = new ArrayList<>();
        for (int i = 0; i < this.N; i++) {
            serfThreads.add(new Thread(new SerfRunnable(i), "Serf-Thread-" + i));
        }

        for (int i = 0; i < this.M; i++) {
            hackerThreads.add(new Thread(new HackerRunnable(i), "Hacker-Thread-" + i));
        }

        for (Thread t : serfThreads) {
            t.start();
        }

        for (Thread t : hackerThreads) {
            t.start();
        }

        for (Thread t : serfThreads) {
            t.join();
        }
        for (Thread t : hackerThreads) {
            t.join();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        int N = 10;
        int M = 10;
        new RiverCrossing(N, M).solve();
    }
}
