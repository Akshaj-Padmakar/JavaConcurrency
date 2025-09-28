package Problems.LessClassical.BarberShop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BarberShop { // This is harder fair version [FIFO]
    private final int N; // number of seats
    private final int M; // number of customer

    private int current = 0;
    private Lock lock = new ReentrantLock();
    private Condition sleepCondition = lock.newCondition();
    
    private Queue<Integer> waitingList = new LinkedList<>();
    private Map<Integer, Condition> customerCondition = new HashMap<>();
    private Random rnd = new Random();


    public BarberShop(int N, int M) {
        this.N = N;
        this.M = M;
    }

    public class BarberRunnable implements Runnable {
        public BarberRunnable() {

        }

        @Override
        public void run() {
            while(true) {
                try {
                    cutHair();
                } catch (InterruptedException e) {
                    System.out.println("Time to close the shop, BYEEEEE !");
                    return;
                }
            }
        }

        private void cutHair() throws InterruptedException {
            lock.lockInterruptibly();
            try {
                while(current == 0) {
                    sleepCondition.await();
                }
                Integer head = waitingList.peek();
                System.out.println("Starting HairCut for customer with id: " + head);
            } finally {
                lock.unlock();
            }
            
            Thread.sleep(rnd.nextInt(400));

            lock.lockInterruptibly();
            try {
                Integer head = waitingList.poll();
                System.out.println("HairCut Done for customer with id: " + head + " Singalling to exit.");

                
                customerCondition.get(head).signal();
                customerCondition.get(head).await(); // handle spurious wakeup. [ez]
            } finally {
                lock.unlock();
            }

        }
    }

    public class CustomerRunnable implements Runnable {
        private int id;
        public CustomerRunnable(int id) {
            this.id = id;
            customerCondition.put(this.id, BarberShop.this.lock.newCondition()); // put new condition for each id.
        }

        @Override
        public void run() {
            try {
                Thread.sleep(rnd.nextInt(500) + (this.id == 10 ? 300 : 0));
                if(!getHairCut()) {
                    return;
                }
            } catch (InterruptedException e) {
                return;
            }
        }

        private boolean getHairCut() {
            try {
                lock.lockInterruptibly();
                if(current == BarberShop.this.N) {
                    System.out.println("Customer-" + this.id + " Leaving, Shop is full, current = " + current);
                    return false;
                }
                current++;
                waitingList.add(this.id);
                System.out.println("Customer-" + this.id + " have taken a seat, current = " + current + " waitingList = " + waitingList.toString());
                sleepCondition.signal();

                customerCondition.get(this.id).await();
                current--;
                System.out.println("Customer-" + this.id + " hairCut is done current = " + current + " waitingList = " + waitingList.toString());
                customerCondition.get(this.id).signal();
                return true;
            } catch(InterruptedException e) {
                e.printStackTrace();
                return false;
            } finally {
                lock.unlock();
            }
        }
    }
    public void solve() throws InterruptedException {
        List<Thread> customerThreads = new ArrayList<>();
        Thread barberThread = new Thread(new BarberRunnable(), "Barber-Thread");
        for(int i = 0; i < M; i++) {
            customerThreads.add(new Thread(new CustomerRunnable(i), "Customer-Thread-" + i));
        }
        barberThread.start();
        for(Thread t : customerThreads) {
            t.start();
        }
        
        for(Thread t : customerThreads) {
            t.join();
        }
        barberThread.interrupt();
    }
    public static void main(String[] args) throws InterruptedException {
        int N = 3;
        int M = 12;

        new BarberShop(N, M).solve();
    }
}
