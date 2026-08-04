package Problems.S02_LessClassical.P05_BuildingH2O;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BuildingH2O_Lock {
    private final int Hcnt;
    private final int Ocnt;

    private static final int H_GROUP = 2;
    private static final int O_GROUP = 1;

    private Lock lock = new ReentrantLock();
    private Condition hCondition = lock.newCondition();
    private Condition oCondition = lock.newCondition();
    private Condition doneCondition = lock.newCondition();

    private int hReady = 0;
    private int oReady = 0;
    private int hGrant = 0;
    private int oGrant = 0;

    private int generation = 0;
    private int bonded = 0;

    public BuildingH2O_Lock(int Hcnt, int Ocnt) {
        this.Hcnt = Hcnt;
        this.Ocnt = Ocnt;
    }

    private class ORunnable implements Runnable {
        private int id;

        public ORunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            lock.lock();
            try {
                oReady++;
                tryForm();
                while (oGrant == 0) {
                    oCondition.await();
                }

                oGrant--;
                System.out.println("Oxygen-" + id + " is now being used to form water...; gen=" + generation);
                barrier();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }
    }

    private class HRunnable implements Runnable {
        private int id;

        public HRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            lock.lock();
            try {
                hReady++;
                tryForm();
                while (hGrant == 0) {
                    hCondition.await();
                }
                hGrant--;
                System.out.println("Hydrogen-" + id + " is now being used to form water...; gen=" + generation);
                barrier();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }
    }

    private void tryForm() {
        // hGrant & oGrant check -> so that no molecule formation is in progress...
        if (hReady >= H_GROUP && oReady >= O_GROUP && hGrant == 0 && oGrant == 0) {
            hReady -= H_GROUP;
            oReady -= O_GROUP;
            hGrant = H_GROUP;
            oGrant = O_GROUP;
            hCondition.signalAll();
            oCondition.signalAll();
        }
    }

    private void barrier() throws InterruptedException {
        int myGen = generation;
        bonded++;
        if (bonded == H_GROUP + O_GROUP) {
            bonded = 0;
            generation++;
            doneCondition.signalAll();
            tryForm();
        } else {
            while (myGen == generation) {
                doneCondition.await();
            }
        }
    }

    public void solve() throws InterruptedException {
        List<Thread> hThreads = new ArrayList<>();
        List<Thread> oThreads = new ArrayList<>();

        for (int i = 0; i < this.Hcnt; i++) {
            hThreads.add(new Thread(new HRunnable(i), "H-Thread-" + i));
        }

        for (int i = 0; i < this.Ocnt; i++) {
            oThreads.add(new Thread(new ORunnable(i), "O-Thread-" + i));
        }

        for (Thread t : hThreads) {
            t.start();
        }
        for (Thread t : oThreads) {
            t.start();
        }

        for (Thread t : hThreads) {
            t.join();
        }
        for (Thread t : oThreads) {
            t.join();
        }
    }

    public static void main(String args[]) throws InterruptedException {
        new BuildingH2O_Lock(20, 10).solve();
    }

}
