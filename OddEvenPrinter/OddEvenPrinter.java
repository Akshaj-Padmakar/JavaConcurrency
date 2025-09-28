package OddEvenPrinter;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class OddEvenPrinter {
    private Semaphore odd = new Semaphore(1);
    private Semaphore even = new Semaphore(0);
    private AtomicInteger current = new AtomicInteger(1);

    public class OddRunnable implements Runnable {
        private int n;
        public OddRunnable(int n){
            this.n = n;
        }

        @Override
        public void run() {
            try{
                while(current.get() < n){
                    odd.acquire();
                    System.out.println("Thread:" + Thread.currentThread().getName() + " value: " + current.get());
                    current.incrementAndGet();
                    even.release();
                }
            }catch(Exception e){
                e.printStackTrace();
            }
        }
    }


    public class EvenRunnable implements Runnable {
        private int n;
        public EvenRunnable(int n){
            this.n = n;
        }

        @Override 
        public void run() {
            try{
                while(current.get() < n) {
                    even.acquire();
                    System.out.println("Thread:" + Thread.currentThread().getName() + " value: " + current.get());
                    current.getAndIncrement();
                    odd.release();
                }
            } catch(Exception e){
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException{
        int n = 10;
        OddEvenPrinter printer = new OddEvenPrinter();

        Thread oddThread = new Thread(printer.new OddRunnable(n), "Odd-Thread");
        Thread evenThread = new Thread(printer.new EvenRunnable(n), "Even-Thread");

        oddThread.start();
        evenThread.start();

        oddThread.join();
        evenThread.join();
    }
}
