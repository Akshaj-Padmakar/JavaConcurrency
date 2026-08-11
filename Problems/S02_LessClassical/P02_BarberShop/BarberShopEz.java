package Problems.S02_LessClassical.P02_BarberShop;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BarberShopEz {
    private final int nChairs;
    private final int nCustomers;

    private final Lock lock;
    private final Condition barberSleepingCondition;
    private final Condition customerWaitingCondition;
    private final Condition customerHairCutDoneCondition;
    private final Condition barberNxtIterationCondition;

    private int customerCnt = 0;
    private boolean barberChairEmpty = false;
    private boolean hairCutDone = false;
    private boolean customerLeft = false;

    private boolean stop = false;

    private Random rnd = new Random();

    public BarberShopEz(int nChairs, int nCustomers) {
        this.nChairs = nChairs;
        this.nCustomers = nCustomers;

        this.lock = new ReentrantLock();
        this.barberSleepingCondition = this.lock.newCondition();
        this.customerWaitingCondition = this.lock.newCondition();
        this.customerHairCutDoneCondition = this.lock.newCondition();
        this.barberNxtIterationCondition = this.lock.newCondition();
    }

    private class BarberRunnable implements Runnable {
        @Override
        public void run() {
            while (true) {
                cutOrSleep();
                if (stop) {
                    return;
                }

                doHairCut();

                hairCutDone();
            }
        }

        private void cutOrSleep() {
            lock.lock();
            try {
                while (customerCnt == 0 && !stop) {
                    barberSleepingCondition.await();
                }
                if (stop) {
                    return;
                }
                barberChairEmpty = true;
                customerWaitingCondition.signal();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }

        private void doHairCut() {
            try {
                Thread.sleep(600); // Simulate hair cut.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        private void hairCutDone() {
            lock.lock();
            try {
                barberChairEmpty = false;
                hairCutDone = true;
                customerHairCutDoneCondition.signal();
                while (!customerLeft) {
                    barberNxtIterationCondition.await();
                }
                barberChairEmpty = false;
                customerLeft = false;
                hairCutDone = false;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }

        }
    }

    private class CustomerRunnable implements Runnable {
        private final int id;

        public CustomerRunnable(int id) {
            this.id = id;
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

        private boolean waitOrBalk() { // false => balk
            lock.lock();
            try {
                if (customerCnt == nChairs + 1) {
                    balk();
                    return false;
                }
                customerCnt++;
                if (customerCnt == 1) {
                    barberSleepingCondition.signal();
                }

                while (!barberChairEmpty) {
                    customerWaitingCondition.await();
                }
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
                barberChairEmpty = false;
                logEntry();
                while (!hairCutDone) {
                    customerHairCutDoneCondition.await();
                }
                logExit();
                customerLeft = true;
                customerCnt--;
                barberNxtIterationCondition.signal();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }

        private void balk() {
            System.out.println("Barber shop is full, Customer-" + this.id + " is leaving the shop.");
        }

        private void logEntry() {
            System.out.println("Customer-" + this.id + " has occupied the hair-cut chair.");
        }

        private void logExit() {
            System.out.println("Customer-" + this.id + " is done with the hair-cut. Leaving the shop.");
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
        for (int i = 0; i < this.nCustomers; i++) {
            customerThreads.add(new Thread(new CustomerRunnable(i), "Customer-Thread-" + i));
        }

        barberThread.start();
        for (Thread t : customerThreads) {
            t.start();
        }

//        barberThread.join();
//        for (Thread t : customerThreads) {
//            t.join();
//        }
        Thread.sleep(15000); // simulate for 15s. -> All customer threads are expected to be completed.
        doStop();
    }

    public static void main(String[] args) throws InterruptedException {
        new BarberShopEz(5, 15).solve();
    }
}
