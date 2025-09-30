package Problems.S02_LessClassical.P01_DinningSavage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class DinningSavage {
    private final int M; // number of total servings.
    private final int N; // number of savages.
    private int current;
    private Lock lock = new ReentrantLock();
    private Condition empty = lock.newCondition();
    private Condition cook = lock.newCondition();

    public DinningSavage(int M, int N) {
        this.M = M;
        this.N = N;
        this.current = M;
    }

    public class CookRunnable implements Runnable {
        public CookRunnable() {}

        @Override
        public void run() {
            while(true) {
                boolean acquired = false;
                try {
                    lock.lockInterruptibly();
                    acquired = true;
                    while(current > 0) {
                        cook.await();
                    }
                    System.out.println("Cook has started cooking.");
                    Thread.sleep(300);
                    current = DinningSavage.this.M;
                    empty.signalAll();
                } catch (InterruptedException e) {
                    System.out.println("Thread Interrupted, time expired, Cook is leaving.");
                    break;
                } finally {
                    if(acquired) {
                        lock.unlock();
                    }
                }
            }
        }
    }

    public class SavageRunnable implements Runnable {
        private int id;
        public SavageRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            while(true) {
                boolean acquired = false;
                try {
                    lock.lockInterruptibly();
                    acquired = true;
                    while(current == 0) {
                        cook.signal();
                        empty.await();
                    }
                    current--;
                    System.out.println("Savage-" + this.id + "has taken the serving. currentServingsLeft = " + current);
                    System.out.println("Savage-" + this.id + "has started eating. currentServingsLeft = " + current);
                } catch (InterruptedException e) {
                    System.out.println("Thread Interrupted, time expired, Savage: " + this.id + " is leaving.");
                    break;
                } finally {
                    if(acquired) {
                        lock.unlock();
                    }
                }

                try {
                    Thread.sleep(500);
                    System.out.println("Savage-" + this.id + "has done eating.");
                } catch (InterruptedException e) {
                    System.out.println("Thread Interrupted, time expired, Savage: " + this.id + " is leaving.");
                    break;
                }
            }   
        }
    }

    public void solve() throws InterruptedException {
        List<Thread> savageThreads = new ArrayList<>();
        Thread cookThread = new Thread(new CookRunnable(), "Cook-Thread");

        for(int i = 0; i < this.N; i++) {
            savageThreads.add(new Thread(new SavageRunnable(i), "Savage-Thread-" + i));
        }
        cookThread.start();
        for(Thread t : savageThreads) {
            t.start();
        }
        Thread.sleep(3000); // Run the application for 3s.

        cookThread.interrupt();
        for(Thread t : savageThreads) {
            t.interrupt();
        }

    }
    
    public static void main(String[] args) throws InterruptedException {
        new DinningSavage(5, 20).solve();
    }
}
