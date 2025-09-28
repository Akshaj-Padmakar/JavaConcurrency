package BarberShopProblem;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BarberShop {
    private static final Random rnd = new Random();
    public class Shop {
        private int chairs;
        private Lock lock = new ReentrantLock();
        private int waiting = 0;
        private Semaphore customer = new Semaphore(0);
        private Semaphore barber = new Semaphore(0);
        private Semaphore seatBealt = new Semaphore(0);

        public Shop(int chairs) {
            this.chairs = chairs;
        }
        private void cutHair() throws InterruptedException {
            customer.acquire();
            lock.lock();
            try{
                waiting--;
                barber.release();
            } finally {
                lock.unlock();
            }

            doCut();
            seatBealt.release();
        }

        private void getHairCut(int id) throws InterruptedException {  
            lock.lock();
            try{
                if(waiting == chairs){
                    System.out.println("Shop is full, customerId: " + id +" is leaving");
                    return;
                }
                waiting++;
                System.out.println("Customerid: " + id + " is waiting now");
                customer.release();
            } finally {
                lock.unlock();
            }
            barber.acquire();
            seatBealt.acquire();
            System.out.println("Hair cut for customerId: " + id + "is done !");
        }

        private void doCut() throws InterruptedException {
            Thread.sleep(200 + rnd.nextInt(200));
        }

        public void stop() {
            customer.release();
        }

    }

    public class BarberRunnable implements Runnable {
        private Shop shop;
        private volatile boolean running = true;

        public BarberRunnable(Shop shop){
            this.shop = shop;
        }

        public void shutdown(){
            running = false;
        }

        @Override
        public void run() {
            try{
                while(running) {
                    shop.cutHair();
                }
            }catch(InterruptedException e){
                e.printStackTrace();
            }
        }
    }

    public class CustomerRunnable implements Runnable {
        private Shop shop;
        private int id;
        
        public CustomerRunnable(Shop shop, int id){
            this.shop = shop;
            this.id = id;
        }

        @Override
        public void run() {
            try{
                shop.getHairCut(id);
            } catch(InterruptedException e){
                e.printStackTrace();
            }
        }
    }
    public static void main(String[] args) throws InterruptedException {
        int chairs = 4;
        int customers = 20;
        BarberShop barberShop = new BarberShop();
        Shop shop = barberShop.new Shop(chairs);

        BarberRunnable barberRunnable = barberShop.new BarberRunnable(shop);
        Thread barberThread = new Thread(barberRunnable, "Barber-Thread");
        barberThread.start();

        List<Thread> customerThread = new ArrayList<>();
        for(int i = 0; i < customers; i++){
            CustomerRunnable customerRunnable = barberShop.new CustomerRunnable(shop, i);
            customerThread.add(new Thread(customerRunnable, "Customer-Thread-" + i));

            customerThread.get(i).start();
            Thread.sleep(100 + rnd.nextInt(100));
        }

        for(Thread t : customerThread){
            t.join();
        }
        shop.stop();
        barberRunnable.shutdown();
        barberThread.join();

        System.out.println("Barber Shop is Closed now!");
    }
}
