package Problems.S02_LessClassical.P02_BarberShop;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BarberShop { // This is harder fair version [FIFO]
    private final int nChairs;
    private final int nCustomers;

    private final Lock lock;
    private final Condition barberSleepingCondition;
    private final Condition barberNxtIterationCondition;
    private final Queue<Node> waitingList; // for barber to wake up.
    private int customerCnt = 0; // for customer to balk.
    private final Random rnd = new Random();
    private boolean stop = false;


    public BarberShop(int nChairs, int nCustomers) {
        this.nChairs = nChairs;
        this.nCustomers = nCustomers;
        this.lock = new ReentrantLock();
        this.barberSleepingCondition = lock.newCondition();
        this.barberNxtIterationCondition = lock.newCondition();
        this.waitingList = new LinkedList<>();
    }

    private class Node {
        private final int customerId;
        private final Condition condition;
        private boolean barberReady;
        private boolean hairCutDoneFlag;
        private boolean customerLeftFlag; // this flag is set only when customer got a hair cut and left.

        public Node(int customerId) {
            this.customerId = customerId;
            this.condition = lock.newCondition();
            this.barberReady = false;
            this.hairCutDoneFlag = false;
            this.customerLeftFlag = false;
        }

        public int getCustomerId() {
            return this.customerId;
        }

        public Condition getCondition() {
            return this.condition;
        }

        public boolean getBarberReady() {
            return this.barberReady;
        }

        public void setBarberReady(boolean value) {
            this.barberReady = value;
        }

        public boolean getHairCutDoneFlag() {
            return this.hairCutDoneFlag;
        }

        public void setHairCutDoneFlag(boolean value) {
            this.hairCutDoneFlag = value;
        }

        public boolean getCustomerLeftFlag() {
            return this.customerLeftFlag;
        }

        public void setCustomerLeftFlag(boolean value) {
            this.customerLeftFlag = value;
        }
    }


    private class BarberRunnable implements Runnable {
        @Override
        public void run() {
            while (true) {
                Node customer = cutOrSleep();
                if (stop || customer == null) {
                    return;
                }

                doCutHair();

                hairCutDone(customer);
            }
        }

        private Node cutOrSleep() {
            lock.lock();
            try {
                while (waitingList.isEmpty() && !stop) {
                    logBarberSleeping();
                    barberSleepingCondition.await();
                }
                if (stop) {
                    return null;
                }
                Node customer = waitingList.poll();
                customer.setBarberReady(true);
                customer.getCondition().signal();
                logBarberStartedCuttingHair(customer);
                return customer;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            } finally {
                lock.unlock();
            }
        }

        private void doCutHair() {
            try {
                Thread.sleep(rnd.nextInt(500));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        private void hairCutDone(Node customer) {
            lock.lock();
            try {
                customer.setHairCutDoneFlag(true);
                customer.getCondition().signal();
                while (!customer.getCustomerLeftFlag()) {
                    barberNxtIterationCondition.await();
                }

            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }

        private void logBarberSleeping() {
            System.out.println("Barber going to sleep....zzzz....");
        }

        private void logBarberStartedCuttingHair(Node customer) {
            System.out.println("Barber has starting cutting hair for customer-" + customer.getCustomerId());
        }
    }

    private class CustomerRunnable implements Runnable {
        private final Node node;

        public CustomerRunnable(int id) {
            this.node = new Node(id);
        }

        @Override
        public void run() {
            wander();

            if (!waitOrBalk()) {
                return;
            }

            getHairCut();
        }

        private void wander() {
            try {
                Thread.sleep(rnd.nextInt(500));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean waitOrBalk() {
            lock.lock();
            try {
                if (customerCnt == nChairs + 1) {
                    balk();
                    return false;
                }
                customerCnt++;
                waitingList.add(this.node);
                if (customerCnt == 1) {
                    barberSleepingCondition.signal();
                }
                while (!this.node.getBarberReady()) {
                    this.node.getCondition().await();
                }
                logEntry();
                return true;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                lock.unlock();
            }
        }

        private void getHairCut() {
            lock.lock();
            try {
                while (!this.node.getHairCutDoneFlag()) {
                    this.node.getCondition().await();
                }
                customerCnt--;
                this.node.setCustomerLeftFlag(true);
                barberNxtIterationCondition.signal();
                logExit();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }

        private void balk() {
            System.out.println("Barber shop is full, Customer-" + this.node.getCustomerId() + " is leaving the shop.");
        }

        private void logEntry() {
            System.out.println("Customer-" + this.node.getCustomerId() + " has occupied the hair-cut chair.");
        }

        private void logExit() {
            System.out.println("Customer-" + this.node.getCustomerId() + " is done with the hair-cut. Leaving the shop.");
        }
    }

    private void doStop() {
        lock.lock();
        try {
            stop = true;
            barberSleepingCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    public void solve() throws InterruptedException {
        Thread barberThread = new Thread(new BarberRunnable(), "Barber-Thread");
        List<Thread> customerThreads = new ArrayList<>();

        for (int i = 0; i < nCustomers; i++) {
            customerThreads.add(new Thread(new CustomerRunnable(i), "Customer-Thread-" + i));
        }

        barberThread.start();
        for (Thread t : customerThreads) {
            t.start();
        }
        for (Thread t : customerThreads) {
            t.join();
        }
        doStop();
    }

    public static void main(String[] args) throws InterruptedException {
        new BarberShop(3, 15).solve();
    }
}
