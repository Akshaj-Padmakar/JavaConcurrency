package RiverBoatProblem;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class RiverBoatProblem {
    private Lock lock = new ReentrantLock();
    private Semaphore hackerSem = new Semaphore(0);
    private Semaphore engineerSem = new Semaphore(0);
    private int hacker = 0;
    private int engineer = 0;
    private final Random rnd = new Random();
    private int boatNumber = 0;


    public class HackerRunnable implements Runnable {
        private final int id;
        public HackerRunnable(int id){
            this.id = id;
        }

        @Override
        public void run() {
            lock.lock();
            boolean captain = false;
            try{
                hacker++;
                if(hacker == 4){
                    hacker -= 4;
                    captain = true;
                    hackerSem.release(4);
                    boatNumber++;
                } else if(hacker >= 2 && engineer >= 2){
                    hacker -= 2;
                    engineer -= 2;
                    captain = true;
                    hackerSem.release(2);
                    engineerSem.release(2);
                    boatNumber++;
                }
            } finally {
                lock.unlock();
            }
            try{
                hackerSem.acquire();
                lock.lock();
                board(this.id, "Hacker"); // boarded the boat
                if(captain){
                    isCaptain(this.id, "Hacker");
                }
            } catch (InterruptedException e){
                Thread.interrupted();
            } finally {
                lock.unlock();
            }
        }
    }

    public class EngineerRunnable implements Runnable {
        private final int id;
        public EngineerRunnable(int id){
            this.id = id;
        }

        @Override
        public void run(){
            lock.lock();
            boolean captain = false;
            try{
                engineer++;
                if(engineer == 4){
                    engineer -= 4;
                    captain = true;
                    boatNumber++;
                    engineerSem.release(4);
                } else if(engineer >= 2 && hacker >= 2){
                    engineer -= 2;
                    hacker -= 2;
                    captain = true;
                    boatNumber++;
                    engineerSem.release(2);
                    hackerSem.release(2);
                }
            } finally {
                lock.unlock();
            }
            
            try{
                engineerSem.acquire();
                lock.lock();
                board(this.id, "Engineer"); // boarded the boat
                if(captain){
                    isCaptain(this.id, "Engineer");
                }
            } catch (InterruptedException e){
                Thread.interrupted();
            } finally {
                lock.unlock();
            }
        }
    }

    public void board(int id, String memberType){
        System.out.println(memberType + id + " Boarded, boatNumber: " + boatNumber);
        try { Thread.sleep(10 + rnd.nextInt(40)); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

    }

    public void isCaptain(int id, String memberType){
        System.out.println(memberType + id + " Captain, boatNumber: " + boatNumber);
    }

    public void solve(int hacker, int engineer) throws InterruptedException {
        List<Thread> hackerThreads = new ArrayList<>();
        List<Thread> engineerThreads = new ArrayList<>();

        for(int i = 0; i < hacker; i++){
            Runnable hackerRunnable = new HackerRunnable(i);
            hackerThreads.add(new Thread(hackerRunnable, "Hacker-Thread-" + i));
        }

        for(int i = 0; i < engineer; i++){
            Runnable engineerRunnable = new EngineerRunnable(i);
            engineerThreads.add(new Thread(engineerRunnable, "Engineer-Thread-" + i));
        }

        for(Thread t : hackerThreads){
            t.start();
        }
        for(Thread t: engineerThreads){
            t.start();
        }

        for(Thread t : hackerThreads){
            t.join();
        }
        for(Thread t: engineerThreads){
            t.join();
        }

    }
    public static void main(String[] args) throws InterruptedException {
        int hacker = 2;
        int engineer = 6;

        new RiverBoatProblem().solve(hacker, engineer);
    }
}
