package Problems.S02_LessClassical.P07_RollerCoaster;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class RollerCoasterEz {
    private static final int CAPACITY = 5;
    private final int n;// number of riders

    private int riderOnBoard = 0;
    private int rideId = 0;

    private boolean carStarted = false;
    private boolean reached = false;

    private final Lock lock = new ReentrantLock();
    private final Condition riderWaitCondition = lock.newCondition();
    private final Condition onBoardWaitCondition = lock.newCondition();
    private final Condition carStartSignal = lock.newCondition();
    private final Condition onBoardRideCondition = lock.newCondition();
    private final Condition carResetCondition = lock.newCondition();

    public RollerCoasterEz(int n) {
        this.n = n;
    }

    private class CarRunnable implements Runnable {
        @Override
        public void run() {
            while (true) {
                lock.lock();
                try {
                    while (riderOnBoard != CAPACITY) {
                        carStartSignal.await();

                    }
                    carStarted = true;
                    onBoardWaitCondition.signalAll();
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                }

                try {
                    Thread.sleep(500);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                    Thread.currentThread().interrupt();
                }

                lock.lock();
                try {
                    reached = true;
                    onBoardRideCondition.signalAll();
                    while (riderOnBoard != 0) {
                        carResetCondition.await();
                    }
                    carStarted = false;
                    reached = false;
                    riderWaitCondition.signalAll();
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                } finally {
                    lock.unlock();
                }
            }

        }
    }

    private class RiderRunnable implements Runnable {
        private final int id;

        private RiderRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            lock.lock();
            try {
                while (riderOnBoard == CAPACITY) {
                    riderWaitCondition.await();
                }
                riderOnBoard++;
                if (riderOnBoard == CAPACITY) {
                    carStartSignal.signal();
                }
                while (!carStarted) {
                    onBoardWaitCondition.await();
                }
                System.out.println("Rider-" + this.id + " has started the journey....; rideId=" + rideId);
                while (!reached) {
                    onBoardRideCondition.await();
                }

                riderOnBoard--;
                System.out.println("Rider-" + this.id + " has unboarded from the car.");
                if (riderOnBoard == 0) {
                    rideId++;
                    carResetCondition.signal();
                }
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }
    }

    public void solve() throws InterruptedException {
        Thread carThread = new Thread(new CarRunnable(), "Car-Thread");
        List<Thread> riderThread = new ArrayList<>();

        for (int i = 0; i < this.n; i++) {
            riderThread.add(new Thread(new RiderRunnable(i), "Rider-Thread-" + i));
        }

        carThread.start();
        for (Thread t : riderThread) {
            t.start();
        }
        carThread.join();
        for (Thread t : riderThread) {
            t.join();
        }
    }

    public static void main(String args[]) throws InterruptedException {
        new RollerCoasterEz(30).solve();
    }
}
