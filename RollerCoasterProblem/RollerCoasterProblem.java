package RollerCoasterProblem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class RollerCoasterProblem {
    
    private int waitingPassengerCnt = 0;
    private Lock lock = new ReentrantLock();
    private Semaphore passengerSem = new Semaphore(0);
    private Semaphore carSemBoard = new Semaphore(0);
    private Semaphore passengerSemBoard = new Semaphore(0);
    private Semaphore carSemRun = new Semaphore(0);
    private Semaphore passengerSemUnboard = new Semaphore(0);
    private Semaphore carSemReRun = new Semaphore(0);
    public class PassengerRunnable implements Runnable {
        private final int id;
        private final int c;
        public PassengerRunnable(int id, int c){
            this.id = id;
            this.c = c;
        }

        @Override
        public void run() {
            lock.lock();
            try{
                waitingPassengerCnt++;
                if(waitingPassengerCnt >= this.c) {
                    passengerSem.release(this.c);
                    waitingPassengerCnt -= this.c;
                }
            } finally {
                lock.unlock();
            }

            try {
                passengerSem.acquire();
                carSemBoard.release();
                passengerSemBoard.acquire();
                board();
                carSemRun.release();
                passengerSemUnboard.acquire();
                unboard();
                carSemReRun.release();
            } catch(InterruptedException e){
                Thread.currentThread().interrupt();
            } 
        }

        private void board() {
            System.out.println("Passenger-" + this.id + " has boarded the Car!");
        }
        
        private void unboard() {
            System.out.println("Passenger-" + this.id + " has unboarded the Car!");
        }
    }

    public class CarRunnable implements Runnable {
        private final int c;
        public CarRunnable(int c) {
            this.c = c;
        }

        @Override
        public void run() {
            try {
                while(true){
                    boolean acquired = carSemBoard.tryAcquire(c, 1000, TimeUnit.MILLISECONDS);
                    if(acquired == false){
                        System.out.println("Timming out bruh !!!!!"); return;
                    }
                    load();
                    passengerSemBoard.release(c);
                    carSemRun.acquire(c);
                    runn();
                    unload();
                    passengerSemUnboard.release(c);
                    carSemReRun.acquire(c);
                }
            } catch (InterruptedException e){
                Thread.currentThread().interrupt();
            }
        }

        private void load() {
            System.out.println("Car has started loading passengers!");
        }

        private void runn() {
            System.out.println("Car has started running with passengers on board!");
        }
        private void unload() {
            System.out.println("Car has reached unloading passengers!");
        }
    }
    public void solve(int n, int c) throws InterruptedException{
        List<Thread> passengerThread = new ArrayList<>();
        for(int i = 0; i < n; i++){
            Runnable passengerRunnable = new PassengerRunnable(i, c);
            passengerThread.add(new Thread(passengerRunnable, "Passenger-Thread-" + i));
        }
        Thread carThread = new Thread(new CarRunnable(c));
        carThread.start();
        for(Thread t : passengerThread){
            t.start();
        }
        for(Thread t: passengerThread){
            t.join();
        }
        carThread.join();

    }

    public static void main(String[] args) throws InterruptedException {
        int n = 16;
        int c = 4;
        new RollerCoasterProblem().solve(n, c);
    }
}
