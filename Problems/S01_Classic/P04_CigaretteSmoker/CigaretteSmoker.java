package Problems.S01_Classic.P04_CigaretteSmoker;

import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class CigaretteSmoker {

    private final Lock lock = new ReentrantLock();

    private boolean paper = false;
    private boolean matches = false;
    private boolean tobacco = false;

    private boolean stop = false;

    private final Random rnd = new Random();
    private final Condition pusherCondition = lock.newCondition();

    private final Condition paperCondition = lock.newCondition();
    private final Condition matchesCondition = lock.newCondition();
    private final Condition tobaccoCondition = lock.newCondition();

    private class AgentRunnable implements Runnable {
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

    private class PusherRunnable implements Runnable {
        @Override
        public void run() {
            lock.lock();
            try {
                while (!((paper && matches) || (paper && tobacco) || (matches && tobacco))) {
                    pusherCondition.await();
                }
                if (!paper) { // matches and tobacco are put forward by agent.
                    paperCondition.signal();
                } else if (!matches) {
                    matchesCondition.signal();
                } else {
                    tobaccoCondition.signal();
                }

            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }
    }

    private class PaperSmokerRunnable implements Runnable {
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
                Thread.currentThread().interrupt();
            } finally {
                doFinally();
                lock.unlock();
            }
        }
    }

    private class MatchesSmokerRunnable implements Runnable {
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
                Thread.currentThread().interrupt();
            } finally {
                doFinally();
                lock.unlock();
            }
        }
    }

    private class TobaccoSmokerRunnable implements Runnable {
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
                Thread.currentThread().interrupt();
            } finally {
                doFinally();
                lock.unlock();
            }
        }
    }

    private void doFinally() {
        stop = true;
        paper = false;
        tobacco = false;
        matches = false;
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

    public static void main(String[] args) throws InterruptedException {
        new CigaretteSmoker().solve();
    }
}
