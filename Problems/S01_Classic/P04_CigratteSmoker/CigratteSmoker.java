package Problems.S01_Classic.P04_CigratteSmoker;

import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class CigratteSmoker {
    private Lock lock = new ReentrantLock();
    private Condition tobaccoPusherCondition = lock.newCondition();
    private Condition tobaccoSmokerCondition = lock.newCondition();

    private Condition matchesPusherCondition = lock.newCondition();
    private Condition matchesSmokerCondition = lock.newCondition();

    private Condition paperPusherCondition = lock.newCondition();
    private Condition paperSmokerCondition = lock.newCondition();

    private final Random rnd = new Random();

    private boolean matches = false;
    private boolean paper = false;
    private boolean tobacco = false;

    private boolean stop = false;

    private class AgentRunnable implements Runnable {
        @Override
        public void run() {
            lock.lock();
            try {
                int r = rnd.nextInt(3);

                if (r == 0) { // No Tobacco
                    matches = true;
                    paper = true;
                    matchesPusherCondition.signal();
                    paperPusherCondition.signal();
                    System.out.println("Agent has put Matches and Paper on the table.");
                } else if (r == 1) { // No Matches
                    tobacco = true;
                    paper = true;
                    tobaccoPusherCondition.signal();
                    paperPusherCondition.signal();
                    System.out.println("Agent has put Tobacco and Paper on the table.");
                } else { // No Paper
                    tobacco = true;
                    matches = true;
                    tobaccoPusherCondition.signal();
                    matchesPusherCondition.signal();
                    System.out.println("Agent has put Tobacco and Matches on the table.");
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private class TobaccoPusherRunnable implements Runnable {
        @Override
        public void run() {
            lock.lock();
            try {
                while (!tobacco && stop == false) {
                    tobaccoPusherCondition.await();

                }
                if (stop) {
                    return;
                }

                if (paper) {
                    matchesSmokerCondition.signal();
                } else if (matches) {
                    paperSmokerCondition.signal();
                }
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }
        }
    }

    private class MatchesPusherRunnable implements Runnable {
        @Override
        public void run() {
            lock.lock();
            try {
                while (!matches && stop == false) {
                    matchesPusherCondition.await();
                }
                if (stop) {
                    return;
                }

                if (tobacco) {
                    paperSmokerCondition.signal();
                } else if (paper) {
                    tobaccoSmokerCondition.signal();
                }
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }
        }
    }

    private class PaperPusherRunnable implements Runnable {
        @Override
        public void run() {
            lock.lock();
            try {
                while (!paper && stop == false) {
                    paperPusherCondition.await();
                }
                if (stop) {
                    return;
                }

                if (tobacco) {
                    matchesSmokerCondition.signal();
                } else if (matches) {
                    tobaccoSmokerCondition.signal();
                }
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }
        }
    }

    private class TobaccoSmokerRunnable implements Runnable {
        @Override
        public void run() {
            lock.lock();
            try {
                while (!(paper && matches) && stop == false) {
                    tobaccoSmokerCondition.await();
                }
                if (stop) {
                    return;
                }
                System.out.println("Smoker with Tobacco have all the ingridents now ! ROLLING.......");
                System.out.println("SMOKING.....");
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                doFinally();
            }
        }
    }

    private class PaperSmokerRunnable implements Runnable {
        @Override
        public void run() {
            lock.lock();
            try {
                while (!(tobacco && matches) && stop == false) {
                    paperSmokerCondition.await();
                }
                if (stop) {
                    return;
                }
                System.out.println("Smoker with Paper have all the ingridents now ! ROLLING.......");
                System.out.println("SMOKING.....");
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                doFinally();
            }
        }
    }

    private class MatchesSmokerRunnable implements Runnable {
        @Override
        public void run() {
            lock.lock();
            try {
                while (!(tobacco && paper) && stop == false) {
                    matchesSmokerCondition.await();
                }
                if (stop) {
                    return;
                }
                System.out.println("Smoker with Matches have all the ingridents now ! ROLLING.......");
                System.out.println("SMOKING.....");
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                doFinally();
            }
        }
    }

    private void doFinally() {
        paper = false;
        tobacco = false;
        matches = false;
        stop = true;

        tobaccoPusherCondition.signal();
        tobaccoSmokerCondition.signal();

        matchesPusherCondition.signal();
        matchesSmokerCondition.signal();

        paperPusherCondition.signal();
        paperSmokerCondition.signal();
        lock.unlock();
    }

    public void solve() throws InterruptedException {
        Thread agentThread = new Thread(new AgentRunnable(), "Agent-Thread");
        Thread tobaccoPusherThread = new Thread(new TobaccoPusherRunnable(), "Tobacco-Pusher-Thread");
        Thread tobaccoSmokerThread = new Thread(new TobaccoSmokerRunnable(), "Tobacco-Smoker-Thread");

        Thread matchPusherThread = new Thread(new MatchesPusherRunnable(), "Match-Pusher-Runnable");
        Thread matchSmokerThread = new Thread(new MatchesSmokerRunnable(), "Match-Smoker-Thread");

        Thread paperPusherThread = new Thread(new PaperPusherRunnable(), "Paper-Pusher-Runnable");
        Thread paperSmokerThread = new Thread(new PaperSmokerRunnable(), "Paper-Smoker-Thread");

        agentThread.start();
        tobaccoPusherThread.start();
        tobaccoSmokerThread.start();

        matchPusherThread.start();
        matchSmokerThread.start();

        paperPusherThread.start();
        paperSmokerThread.start();

        agentThread.join();
        tobaccoPusherThread.join();
        tobaccoSmokerThread.join();

        matchPusherThread.join();
        matchSmokerThread.join();

        paperPusherThread.join();
        paperSmokerThread.join();

    }

    public static void main(String[] args) throws InterruptedException {
        new CigratteSmoker().solve();
    }
}
