package ChildCareProblem;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ChildCare {
    private Random rnd = new Random();
    private Lock lock = new ReentrantLock();
    private Condition childCondition = lock.newCondition();
    private Condition menCondition = lock.newCondition();
    private int men = 0;
    private int child = 0;
    

    public class MenRunnable implements Runnable {
        private final int id;
        public MenRunnable(int id) {
            this.id = id;
        }
        
        @Override
        public void run() {
            try{
                Thread.sleep(rnd.nextInt(500));
                enter();
                Thread.sleep(rnd.nextInt(500));
                exit();
            } catch (InterruptedException e){
                Thread.currentThread().interrupt();
            } 
        }

        private void enter() {
            lock.lock();
            try {
                System.out.println("Man" + this.id + " is trying to enter.");
                men++;
                System.out.println("Man" + this.id + " has entered. currentMen = " + men + " currentChild = " + child);
                childCondition.signalAll();
            } finally {
                lock.unlock();
            }
        }
        private void exit() {
            lock.lock();
            try {
                System.out.println("Man-" + this.id + " is trying to exit.");
                while(3*(men-1) < child) {
                    menCondition.await();
                }
                men--;
                System.out.println("Man-" + this.id + " has exited. currentMen = " + men + " currentChild = " + child);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
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
            try{
                Thread.sleep(rnd.nextInt(500));
                enter();
                Thread.sleep(rnd.nextInt(500));
                exit();
            } catch (InterruptedException e){
                Thread.currentThread().interrupt();
            } 
        }

        private void enter() {
            lock.lock();
            try {
                System.out.println("Child-" + this.id + " is trying to enter.");
                while(child + 1 > 3*men) {
                    childCondition.await();
                }
                child++;
                System.out.println("Child-" + this.id + " has entered. currentMen = " + men + " currentChild = " + child);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }

        private void exit() {
            lock.lock();
            try {
                child--;
                System.out.println("Child-" + this.id + " has exiting. currentMen = " + men + " currentChild = " + child);
                menCondition.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }
    public void solve(int men, int child) throws InterruptedException {
        List<Thread> menThreads = new ArrayList<>();
        List<Thread> childThreads = new ArrayList<>();

        for(int i = 0; i < men; i++) {
            menThreads.add(new Thread(new MenRunnable(i), "Men-Thread-" + i));
        }

        for(int i = 0; i < child; i++) {
            childThreads.add(new Thread(new ChildRunnable(i), "Child-Thread-" + i));
        }

        for(Thread t : menThreads) {
            t.start();
        }

        for(Thread t : childThreads) {
            t.start();
        }

        for(Thread t : menThreads) {
            t.join();
        }

        for(Thread t : childThreads) {
            t.join();
        }
    }
    public static void main(String[] args) throws InterruptedException {
        int men = 2;
        int child = 6;
        new ChildCare().solve(men, child);
    }
}
