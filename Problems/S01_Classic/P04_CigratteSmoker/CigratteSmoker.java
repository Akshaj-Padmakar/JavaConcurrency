package Problems.S01_Classic.P04_CigratteSmoker;

import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class CigratteSmoker {

    private final Lock lock = new ReentrantLock();

    private boolean paper = false;
    private boolean matches = false;
    private boolean tobacco = false;

    private boolean stop = false;

    private Random rnd = new Random();
    private Condition pusherCondition = lock.newCondition();

    private Condition paperCondition = lock.newCondition();
    private Condition matchesCondition = lock.newCondition();
    private Condition tobaccoCondition = lock.newCondition();

    public class AgentRunnable implements Runnable {
        @Override
        public void run() {
            lock.lock();
            try {
                int agentChoice = rnd.nextInt(3);

                if (agentChoice == 0) {
                    tobacco = true;
                    matches = true;
                    System.out.println("Agent has put Tobacco and Matches on the table.");
                } else if (agentChoice == 1) {
                    paper = true;
                    matches = true;
                    System.out.println("Agent has put Paper and Matches on the table.");
                } else {
                    paper = true;
                    tobacco = true;
                    System.out.println("Agent has put Paper and Tobacco on the table.");
                }
                pusherCondition.signal();
            } finally {
                lock.unlock();
            }
        }
    }

    public class PusherRunnable implements Runnable {
        @Override
        public void run() {
            lock.lock();
            try {
                while (!((paper && matches) || (paper && tobacco) || (matches && tobacco))) {
                    pusherCondition.await();
                }
                if (!paper) { // matches and tobacco are put forwar by agent.
                    paperCondition.signal();
                } else if (!matches) {
                    matchesCondition.signal();
                } else {
                    tobaccoCondition.signal();
                }

            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }
    }

    public class PaperSmokerRunnable implements Runnable {
        @Override
        public void run() {
            lock.lock();
            try {
                while (!(matches && tobacco) && !stop) {
                    paperCondition.await();
                }
                if (stop) {
                    return;
                }
                System.out.println("Smoker with Paper have all the ingredients now ! ROLLING.......");
                System.out.println("SMOKING.....");
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            } finally {
                doFinally();
                lock.unlock();
            }
        }
    }

    public class MatchesSmokerRunnable implements Runnable {
        @Override
        public void run() {
            lock.lock();
            try {
                while (!(paper && tobacco) && !stop) {
                    matchesCondition.await();
                }

                if (stop) {
                    return;
                }
                System.out.println("Smoker with Matches have all the ingredients now ! ROLLING.......");
                System.out.println("SMOKING.....");
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            } finally {
                doFinally();
                lock.unlock();
            }
        }
    }

    public class TobaccoSmokerRunnable implements Runnable {
        @Override
        public void run() {
            lock.lock();
            try {
                while (!(paper && matches) && !stop) {
                    tobaccoCondition.await();
                }

                if (stop) {
                    return;
                }
                System.out.println("Smoker with Tobacco have all the ingredients now ! ROLLING.......");
                System.out.println("SMOKING.....");
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            } finally {
                doFinally();
                lock.unlock();
            }
        }
    }

    private void doFinally() {
        stop = true;
        paperCondition.signal();
        tobaccoCondition.signal();
        matchesCondition.signal();
    }

    public void solve() throws InterruptedException {
        Thread agentThread = new Thread(new AgentRunnable(), "Agent-Thread");

        Thread pusherThread = new Thread(new PusherRunnable(), "Pusher-Thread");

        Thread paperThread = new Thread(new PaperSmokerRunnable(), "Paper-Thread");
        Thread tobaccoThread = new Thread(new TobaccoSmokerRunnable(), "Tobacco-Thread");
        Thread matchesThread = new Thread(new MatchesSmokerRunnable(), "Matches-Thread");

        agentThread.start();

        pusherThread.start();

        paperThread.start();
        tobaccoThread.start();
        matchesThread.start();

        agentThread.join();
        pusherThread.join();

        paperThread.join();
        tobaccoThread.join();
        matchesThread.join();
    }

    public static void main(String args[]) throws InterruptedException {
        new CigratteSmoker().solve();
    }
}
