package ConcurrentStructures.CustomReentrantLock;

import ConcurrentStructures.CustomReentrantLock.CustomReentrantLock.SimpleCondition;

public class CustomReentrantLockTest {
    private int count = 1;
    private CustomReentrantLock lock = new CustomReentrantLock();
    private SimpleCondition oddCondition = lock.newCondition();
    private SimpleCondition evenCondition = lock.newCondition();
    
    public class OddRunnable implements Runnable {
        private int n;
        public OddRunnable(int n) {
            this.n = n;
        }

        @Override
        public void run() {
            while(true) {
                lock.lock();
                try {
                    if(this.n < count) {
                        evenCondition.signal();
                        break;
                    }
                    while(count % 2 == 0) {
                        oddCondition.await();
                    }
                    if(count > n) {
                        evenCondition.signal();
                        break;
                    }
                    System.out.println("count = " + count + " Thread = " + Thread.currentThread().getName());
                    count++;
                    evenCondition.signal();
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                }
            }
        }
    }
    public class EvenRunnable implements Runnable {
        private int n;
        public EvenRunnable(int n) {
            this.n = n;
        }
        
        @Override
        public void run() {
            while(true) {
                lock.lock();
                try {
                    if(this.n < count) {
                        oddCondition.signal();
                        break;
                    }
                    while(count % 2 == 1) {
                        evenCondition.await();
                    }
                    if(count > n) {
                        oddCondition.signal();
                        break;
                    }
                    System.out.println("count = " + count + " Thread = " + Thread.currentThread().getName());
                    count++;
                    oddCondition.signal();
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                }
            }
        }
    }
    public void solve(int n) throws InterruptedException {
        Thread oddThread = new Thread(new OddRunnable(n), "Odd-Thread");
        Thread evenThread = new Thread(new EvenRunnable(n), "Even-Thread");

        oddThread.start();
        evenThread.start();

        oddThread.join();
        evenThread.join();
    }
    public static void main(String[] args) throws InterruptedException {
        int n = 12;
        new CustomReentrantLockTest().solve(n);
    }
}
