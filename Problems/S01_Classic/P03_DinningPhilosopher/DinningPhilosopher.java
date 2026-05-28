package Problems.S01_Classic.P03_DinningPhilosopher;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Semaphore;

public class DinningPhilosopher {
    private final int n;
    private final List<Semaphore> fork;
    private final Semaphore criticalSemaphore;
    private final Random rnd = new Random();

    public DinningPhilosopher(int n) {
        this.n = n;
        this.fork = new ArrayList<>();
        this.criticalSemaphore = new Semaphore(n - 1);
        for (int i = 0; i < n; i++) {
            fork.add(new Semaphore(1));
        }
    }

    private class PhilosopherRunnable implements Runnable {
        private int id;

        public PhilosopherRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            boolean criticalAcquired = false;
            boolean leftAcquired = false;
            boolean rightAcquired = false;
            int left = this.id;
            int right = (this.id + 1) % DinningPhilosopher.this.n;
            try {
                criticalSemaphore.acquire();
                criticalAcquired = true;

                fork.get(left).acquire();
                leftAcquired = true;
                System.out.println("Acquired left fork, with forkId: " + left + " for Philosopher: " + this.id);

                fork.get(right).acquire();
                rightAcquired = true;
                System.out.println("Acquired right fork, with forkId: " + right + " for Philosopher: " + this.id);

                System.out.println("Acquired forks, starting eating, for Philosopher: " + this.id);
                Thread.sleep(rnd.nextInt(500));

                fork.get(left).release();
                leftAcquired = false;
                System.out.println("released left fork, with forkId: " + left + " for Philosopher: " + this.id);

                fork.get(right).release();
                rightAcquired = false;
                System.out.println("released right fork, with forkId: " + right + " for Philosopher: " + this.id);
                criticalSemaphore.release();
                criticalAcquired = false;
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                if (criticalAcquired) {
                    System.out.println("[CRASHED] for this philosopher:" + this.id + " criticalSemaphore was occupied");
                    criticalSemaphore.release();
                }
                if (rightAcquired) {
                    System.out.println("[CRASHED] for this philosopher:" + this.id + " rightFork was occupied");
                    fork.get(right).release();
                }

                if (leftAcquired) {
                    System.out.println("[CRASHED] for this philosopher:" + this.id + " leftFork was occupied");
                    fork.get(left).release();
                }
            }
        }
    }

    public void solve() throws InterruptedException {
        List<Thread> threads = new ArrayList<>(); // Philosophers Threads

        for (int i = 0; i < this.n; i++) {
            threads.add(new Thread(new PhilosopherRunnable(i), "Philosopher-" + i));
        }
        for (Thread t : threads) {
            t.start();
        }

        for (Thread t : threads) {
            t.join();
        }
    }
}
