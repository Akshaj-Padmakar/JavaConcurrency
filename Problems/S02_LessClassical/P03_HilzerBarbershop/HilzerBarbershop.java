package Problems.S02_LessClassical.P03_HilzerBarbershop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class HilzerBarbershop {
    private final int customerCnt;
    private final int barberCnt = 3;
    private final int maxCustomers = 20;

    private int currentCustomerCnt = 0;

    private final Queue<Integer> standingQueue = new LinkedList<>();
    private final Queue<Integer> sofaQueue = new LinkedList<>();

    private final Map<Integer, Condition> standingMap = new HashMap<>();
    private final Map<Integer, Condition> sofaMap = new HashMap<>();

    private final Map<Integer, Boolean> barberReady = new HashMap<>();
    private final Map<Integer, Boolean> haircutDone = new HashMap<>();
    private final Map<Integer, Boolean> customerLeft = new HashMap<>();
    private final Map<Integer, Boolean> paymentRequested = new HashMap<>();
    private final Map<Integer, Boolean> paymentDone = new HashMap<>();

    private Lock lock = new ReentrantLock();
    private Condition sofaEmpty = lock.newCondition();

    public HilzerBarbershop(int customerCnt) {
        this.customerCnt = customerCnt;
    }

    private class BarberRunnable implements Runnable {
        private final int id;

        public BarberRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            while (true) {
                lock.lock();
                Integer customerId = 0;
                Condition condition = null;
                try {
                    while (sofaQueue.size() == 0) {
                        sofaEmpty.await();
                    }
                    customerId = sofaQueue.poll();
                    condition = sofaMap.get(customerId);

                    barberReady.put(customerId, true);
                    sofaMap.remove(customerId);

                    System.out.println("Barber" + this.id + "has starting cutting hair for customer-" + customerId);

                    condition.signal();
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
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
                    cutHair(customerId, condition);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                } finally {
                    lock.unlock();
                }
            }
        }

        private void cutHair(Integer customerId, Condition condition) throws InterruptedException {
            System.out.println("Barber is done cutting the hair for customer-" + customerId);
            haircutDone.put(customerId, true);
            condition.signal();
            while (!paymentRequested.get(customerId)) {
                condition.await();
            }
            paymentDone.put(customerId, true);
            System.out.println("Barber have accepted payment for customer-" + customerId);
            condition.signal();
            while (!customerLeft.get(customerId)) {
                condition.await();
            }
            customerLeft.put(customerId, false);

        }
    }

    private class CustomerRunnable implements Runnable {
        private int id;
        private Condition condition;

        public CustomerRunnable(int id) {
            this.id = id;
            this.condition = lock.newCondition();
            barberReady.put(id, false);
            haircutDone.put(id, false);
            customerLeft.put(id, false);
            paymentDone.put(id, false);
            paymentRequested.put(id, false);
        }

        @Override
        public void run() {
            lock.lock();
            try {
                if (shopFull()) {
                    return;
                }
                currentCustomerCnt++;
                System.out.println("Customer-" + this.id + " is now standing in the common area");

                standingQueue.add(this.id);
                standingMap.put(this.id, this.condition);

                while (sofaQueue.size() == 4) {
                    this.condition.await();
                }
                standingQueue.remove(this.id);
                standingMap.remove(this.id);

                sofaQueue.add(this.id);
                sofaMap.put(this.id, this.condition);
                System.out.println("Customer-" + this.id + " is now sitting  on the sofa");
                sofaEmpty.signal();

                while (!barberReady.get(this.id)) {
                    this.condition.await();
                }

                Integer nxt = standingQueue.peek();
                if (nxt != null) {
                    standingMap.get(nxt).signal();
                }

                getHairCut();

            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }
        }

        private boolean shopFull() {
            if (currentCustomerCnt == maxCustomers) {
                System.out.println("Already 20 customers in the shop, customer-" + this.id + " is leaving...");
                return true;
            }
            return false;
        }

        private void getHairCut() throws InterruptedException {
            System.out.println("Customer-" + this.id + " is getting a hair cut !!!");
            while (!haircutDone.get(this.id)) {
                this.condition.await();
            }
            System.out.println("Customer-" + this.id + " is paying.");
            paymentRequested.put(this.id, true);
            this.condition.signal();

            while (!paymentDone.get(this.id)) {
                this.condition.await();
            }
            System.out.println("Customer-" + this.id + " payement is accepted.");

            System.out.println("Customer-" + this.id + " is now leaving the store.");
            customerLeft.put(this.id, true);
            currentCustomerCnt--;
            this.condition.signal();

        }
    }

    public void solve() throws InterruptedException {
        List<Thread> barberThreads = new ArrayList<>();
        List<Thread> customerThreads = new ArrayList<>();

        for (int i = 0; i < barberCnt; i++) {
            barberThreads.add(new Thread(new BarberRunnable(i), "Barber-Thread" + i));
        }

        for (int i = 0; i < customerCnt; i++) {
            customerThreads.add(new Thread(new CustomerRunnable(i), "Customer-Thread" + i));
        }

        for (Thread t : barberThreads) {
            t.start();
        }
        for (Thread t : customerThreads) {
            t.start();
        }

        for (Thread t : barberThreads) {
            t.join();
        }
        for (Thread t : customerThreads) {
            t.join();
        }

    }

    public static void main(String[] args) throws InterruptedException {
        new HilzerBarbershop(25).solve();
    }
}
