package SushiBarProblem;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SushiBar {
    private final int CAPACITY = 5;
    private Lock lock = new ReentrantLock();
    private Condition notDinning = lock.newCondition();
    private final Random rnd = new Random();
    private int waiting = 0;
    private int inside = 0;
    private boolean started = false;
    
    public class CustomerRunnable implements Runnable {
        private int id;
        public CustomerRunnable(int id){
            this.id = id;
        }

        @Override
        public void run() {
            try {
                enter();
                Thread.sleep(rnd.nextInt(500));
                exit();
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void enter() {
            lock.lock();
            try {
                waiting++;
                System.out.println("Customer-" + this.id + " is in waiting queue :/");
                while(started && inside > 0){
                    notDinning.await();
                }
                waiting--;
                inside++;
                System.out.println("Customer-" + this.id + " has entered the SushiBar !");
                if(inside == CAPACITY){
                    started = true;
                } else {
                    // notDinning.signal();
                }
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }

        private void exit() {
            lock.lock();
            try {
                inside--;
                if(inside == 0){
                    started = false;
                    notDinning.signalAll();
                }
                System.out.println("Customer-" + this.id + " has exited the SushiBar !");
            } finally {
                lock.unlock();
            }
        }
    }
    public void solve(int customer) throws InterruptedException{
        List<Thread> customerThreads = new ArrayList<>();
        for(int i = 0; i < customer; i++){
            customerThreads.add(new Thread(new CustomerRunnable(i), "Customer-Thread-" + i));
        }

        for(Thread t : customerThreads) {
            t.start();
        }

        for(Thread t : customerThreads) {
            t.join();
        }
    }
    public static void main(String[] args) throws InterruptedException {
        int customer = 12;
        new SushiBar().solve(customer);
    }
}