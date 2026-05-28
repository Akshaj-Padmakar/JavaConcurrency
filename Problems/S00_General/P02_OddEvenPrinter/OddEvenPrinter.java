package Problems.S00_General.P02_OddEvenPrinter;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class OddEvenPrinter {

    private final int n;
    private final Lock lock = new ReentrantLock();
    private final Condition oddCondition = lock.newCondition();
    private final Condition evenCondition = lock.newCondition();
    int current = 0;

    public OddEvenPrinter(int n) {
        this.n = n;
    }

    public void solve() throws InterruptedException {
        Thread oddThread = new Thread(new OddRunnable(), "Odd-Thread");
        Thread evenThread = new Thread(new EvenRunnable(), "Even-Thread");

        oddThread.start();
        evenThread.start();

        oddThread.join();
        evenThread.join();
    }

    private class OddRunnable implements Runnable {
        @Override
        public void run() {
            while (true) {
                lock.lock();
                try {
                    while (current <= n && current % 2 == 0) {
                        oddCondition.await();
                    }
                    if (current > n) { // Signaling here other thread ? Not needed.
                        break;
                    }
                    printCurrentValueAndThread();
                    current++;
                    evenCondition.signal();
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    private class EvenRunnable implements Runnable {
        @Override
        public void run() {
            while (true) {
                lock.lock();
                try {
                    while (current <= n && current % 2 == 1) {
                        evenCondition.await();
                    }
                    if (current > n) {
                        break;
                    }
                    printCurrentValueAndThread();
                    current++;
                    oddCondition.signal();
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    private void printCurrentValueAndThread() {
        System.out.println(
                "current = " + current + ", printed by Thread = " + Thread.currentThread().getName());
    }
}
