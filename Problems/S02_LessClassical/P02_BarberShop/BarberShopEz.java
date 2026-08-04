package Problems.S02_LessClassical.P02_BarberShop;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BarberShopEz {
    private final int N; // number of chairs
    private final int M; // number of customers

    private Lock lock = new ReentrantLock();
    private Condition barberSleepingCondition = lock.newCondition();
    private Condition customerWaitingCondition = lock.newCondition();
    private Condition customerHairCutCondition = lock.newCondition();
    private Condition barberNxtCondition = lock.newCondition();
    private int customerInside = 0;
    private boolean barberChairEmpty = false;
    private boolean hairCutDone = false;
    private boolean currentCustomerLeft = false;

    public BarberShopEz(int N, int M) {
        this.N = N;
        this.M = M;
    }

    private class BarberRunnable implements Runnable {
        @Override
        public void run() {
            while (true) {
                lock.lock();
                try {
                    while (customerInside == 0) {
                        barberSleepingCondition.await();
                    }
                    barberChairEmpty = true;
                    customerWaitingCondition.signal();
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                }
                try {
                    Thread.sleep(600);// simulate cutting hair
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                    Thread.currentThread().interrupt();
                    break;
                }
                lock.lock();
                try {
                    hairCutDone = true;
                    customerHairCutCondition.signal();
                    while (!currentCustomerLeft) {
                        barberNxtCondition.await();
                    }
                    currentCustomerLeft = false;
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                    Thread.currentThread().interrupt();
                    break;
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    private class CustomerRunnable implements Runnable {
        private int id;

        public CustomerRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            lock.lock();
            try {
                if (customerInside == N + 1) {
                    balk();
                    return;
                }
                customerInside++;
                if (customerInside == 1) {
                    barberSleepingCondition.signal();
                }

                while (!barberChairEmpty) {
                    customerWaitingCondition.await();
                }
                getHairCut();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            } finally {
                customerInside--;
                currentCustomerLeft = true;
                hairCutDone = false;
                // moved to final-> since customer getting hair cut, can be interrupted, and
                // then this flag is never reset.
                // Keep mid-handshake interrupts in mind.
                lock.unlock();
            }
        }

        private void balk() {
            System.out.println("Barber shop is full, Customer-" + this.id + " is leaving the shop.");
        }

        private void getHairCut() throws InterruptedException {
            barberChairEmpty = false;
            System.out.println("Customer-" + this.id + " has occupied the hair-cut chair.");
            while (!hairCutDone) {
                customerHairCutCondition.await();
            }
            System.out.println("Customer-" + this.id + " is done with the hair-cut. Leaving the shop.");
            barberNxtCondition.signal();
        }
    }

    public void solve() throws InterruptedException {
        Thread barberThread = new Thread(new BarberRunnable(), "Barber-Thread");
        List<Thread> customerThread = new ArrayList<>();

        for (int i = 0; i < M; i++) {
            customerThread.add(new Thread(new CustomerRunnable(i), "Customer-Thread-" + i));
        }

        barberThread.start();
        for (Thread t : customerThread) {
            t.start();
        }

        barberThread.join();
        for (Thread t : customerThread) {
            t.join();
        }
    }

    public static void main(String args[]) throws InterruptedException {
        new BarberShopEz(3, 5).solve();
    }
}
