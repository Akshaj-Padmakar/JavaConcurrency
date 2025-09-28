package UniSexBathroom;

import java.security.cert.CertPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class UnisexBathroom {
    private final Random rnd = new Random();
    private final int CAPACITY = 3;
    public enum GENDER {
        MEN, WOMEN, NONE;
    }
    private GENDER currentGender = GENDER.NONE;
    private GENDER previousGender = GENDER.NONE;

    private Lock lock = new ReentrantLock();
    private Condition menCondition = lock.newCondition();
    private Condition womenCondition = lock.newCondition();
    private int menWaiting = 0;
    private int womenWaiting = 0;
    private int inside = 0;
    public class MenRunnable implements Runnable {
        private final int id;
        public MenRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            try {
                menEnter();
                Thread.sleep(rnd.nextInt(100) + 100);
                menExit();
            } catch(InterruptedException e){
                Thread.currentThread().interrupt();
            }
        }

        private void menEnter() {
            lock.lock();
            System.out.println("Men-" + this.id + " wants to enter.");
            try {
                menWaiting++;
                while(currentGender == GENDER.WOMEN || inside == CAPACITY || (currentGender == GENDER.NONE && womenWaiting > 0 && previousGender == GENDER.MEN)){
                    menCondition.await();
                }
                menWaiting--;
                inside++;
                currentGender = GENDER.MEN;
                System.out.println("Men-" + this.id + " entered.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }

        private void menExit() {
            lock.lock();
            System.out.println("Men-" + this.id + "exiting now !");
            try{
                inside--;
                if(inside == 0){
                    currentGender = GENDER.NONE;
                    previousGender = GENDER.MEN;

                    if(womenWaiting > 0){
                        womenCondition.signalAll();
                    } else {
                        menCondition.signalAll();
                    }
                } else {
                    menCondition.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    public class WomenRunnable implements Runnable {
        private final int id;
        public WomenRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            try {
                enterWomen();
                Thread.sleep(rnd.nextInt(100) + 100);
                exitWomen();
            } catch(InterruptedException e){
                Thread.currentThread().interrupt();
            }
        }

        public void enterWomen() {
            lock.lock();
            System.out.println("Women-" + this.id + " wants to enter.");
            try {
                womenWaiting++;
                if(currentGender == GENDER.MEN || inside == CAPACITY || (currentGender == GENDER.NONE && menWaiting > 0 && previousGender == GENDER.WOMEN)) {
                    womenCondition.await();
                }
                womenWaiting--;
                inside++;
                currentGender = GENDER.WOMEN;
                System.out.println("Women-" + this.id + " entered.");
            } catch (InterruptedException e){
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }
        public void exitWomen() {
            lock.lock();
            System.out.println("Women-" + this.id + " exiting now !");
            try{
                inside--;
                if(inside == 0){
                    currentGender = GENDER.NONE;
                    previousGender = GENDER.WOMEN;

                    if(menWaiting > 0){
                        menCondition.signalAll();
                    } else {
                        womenCondition.signalAll();
                    }
                } else {
                    if(womenWaiting > 0){
                        womenCondition.signalAll();
                    }
                }
            } finally {
                lock.unlock();
            }
        }
    }
    public void solve(int men, int women) throws InterruptedException {
        List<Thread> menThreads = new ArrayList<>(), womenThreads = new ArrayList<>();
        for(int i = 0; i < men; i++) {
            menThreads.add(new Thread(new MenRunnable(i), "Men-Thread-" + i));
        }

        for(int i = 0; i < women; i++) {
            womenThreads.add(new Thread(new WomenRunnable(i), "Women-Thread-" + i));
        }

        for(Thread t : menThreads){
            t.start();
        }

        for(Thread t : womenThreads){
            t.start();
        }

        for(Thread t : menThreads){
            t.join();
        }

        for(Thread t : womenThreads){
            t.join();
        }
    }
    public static void main(String[] args) throws InterruptedException {
        int men = 100;
        int women = 4;
        new UnisexBathroom().solve(men, women);
    }
}

