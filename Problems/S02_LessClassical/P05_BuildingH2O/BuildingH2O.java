package Problems.S02_LessClassical.P05_BuildingH2O;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

public class BuildingH2O {
    
    private final int Hcnt;
    private final int Ocnt; // Assuming Hcnt = 2 * Ocnt

    private Semaphore hSemaphore = new Semaphore(2);
    private Semaphore oSemaphore = new Semaphore(0);

    public BuildingH2O(int Hcnt, int Ocnt) {
        this.Hcnt = Hcnt;
        this.Ocnt = Ocnt;
    }

    public class HRunnable implements Runnable {
        private int id;
        public HRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            try {
                hSemaphore.acquire();
                System.out.println("Acuired H, with id:" + this.id);
                oSemaphore.release();
            } catch(InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public class ORunnable implements Runnable {
        private int id;
        public ORunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            try {
                oSemaphore.acquire(2);
                System.out.println("Acuired O, with id:" + this.id);
                hSemaphore.release(2);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
    public void solve() throws InterruptedException {
        List<Thread> Hthreads = new ArrayList<>();
        List<Thread> Othreads = new ArrayList<>();

        for(int i = 0; i < this.Hcnt; i++) {
            Hthreads.add(new Thread(new HRunnable(i), "H-Thread-" + i));
        }


        for(int i = 0; i < this.Ocnt; i++) {
            Othreads.add(new Thread(new ORunnable(i), "O-Thread-" + i));
        }

        for(Thread t : Hthreads) {
            t.start();
        }

        for(Thread t : Othreads) {
            t.start();
        }

        for(Thread t : Hthreads) {
            t.join();
        }

        for(Thread t : Othreads) {
            t.join();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        new BuildingH2O(20, 10).solve();
    }
}
