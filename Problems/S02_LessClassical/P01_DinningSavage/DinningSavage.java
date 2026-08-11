package Problems.S02_LessClassical.P01_DinningSavage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class DinningSavage {
    private final int savageCount; // Number of savages
    private final int potCapacity; // Capacity of pot

    private int currentServings = 0;
    private boolean cookRequested = false;

    private final Lock lock;
    private final Condition cookSleepCondition;
    private final Condition savageWaitCondition;

    public DinningSavage(int savageCount, int potCapacity) {
        if (savageCount <= 0 || potCapacity <= 0) {
            throw new IllegalArgumentException("savageCount & potCapacity must be > 0");
        }
        this.savageCount = savageCount;
        this.potCapacity = potCapacity;
        this.currentServings = this.potCapacity;
        this.lock = new ReentrantLock();
        this.cookSleepCondition = this.lock.newCondition();
        this.savageWaitCondition = this.lock.newCondition();
    }

    private class CookRunnable implements Runnable {
        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                cook();
            }
        }

        private void cook() {
            lock.lock();
            try {
                while (currentServings > 0) {
                    cookSleepCondition.await();
                }
                logCookRequested();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }

            logCookingStarted();
            try {
                Thread.sleep(1000);  // Cooking takes time !
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }

            lock.lock();
            try {
                logCooked();
                currentServings = potCapacity;
                cookRequested = false;
                savageWaitCondition.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private void logCookRequested() {
            System.out.println("Cook is signalled to cook....");
        }

        private void logCookingStarted() {
            System.out.println("Cook has started cooking !");
        }

        private void logCooked() {
            System.out.println("COOKED !");
        }
    }

    private class SavageRunnable implements Runnable {
        private final int id;

        public SavageRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            while (true) {
                if (!takeServingFromPot()) return;
                eat();
            }
        }

        private boolean takeServingFromPot() {
            lock.lock();
            try {
                while (currentServings == 0) {
                    if (!cookRequested) {
                        cookSleepCondition.signal();
                        cookRequested = true;
                        logCookSignalled();
                    }
                    savageWaitCondition.await();
                }
                logTakeServing();
                currentServings--;
                return true;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                lock.unlock();
            }
        }

        private void eat() {
            try {
                logSavageEating();
                Thread.sleep(500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            logSavageDoneEating();
        }

        private void logCookSignalled() {
            System.out.println("No servings left in pot, signalling cook to make more.");
        }

        private void logTakeServing() {
            System.out.println("Savage-" + this.id + " is taking a serving from pot.");

        }

        private void logSavageEating() {
            System.out.println("Savage-" + this.id + " is eating....");
        }

        private void logSavageDoneEating() {
            System.out.println("Savage-" + this.id + " is done eating !!!");
        }
    }


    public void solve() throws InterruptedException {
        Thread cookThread = new Thread(new CookRunnable(), "Cook-Thread");
        List<Thread> savageThreads = new ArrayList<>();

        for (int i = 0; i < savageCount; i++) {
            savageThreads.add(new Thread(new SavageRunnable(i), "Savage-Thread-" + i));
        }
        cookThread.start();
        for (Thread t : savageThreads) {
            t.start();
        }

        cookThread.join();
        for (Thread t : savageThreads) {
            t.join();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        new DinningSavage(5, 2).solve();
    }
}
