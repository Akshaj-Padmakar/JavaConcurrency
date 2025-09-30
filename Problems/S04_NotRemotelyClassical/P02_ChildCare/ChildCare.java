package Problems.S04_NotRemotelyClassical.P02_ChildCare;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ChildCare {
    private final int N; // adult count;
    private final int M; // child count;
    public ChildCare(int N, int M) {
        this.N = N;
        this.M = M;
    }
    private Random rnd = new Random();
    
    private final Lock lock = new ReentrantLock();
    private final Condition adultExitCondition = lock.newCondition();
    private final Condition childEntryCondition = lock.newCondition();

    private int adultCnt = 0;
    private int childCnt = 0;

    public class AdultRunnable implements Runnable {
        private final int id;
        public AdultRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            boolean locked = false;
            try {
                lock.lockInterruptibly();
                locked = true;
                adultCnt++;
                System.out.println("Adult-" + this.id + " has entered the DayCare. AdultCnt = " + adultCnt + " ChildCnt = " + childCnt);
                childEntryCondition.signalAll();
                adultExitCondition.signalAll();
            } catch (InterruptedException e) {
                System.out.println("DayCare has been closed.");
                return;
            } finally {
                if(locked) {
                    lock.unlock();
                }
            }

            try {
                Thread.sleep(rnd.nextInt(300));
            } catch (InterruptedException e) {
                System.out.println("DayCare has been closed.");
                return;
            }
            locked = false;
            try {
                lock.lockInterruptibly();
                locked = true;
                while((adultCnt - 1) * 3 < childCnt) {
                    
                    adultExitCondition.await();
                }
                adultCnt--;
                System.out.println("Adult-" + this.id + " has exited the DayCare. AdultCnt = " + adultCnt + " ChildCnt = " + childCnt);
            } catch (InterruptedException e) {
                System.out.println("DayCare has been closed.");
            } finally {
                if(locked) {
                    lock.unlock();
                }
            }
        }
    }

    public class ChildRunnable implements Runnable {
        private final int id;
        public ChildRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            boolean locked = false;
            try {
                lock.lockInterruptibly();
                locked = true;
                while(childCnt + 1 > 3 * adultCnt) {
                    childEntryCondition.await();
                }
                childCnt++;
                System.out.println("Child-" + this.id + " has entered the DayCare. AdultCnt = " + adultCnt + " ChildCnt = " + childCnt);
            } catch (InterruptedException e) {
                System.out.println("DayCare has been closed.");
            } finally {
                if(locked) {
                    lock.unlock();
                }
            }


            try {
                Thread.sleep(rnd.nextInt(300));
            } catch (InterruptedException e) {
                System.out.println("DayCare has been closed.");
                return;
            }

            locked = false;
            try {
                lock.lockInterruptibly();
                locked = true;
                childCnt--;
                childEntryCondition.signalAll();
                adultExitCondition.signalAll();
                System.out.println("Child-" + this.id + " has exited the DayCare. AdultCnt = " + adultCnt + " ChildCnt = " + childCnt);
            } catch (InterruptedException e) {
                System.out.println("DayCare has been closed.");
                return;
            } finally {
                if(locked) {
                    lock.unlock();
                }
            }


        }
    }
    public void solve() throws InterruptedException {
        List<Thread> adultThreads = new ArrayList<>();
        List<Thread> childThreads = new ArrayList<>();

        for(int i = 0; i < this.N; i++) {
            adultThreads.add(new Thread(new AdultRunnable(i), "Adult-Thread-" + i));
        }

        for(int i = 0; i < this.M; i++) {
            childThreads.add(new Thread(new ChildRunnable(i), "Child-Thread-" + i));
        }

        for(Thread t : adultThreads) {
            t.start();
        }

        for(Thread t : childThreads) {
            t.start();
        }

        // for(Thread t : adultThreads) {
        //     t.join();
        // }
        // for(Thread t : childThreads) {
        //     t.join();
        // }

        Thread.sleep(5000);

        for(Thread t : adultThreads) {
            t.interrupt();
        }
        for(Thread t : childThreads) {
            t.interrupt();
        } 
    }
    public static void main(String[] args) throws InterruptedException {
        int N = 3;
        int M = 12;
        new ChildCare(N, M).solve();
    }
}
