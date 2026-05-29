package Problems.S02_LessClassical.P01_DinningSavage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class DinningSavage {
    private final int M; // Number of servings.
    private final int N; // Number of savages.
    private int current; // Current servings left.

    private final Lock lock = new ReentrantLock();
    private final Condition empty = lock.newCondition();
    private final Condition cook = lock.newCondition();
    private boolean cookRequested = false;

    public DinningSavage(int M, int N) {
        this.M = M;
        this.N = N;
        this.current = M;
    }

    private class CookRunnable implements Runnable {
        @Override
        public void run() {
            while (true) {
                putServingsInPot();
            }
        }

        private void putServingsInPot() {
            lock.lock();
            try {
                while (current > 0) {
                    cook.await();
                }
                System.out.println("Cook has started cooking...");
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }
            try {
                Thread.sleep(500); // Allowing other savage threads to request more servings...
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }

            lock.lock();
            try {
                System.out.println("Cook is done cooking !");
                current = M;
                empty.signalAll();
            } finally {
                cookRequested = false;
                lock.unlock();
            }
        }
    }

    private class SavageRunnable implements Runnable {
        @Override
        public void run() {
            while (true) {
                getServingFromPot();
                eat();
            }
        }

        private void getServingFromPot() {
            lock.lock();
            try {
                while (current == 0) {
                    if (!cookRequested) {
                        cook.signal();
                        cookRequested = true;
                    }
                    empty.await();
                }
                current--;
                System.out.println("Savage has taken a serving out of pot. currentServingsLeft = " + current
                        + ", currentSavageThread =" + Thread.currentThread().getName());
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }
        }

        private void eat() {
            try {
                System.out.println(
                        "Savage has started eating. currentSavageThread = " + Thread.currentThread().getName());
                Thread.sleep(200);
                System.out.println("Savage is done eating. currentSavageThread = " + Thread.currentThread().getName());
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        }
    }

    public void solve() throws InterruptedException {
        Thread cookThread = new Thread(new CookRunnable(), "Cook-Thread");
        List<Thread> savageThreads = new ArrayList<>();

        for (int i = 0; i < N; i++) {
            savageThreads.add(new Thread(new SavageRunnable(), "Savage-Thread-" + i));
        }

        cookThread.start();

        for (Thread savageThread : savageThreads) {
            savageThread.start();
        }

        cookThread.join();
        for (Thread savageThread : savageThreads) {
            savageThread.join();
        }
    }
}
