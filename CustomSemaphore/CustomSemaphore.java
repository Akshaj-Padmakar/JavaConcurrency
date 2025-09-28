package CustomSemaphore;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


public class CustomSemaphore {
    private final Lock lock;
    private final Condition nonFairCondition;
    private final Queue<Node> waiters;
    private int permits;
    private final boolean fair;

    public CustomSemaphore(int permits, boolean fair) {
        if(permits < 0) {
            throw new IllegalArgumentException("Number of permits cannot be less than 0");
        }
        this.permits = permits;
        this.fair = fair;
        this.waiters = new ArrayDeque<>();
        this.lock = new ReentrantLock();
        this.nonFairCondition = lock.newCondition();
    }

    public void acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try{
            if(permits > 0 && (!fair || waiters.isEmpty())){
                permits--;
                return;
            }

            if(!fair) {
                while(permits == 0){
                    nonFairCondition.await();
                }
                permits--;
                return;
            } else {
                Node node = new Node(lock.newCondition());
                waiters.add(node);
                
                while(true){
                    if(permits > 0 && waiters.peek() == node){
                        permits --;
                        waiters.poll();
                        return;
                    } else{
                        node.getCondition().await();
                    }
                }
            }
        } catch (InterruptedException e){
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }

    public boolean tryAcquire() {
        lock.lock();
        try{
            if(permits > 0 && (!fair || waiters.isEmpty())){
                permits--;
                return true;
            } else {
                return false;
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean tryAcquire(long duration, TimeUnit timeUnit) throws InterruptedException {
        long nano = timeUnit.toNanos(duration);
        long deadline = nano + System.nanoTime();

        lock.lockInterruptibly();
        try{
            if(permits > 0 && (!fair || waiters.isEmpty())) {
                permits--;
                return true;
            }

            if(!fair) {
                while(permits == 0){
                    if(nano <= 0L) {
                        return false;
                    }
                    nano = nonFairCondition.awaitNanos(nano);
                }
                permits--;
                return true;
            } else {
                Node node = new Node(lock.newCondition());
                waiters.add(node);
                while(true){
                    if(permits > 0 && waiters.peek() == node){
                        permits--;
                        waiters.poll();
                        return true;
                    } else {
                        if(nano <= 0L) {
                            waiters.remove(node);
                            return false;
                        }
                        nano = node.getCondition().awaitNanos(nano);
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void release() {
        lock.lock();

        try{
            permits++;
            if(fair){
                Node node = waiters.peek();
                if(node != null){
                    node.getCondition().signal();
                } 
            } else {
                nonFairCondition.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    public int availablePermits() {
        lock.lock();
        try{
            return permits;
        } finally {
            lock.unlock();
        }
    }

    private class Node {
        private Condition condition;
        boolean released = false; // stores whether the current Node is release/ allowed to proceed.
        public Node(Condition condition){
            this.condition = condition;
        }

        public Condition getCondition() {
            return condition;
        }
    }

    // ---------- Simple test / demonstration ----------
    public static void main(String[] args) throws InterruptedException {
        final CustomSemaphore sem = new CustomSemaphore(1, true); // change fairness flag here

        Runnable r = () -> {
            String name = Thread.currentThread().getName();
            try {
                System.out.println(name + " -> waiting to acquire");
                sem.tryAcquire(1000, TimeUnit.SECONDS);
                System.out.println(name + " -> acquired");
                Thread.sleep(5000); // simulate work
            } catch (InterruptedException e) {
                System.out.println(name + " -> interrupted");
                return;
            } finally {
                try {
                    sem.release();
                    System.out.println(name + " -> released");
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        };

        Thread t1 = new Thread(r, "T1");
        Thread t2 = new Thread(r, "T2");
        Thread t3 = new Thread(r, "T3");

        t1.start();
        Thread.sleep(10);
        t2.start();
        Thread.sleep(10);
        t3.start();

        t1.join();
        t2.join();
        t3.join();
    }
}