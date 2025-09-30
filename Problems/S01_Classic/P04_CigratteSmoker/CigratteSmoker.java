package Problems.S01_Classic.P04_CigratteSmoker;

import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class CigratteSmoker {
    private final Lock lock = new ReentrantLock();
    private final Condition tobaccoCondition = lock.newCondition();
    private final Condition paperCondition = lock.newCondition();
    private final Condition matchesCondition = lock.newCondition();
    private final Semaphore ready = new Semaphore(0);
    
    private int choice = -1; // choice = -1 -> not started, choice = -2 done.

    private final Random rnd = new Random();

    private void doStop() {
        choice = -2;
        paperCondition.signal();
        matchesCondition.signal();
        tobaccoCondition.signal();
    }

    public class AgentRunnable implements Runnable {
        public AgentRunnable() {}

        @Override
        public void run() {
            try {
                Thread.sleep(500);
                ready.acquire(3);
                lock.lock();
                choice = rnd.nextInt(3);
                System.out.println("Agent signalled choice = " + (choice));
                if(choice == 0) {
                    tobaccoCondition.signal();
                } else if (choice == 1) {
                    paperCondition.signal();
                } else {
                    matchesCondition.signal();
                }
            } catch(InterruptedException e) {
                e.printStackTrace();    
            } finally {
                lock.unlock();
            }
        }
    }

    public class TobaccoRunnable implements Runnable { // This smoker need Tobacco
        public TobaccoRunnable() {}
        
        @Override
        public void run() {
            lock.lock();
            ready.release();
            try {
                while(choice != 0) {
                    tobaccoCondition.await();
                    if(choice == -2) {
                        return;
                    }
                }
                System.out.println("Smoker[without Tobacco] acquired Tobacco and started smoking.");
                doStop();
            } catch(InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }
        }
    }

    public class PaperRunnable implements Runnable {
        public PaperRunnable() {}

        @Override
        public void run() {
            lock.lock();
            ready.release();
            try {
                while(choice != 1) {
                    paperCondition.await();
                    if(choice == -2) {
                        return;
                    }
                }
                System.out.println("Smoker[without Paper] acquired Paper and started smoking.");
                Thread.sleep(300);
                doStop();
            } catch(InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }
        }
    }

    public class MatchesRunnable implements Runnable {
        public MatchesRunnable() {}

        @Override
        public void run() {
            lock.lock();
            ready.release();
            try {
                while(choice != 2) {
                    matchesCondition.await();
                    if(choice == -2) {
                        return;
                    }
                }
                System.out.println("Smoker[without Matches] acquired Matches and started smoking.");
                Thread.sleep(300);
                doStop();
            } catch(InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }
        }
    }
    
    public void solve() throws InterruptedException {
        Thread agentThread = new Thread(new AgentRunnable(), "Agent-Thread");
        Thread tobaccoThread = new Thread(new TobaccoRunnable(), "Tobacco-Thread");
        Thread paperThread = new Thread(new PaperRunnable(), "Paper-Thread");
        Thread matchesThread = new Thread(new MatchesRunnable(), "Matches-Thread");

        agentThread.start();
        tobaccoThread.start();
        paperThread.start();
        matchesThread.start();



        agentThread.join();
        tobaccoThread.join();
        paperThread.join();
        matchesThread.join();
    }
    public static void main(String[] args) throws InterruptedException {
        new CigratteSmoker().solve();
    }
}
