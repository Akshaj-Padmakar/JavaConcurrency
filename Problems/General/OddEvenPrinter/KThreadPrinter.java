package Problems.General.OddEvenPrinter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/* 
 * Given k threads, start from 0, and print the i th value with i % k-th thread.
*/
public class KThreadPrinter {
    private final int n;
    private final int k;
    private int current = 0;
    private List<Semaphore> semaphores = new ArrayList<>();
    public KThreadPrinter(int n, int k) {
        this.n = n;
        this.k = k;
        for(int i = 0; i < k; i++){
            if(i == 0){
                semaphores.add(new Semaphore(1));
            } else {
                semaphores.add(new Semaphore(0));
            }
        }
    }

    public class iRunnable implements Runnable {
        private final int i;
        public iRunnable(int i) {
            this.i = i;
        }

        @Override
        public void run() {
            try {
                while(true) {
                    semaphores.get(i).acquire();
                    if(current > n) {
                        for(Semaphore s : semaphores) {
                            s.release();
                        }
                        break;
                    }
                    System.out.println("current = " + current + " threadName = " + Thread.currentThread().getName());
                    current++;
                    semaphores.get((i + 1) % k).release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        }
    }
    public void solve() throws InterruptedException {
        List<Thread> threads = new ArrayList<>();
        for(int i = 0; i < k; i++) {
            threads.add(new Thread(new iRunnable(i), "Thread-"+i));
        }
        for(Thread t : threads) {
            t.start();
        }
        for(Thread t : threads) {
            t.join();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        int n = 15;
        int k = 3;
        new KThreadPrinter(n, k).solve();
    }   
}
