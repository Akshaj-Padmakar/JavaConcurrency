package Problems.General.OddEvenPrinter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

public class OddEvenPrinterSemaphore {
    private int n;
    private int current = 0;
    private List<Semaphore> semaphoreList = new ArrayList<>();

    public OddEvenPrinterSemaphore(int n) {
        this.n = n;
        semaphoreList.add(new Semaphore(1));
        semaphoreList.add(new Semaphore(0));
    }
    public class OddRunnable implements Runnable {
        public OddRunnable() {}

        @Override
        public void run() {
            try {
                while(true){
                    semaphoreList.get(1).acquire();
                    if(current > n) {
                        semaphoreList.get(0).release();
                        break;
                    }
                    System.out.println("current = " + current + " Thread = " + Thread.currentThread().getName());
                    current++;

                    semaphoreList.get(0).release();
                }
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public class EvenRunnable implements Runnable {
        public EvenRunnable() {}

        @Override
        public void run() {
            try {
                while(true){
                    semaphoreList.get(0).acquire();
                    if(current > n) {
                        semaphoreList.get(1).release();
                        break;
                    }
                    System.out.println("current = " + current + " Thread = " + Thread.currentThread().getName());
                    current++;

                    semaphoreList.get(1).release();
                }
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void solve() throws InterruptedException {
        Thread oddThread = new Thread(new OddRunnable(), "Odd-Thread");
        Thread evenThread = new Thread(new EvenRunnable(), "Even-Thread");

        oddThread.start();
        evenThread.start();

        oddThread.join();
        evenThread.join();
    }
    public static void main(String[] args) throws InterruptedException {
        int n = 16;
        new OddEvenPrinterSemaphore(n).solve();
    }
}
