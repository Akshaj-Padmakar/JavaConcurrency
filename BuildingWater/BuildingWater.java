package BuildingWater;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class BuildingWater {
    private Semaphore hSemaphore = new Semaphore(2);
    private Semaphore oSemaphore = new Semaphore(0);

    public class hRunnable implements Runnable {
        public hRunnable(){

        }
        @Override
        public void run(){
            try{
                hSemaphore.tryAcquire(2000, TimeUnit.MILLISECONDS);
                System.out.println("Acquired H");
                oSemaphore.release();
            } catch(InterruptedException e){
                e.printStackTrace();
            }
        }

    }

    public class oRunnable implements Runnable {
        public oRunnable() {

        }

        @Override
        public void run(){
            try{
                oSemaphore.tryAcquire(2, 200, TimeUnit.MILLISECONDS);
                System.out.println("Acquired O");
                hSemaphore.release(2);
            } catch(InterruptedException e){
                e.printStackTrace();
            }

        }
    }
    
    public void solve(int hydrogenThread, int oxygenThread) throws InterruptedException{
        List<Thread> hThreads = new ArrayList<>();
        for (int i = 0; i < hydrogenThread; i++) {
            Thread t = new Thread(new hRunnable(), "Hydrogen-Thread-" + i);
            hThreads.add(t);
            t.start();
        }
 



        List<Thread> oThreads = new ArrayList<>();
        for (int i = 0; i < oxygenThread; i++) {
            Thread t = new Thread(new oRunnable(), "Oxygen-Thread-" + i);
            oThreads.add(t);
            t.start();
        }
        for (Thread t : hThreads) {
            t.join();
        }
        for (Thread t : oThreads) {
            t.join();
        }
    }
    public static void main(String[] args) throws InterruptedException{
        int hydrogenThread = 10;
        int oxygenThread = 7;
        BuildingWater problem = new BuildingWater();
        problem.solve(hydrogenThread, oxygenThread);
    }
}
