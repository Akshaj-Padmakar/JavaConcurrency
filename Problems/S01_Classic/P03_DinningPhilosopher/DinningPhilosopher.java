package Problems.S01_Classic.P03_DinningPhilosopher;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Semaphore;

public class DinningPhilosopher {
    private final int n;
    private final List<Semaphore> forkSemaphores;
    private final Semaphore criticalSemaphore;

    private final Random rnd = new Random();

    public DinningPhilosopher(int n) {
        if (n <= 1) {
            throw new IllegalArgumentException("Number of philosophers must be > 1");
        }
        this.n = n;
        this.forkSemaphores = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            this.forkSemaphores.add(new Semaphore(1));
        }
        this.criticalSemaphore = new Semaphore(this.n - 1);
        // N - 1 nodes try to acquire N resources, where each node
        // and resources are in cycle, can resolve freely without deadlock.
    }

    private class PhilosopherRunnable implements Runnable {
        private final int id;

        public PhilosopherRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            boolean criticalAcquired = false;
            boolean leftAcquired = false;
            boolean rightAcquired = false;

            try {
                criticalSemaphore.acquire();
                criticalAcquired = true;

                getLeftForkSemaphore().acquire();
                leftAcquired = true;
                logLeftForkAcquired();

                getRightForkSemaphore().acquire();
                rightAcquired = true;
                logRightForkAcquired();


                logStartingEating();
                eat();

                getRightForkSemaphore().release();
                rightAcquired = false;
                logRightForkReleased();

                getLeftForkSemaphore().release();
                leftAcquired = false;
                logLeftForkRelease();

                criticalSemaphore.release();
                criticalAcquired = false;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                if (criticalAcquired) {
                    System.out.println("[CRASHED]");
                    criticalSemaphore.release();
                }

                if (leftAcquired) {
                    System.out.println("[CRASHED]");
                    getLeftForkSemaphore().release();
                }

                if (rightAcquired) {
                    System.out.println("[CRASHED]");
                    getRightForkSemaphore().release();
                }
            }
        }

        private Semaphore getLeftForkSemaphore() {
            return forkSemaphores.get(this.id);
        }

        private Semaphore getRightForkSemaphore() {
            return forkSemaphores.get((this.id + 1) % n);
        }

        private void eat() throws InterruptedException {
            Thread.sleep(200 + rnd.nextInt(100));
        }

        private void logLeftForkAcquired() {
            System.out.println("Acquired left fork, with forkId: " + this.id + " for Philosopher: " + this.id);
        }

        private void logRightForkAcquired() {
            System.out.println("Acquired right fork, with forkId: " + (this.id + 1) % n + " for Philosopher: " + this.id);
        }

        private void logStartingEating() {
            System.out.println("Acquired forks, starting eating, for Philosopher: " + this.id);
        }

        private void logRightForkReleased() {
            System.out.println("Released right fork, with forkId: " + (this.id + 1) % n + " for Philosopher: " + this.id);
        }

        private void logLeftForkRelease() {
            System.out.println("Released left fork, with forkId: " + this.id + " for Philosopher: " + this.id);
        }
    }

    public void solve() throws InterruptedException {
        List<Thread> philosopherThreads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            philosopherThreads.add(new Thread(new PhilosopherRunnable(i), "Philosopher-Thread-" + i));
        }
        for (Thread t : philosopherThreads) {
            t.start();
        }

        for (Thread t : philosopherThreads) {
            t.join();
        }
    }


    public static void main(String[] args) throws InterruptedException {
        new DinningPhilosopher(5).solve();
    }

}
