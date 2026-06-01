package Problems.S02_LessClassical.P04_SantaClaus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SantaClaus {

    private final int reindeerCnt = 9;
    private final int elvesCnt;

    private final Lock lock = new ReentrantLock();

    private final Condition santaSleepCondition = lock.newCondition();
    private final Condition elvesWaitingCondition = lock.newCondition();
    private final Condition elvesHelpCondition = lock.newCondition();
    private final Condition reindeerCondition = lock.newCondition();

    private int elvesWaiting = 0;
    private int elvesBeingHelped = 0;

    private int reindeerReturned = 0;

    private boolean santaHelpingElves = false;
    private boolean santaPreparingSleigh = false;

    public SantaClaus(int elvesCnt) {
        this.elvesCnt = elvesCnt;
    }

    private class SantaRunnable implements Runnable {

        @Override
        public void run() {
            while (true) {
                lock.lock();
                try {
                    while (reindeerReturned < 9 && elvesWaiting < 3) {
                        System.out.println("Santa sleeping...");
                        santaSleepCondition.await();
                    }

                    if (reindeerReturned == 9) { // Reindeer have priority
                        santaPreparingSleigh = true;
                        System.out.println("Santa preparing sleigh!");

                        reindeerCondition.signalAll();
                    } else if (elvesWaiting == 3) {
                        santaHelpingElves = true;
                        elvesBeingHelped = 3;
                        System.out.println("Santa helping elves!");

                        elvesHelpCondition.signalAll();
                    }

                } catch (InterruptedException ex) {
                    ex.printStackTrace();

                } finally {
                    lock.unlock();
                }

                try {
                    if (santaPreparingSleigh) {
                        Thread.sleep(500);
                        lock.lock();
                        try {
                            System.out.println("Santa finished sleigh prep!");

                            reindeerReturned = 0;
                            santaPreparingSleigh = false;
                        } finally {
                            lock.unlock();
                        }
                    } else if (santaHelpingElves) {

                        Thread.sleep(300);
                        lock.lock();
                        try {
                            System.out.println("Santa finished helping elves!");
                            santaHelpingElves = false;
                            elvesWaiting = 0;
                            elvesWaitingCondition.signalAll();
                        } finally {
                            lock.unlock();
                        }
                    }

                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

    private class ElvesRunnable implements Runnable {

        private final int id;

        public ElvesRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }

                lock.lock();
                try {
                    while (elvesWaiting == 3 || santaHelpingElves) {
                        elvesWaitingCondition.await();
                    }
                    elvesWaiting++;
                    System.out.println("Elf-" + id + " waiting. count=" + elvesWaiting);
                    if (elvesWaiting == 3) {
                        System.out.println("3 elves waking Santa!");
                        santaSleepCondition.signal();
                    }
                    while (!santaHelpingElves) {
                        elvesHelpCondition.await();
                    }
                    System.out.println("Elf-" + id + " getting help");
                    elvesBeingHelped--;

                    if (elvesBeingHelped == 0) {
                        System.out.println("Last elf done.");
                    }

                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    private class ReindeerRunnable implements Runnable {

        private final int id;

        public ReindeerRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }

                lock.lock();
                try {
                    reindeerReturned++;
                    System.out.println("Reindeer-" + id + " returned. count=" + reindeerReturned);

                    if (reindeerReturned == 9) {
                        System.out.println("All reindeer back! Waking Santa!");
                        santaSleepCondition.signal();
                    }
                    while (!santaPreparingSleigh) {
                        reindeerCondition.await();
                    }
                    System.out.println("Reindeer-" + id + " getting hitched.");
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    public void solve() throws InterruptedException {
        Thread santaThread = new Thread(new SantaRunnable(), "Santa-Thread");

        List<Thread> elvesThread = new ArrayList<>();

        for (int i = 0; i < elvesCnt; i++) {

            elvesThread.add(new Thread(new ElvesRunnable(i), "Elf-Thread-" + i));
        }

        List<Thread> reindeerThreads = new ArrayList<>();

        for (int i = 0; i < reindeerCnt; i++) {
            reindeerThreads.add(new Thread(new ReindeerRunnable(i), "Reindeer-Thread-" + i));
        }

        santaThread.start();
        for (Thread t : elvesThread) {
            t.start();
        }
        for (Thread t : reindeerThreads) {
            t.start();
        }

        santaThread.join();
        for (Thread t : elvesThread) {
            t.join();
        }
        for (Thread t : reindeerThreads) {
            t.join();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        new SantaClaus(10).solve();
    }
}