package Problems.S00_General.P02_OddEvenPrinter;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class OddEvenPrinter {
    private final int n;

    private final Lock lock = new ReentrantLock();
    private final Condition oddCondition = lock.newCondition();
    private final Condition evenCondition = lock.newCondition();

    private int cur = 0;

    public OddEvenPrinter(int n) {
        this.n = n;
    }

    private class OddRunnable implements Runnable {
        @Override
        public void run() {
            while (true) {
                lock.lock();
                try {
                    while (cur % 2 == 0 && cur <= n) {
                        oddCondition.await();
                    }
                    if (cur > n) {
                        // Explicit signal not needed, since the other thread breaks out
                        // sooner-or-later, being explicit is good tho.
                        evenCondition.signal();
                        break;
                    }
                    printCurrentThreadAndNumber();
                    cur++;
                    evenCondition.signal();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
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
                    while (cur % 2 == 1 && cur <= n) {
                        evenCondition.await();
                    }
                    if (cur > n) {
                        oddCondition.signal();
                        break;
                    }
                    printCurrentThreadAndNumber();
                    cur++;
                    oddCondition.signal();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    private void printCurrentThreadAndNumber() {
        System.out.println("Thread = " + Thread.currentThread().getName() + " is printing number = " + cur);
    }

    public void solve() throws InterruptedException {
        Thread oddThread = new Thread(new OddRunnable(), "Odd-Thread");
        Thread evenThread = new Thread(new EvenRunnable(), "Even-Thread");

        oddThread.start();
        evenThread.start();

        oddThread.join();
        evenThread.join();
    }

    public static void main(String[] args) throws InterruptedException {
        new OddEvenPrinter(10).solve();
    }
}
