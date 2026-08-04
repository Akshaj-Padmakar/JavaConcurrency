package Problems.S02_LessClassical.P02_BarberShop;

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

    private final Lock lock = new ReentrantLock();
    private final Queue<Integer> waitingList = new LinkedList<>();
    private final Map<Integer, Condition> customerCondition = new HashMap<>();

    private final Map<Integer, Boolean> barberReady = new HashMap<>();
    private final Map<Integer, Boolean> haircutDone = new HashMap<>();
    private final Map<Integer, Boolean> customerLeft = new HashMap<>();

    private final Condition barberSleepingCondition = lock.newCondition();

    private int curCustomerCnt = 0;

    private final Random rnd = new Random();

    public BarberShop(int N, int M) {
        this.N = N;
        this.M = M;
    }

    private class BarberRunnable implements Runnable {
        @Override
        public void run() {
            while (true) {
                lock.lock();
                Integer customerId = 0;
                try {
                    while (curCustomerCnt == 0) {
                        System.out.println("Barber going to sleep....zzzz....");
                        barberSleepingCondition.await();
                    }
                    customerId = waitingList.poll();

                    barberReady.put(customerId, true);
                    customerCondition.get(customerId).signal();

                    System.out.println("Barber has starting cutting hair for customer-" + customerId);

                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                    return;
                } finally {
                    lock.unlock();
                }

                try {
                    Thread.sleep(150);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                    return;
                }

                lock.lock();
                try {
                    cutHair(customerId);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                } finally {
                    lock.unlock();
                }
            }
        }

        private void cutHair(Integer customerId) throws InterruptedException {
            System.out.println("Barber has completed hair cut for customer-" + customerId);

            haircutDone.put(customerId, true);
            customerCondition.get(customerId).signal();
            while (!customerLeft.get(customerId)) {
                customerCondition.get(customerId).await();
            }

            customerLeft.put(customerId, false);
        }
    }

    private class CustomerRunnable implements Runnable {
        private final int id;

        public CustomerRunnable(int id) {
            this.id = id;
            customerCondition.put(this.id, lock.newCondition());
            barberReady.put(this.id, false);
            haircutDone.put(this.id, false);
            customerLeft.put(this.id, false);
        }

        @Override
        public void run() {
            try {
                Thread.sleep(rnd.nextInt(500) + (this.id == 10 ? 300 : 0));
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }

            lock.lock();
            try {
                if (isShopFull()) { // shop full.
                    balk();
                    return;
                }
                curCustomerCnt++;
                waitingList.add(this.id);
                System.out.println("Customer-" + this.id + " have taken a seat, current = " + curCustomerCnt
                        + " waitingList = " + waitingList.toString());

                if (curCustomerCnt == 1) {
                    barberSleepingCondition.signal();
                }
                while (!barberReady.get(this.id)) {
                    customerCondition.get(this.id).await();
                }
                getHairCut();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }
        }

        private boolean isShopFull() {
            return curCustomerCnt == N;
        }

        private void balk() {
            System.out.println("Shop is full !!, customer-" + this.id + " is exiting...");
        }

        private void getHairCut() throws InterruptedException {
            curCustomerCnt--;
            System.out
                    .println("Customer-" + this.id + " has taken the barberChair. current = " + curCustomerCnt
                            + " waitingList = "
                            + waitingList.toString());
            while (!haircutDone.get(this.id)) {
                customerCondition.get(this.id).await();
            }
            System.out
                    .println("Customer-" + this.id + " hairCut is done current = " + curCustomerCnt + " waitingList = "
                            + waitingList.toString());
            barberReady.put(this.id, false);
            haircutDone.put(this.id, false);
            customerLeft.put(this.id, true);
            customerCondition.get(this.id).signal();
        }
    }

    public void solve() throws InterruptedException {
        Thread barberThread = new Thread(new BarberRunnable(), "Barber-Thread");
        List<Thread> customerThreads = new ArrayList<>();
        for (int i = 0; i < M; i++) {
            customerThreads.add(new Thread(new CustomerRunnable(i), "Customer-Thread-" + i));
        }

        barberThread.start();
        for (Thread t : customerThreads) {
            t.start();
        }

        barberThread.join();
        for (Thread t : customerThreads) {
            t.join();
        }
    }

    public static void main(String args[]) throws InterruptedException {
        new BarberShop(5, 10).solve();
    }
}
