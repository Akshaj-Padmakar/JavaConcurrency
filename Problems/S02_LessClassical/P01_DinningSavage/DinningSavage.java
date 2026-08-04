package Problems.S02_LessClassical.P01_DinningSavage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class DinningSavage {
    private int N; // number of savage
    private int M; // capacity of pot
    private int currentServings = 0;

    private Lock lock = new ReentrantLock();
    private Condition cookCondition = lock.newCondition();
    private Condition savageCondition = lock.newCondition();

    private boolean cookRequeted = false;

    public DinningSavage(int N, int M) {
        this.N = N;
        this.M = M;
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
                while (currentServings > 0) {
                    cookCondition.await();
                }
                System.out.println("Cook is signalled to cook....");
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }

            System.out.println("Cook has started cooking !");

            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }

            lock.lock();
            try {
                System.out.println("COOKED !");
                currentServings = M;
                savageCondition.signalAll();
                cookRequeted = false;
            } finally {
                lock.unlock();
            }

        }
    }

    private class SavageRunnable implements Runnable {
        int id;

        private SavageRunnable(int id) {
            this.id = id;
        }

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
                while (currentServings == 0) {
                    if (!cookRequeted) {
                        cookRequeted = true;
                        cookCondition.signal();
                        System.out.println("No servings left in pot, signalling cook to make more.");
                    }
                    savageCondition.await();
                }
                System.out.println("Savage-" + this.id + " is taking a serving from pot.");
                currentServings--;
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }

        private void eat() {
            System.out.println("Savage-" + this.id + " is eating....");
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            }
            System.out.println("Savage-" + this.id + " is done eating !!!");
        }
    }

    public void solve() throws InterruptedException {
        Thread cookThread = new Thread(new CookRunnable(), "Cook-Thread");

        List<Thread> savageThreads = new ArrayList<>();
        for (int i = 0; i < this.N; i++) {
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

    public static void main(String args[]) throws InterruptedException {
        new DinningSavage(5, 2).solve();
    }
}
