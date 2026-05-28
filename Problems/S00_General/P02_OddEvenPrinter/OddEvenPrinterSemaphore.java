package Problems.S00_General.P02_OddEvenPrinter;

import java.util.concurrent.Semaphore;

public class OddEvenPrinterSemaphore {
    private final int n;
    private final Semaphore oddSemaphore = new Semaphore(1);
    private final Semaphore evenSemaphore = new Semaphore(0);
    private int current = 0;

    public OddEvenPrinterSemaphore(int n) {
        this.n = n;
    }

    public void solve() throws InterruptedException {
        Thread oddThread = new Thread(new OddRunnable(), "Odd-Thread");
        Thread evenThread = new Thread(new EvenRunnable(), "Even-Runnable");

        oddThread.start();
        evenThread.start();

        evenThread.join();
        oddThread.join();
    }

    private class OddRunnable implements Runnable {
        @Override
        public void run() {
            try {
                while (true) {
                    oddSemaphore.acquire();
                    if (current > n) {
                        evenSemaphore.release(1);
                        break;
                    }
                    printCurrentValueAndThread();
                    current++;
                    evenSemaphore.release(1);
                }
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        }
    }

    private class EvenRunnable implements Runnable {
        @Override
        public void run() {
            try {
                while (true) {
                    evenSemaphore.acquire();
                    if (current > n) {
                        oddSemaphore.release();
                        break;
                    }
                    printCurrentValueAndThread();
                    current++;
                    oddSemaphore.release(1);
                }
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        }
    }

    private void printCurrentValueAndThread() {
        System.out.println(
                "current = " + current + ", printed by Thread = " + Thread.currentThread().getName());
    }
}
