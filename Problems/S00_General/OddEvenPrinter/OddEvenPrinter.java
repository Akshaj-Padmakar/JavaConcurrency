package Problems.S00_General.OddEvenPrinter;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class OddEvenPrinter {
    private int n;
    private int current = 0;
    private Lock lock = new ReentrantLock();
    private Condition evenCondition = lock.newCondition();
    private Condition oddCondition = lock.newCondition();
    
    public OddEvenPrinter(int n) {
        this.n = n;
    }

    public class OddRunnable implements Runnable {
        public OddRunnable() {}

        @Override
        public void run() {
            while(true) {
                lock.lock();
                try {
                    while(current % 2 == 0 && current <= n) {
                        oddCondition.await();
                    }
                    if(current > n) {
                        evenCondition.signal();
                        break;
                    }
                    System.out.println("current = " + current + " Thread = " + Thread.currentThread().getName());
                    current++;
                    evenCondition.signal();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();  
                } finally {
                    lock.unlock();
                }
            }
        }

    }

    public class EvenRunnable implements Runnable {
        public EvenRunnable() {}

        @Override
        public void run() {
            while(true) {
                lock.lock();
                try {
                    while(current % 2 == 1 && current <= n) {
                        evenCondition.await();
                    }
                    if(current > n) {
                        oddCondition.signal();
                        break;
                    }
                    System.out.println("current = " + current + " Thread = " + Thread.currentThread().getName());
                    current++;
                    oddCondition.signal();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();  
                } finally {
                    lock.unlock();
                }
            }
        }
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
        int n = 16;
        new OddEvenPrinter(n).solve();
    }
}
