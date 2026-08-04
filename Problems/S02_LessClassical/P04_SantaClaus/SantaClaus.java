package Problems.S02_LessClassical.P04_SantaClaus;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SantaClaus {
    private static final int REINDEER = 9;
    private static final int ELF_GROUP = 3;

    private final int elfCnt;
    private int elfWaiting = 0;
    private int reindeerWaiting = 0;
    private int reindeerDelivered = 0;
    private int elfHelped = 0;

    private final Lock lock = new ReentrantLock();
    private Condition santaSleepCondition = lock.newCondition();
    private Condition reindeerWaitingCondition = lock.newCondition();
    private Condition elfWaitingCondition = lock.newCondition();

    private boolean delivering = false;
    private boolean helping = false;

    private Random rnd = new Random();

    public SantaClaus(int elfCnt) {
        this.elfCnt = elfCnt;
    }

    private class SantaRunnable implements Runnable {
        @Override
        public void run() {
            while (true) {
                lock.lock();
                try {
                    while (reindeerWaiting < REINDEER && elfWaiting < ELF_GROUP) {
                        santaSleepCondition.await();
                    }
                    if (reindeerWaiting == REINDEER) {
                        System.out.println("Santa: Ho ho ho! 9 reindeer are back — DELIVERING TOYS!");
                        delivering = true;
                        reindeerWaiting = 0;
                        reindeerWaitingCondition.signalAll();
                    } else {
                        System.out.println("Santa: helping a group of 3 elves.");
                        helping = true;
                        elfWaiting = 0;
                        elfWaitingCondition.signalAll();
                    }
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                    Thread.currentThread().interrupt();
                    break;
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    private class ElfRunnable implements Runnable {
        private int id;

        public ElfRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            while (true) {
                work();

                lock.lock();
                try {
                    while (elfWaiting == ELF_GROUP || helping) {
                        elfWaitingCondition.await();
                    }
                    elfWaiting++;
                    System.out.println("Elf-" + id + " has a problem, waiting (" + elfWaiting + "/3).");
                    if (elfWaiting == ELF_GROUP) {
                        santaSleepCondition.signal();
                    }

                    while (!helping) {
                        elfWaitingCondition.await();
                    }
                    System.out.println("Elf-" + id + " is being helped by Santa.");

                    elfHelped++;
                    if (elfHelped == ELF_GROUP) {
                        elfHelped = 0;
                        helping = false;
                        elfWaitingCondition.signalAll();
                    }
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                }
            }
        }

        private void work() {
            try {
                Thread.sleep(200 + rnd.nextInt(200));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private class ReindeerRunnable implements Runnable {
        private int id;

        public ReindeerRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            while (true) {
                onVacation();

                lock.lock();
                try {
                    while (delivering) {
                        // safe guard when x reindeers are only needed for hitching and y reindeer
                        // threads exist(y > x)
                        reindeerWaitingCondition.await();
                    }
                    reindeerWaiting++;
                    System.out.println("Reindeer-" + id + " is back (" + reindeerWaiting + "/9).");
                    if (reindeerWaiting == REINDEER) {
                        santaSleepCondition.signal();
                    }

                    while (!delivering) {
                        reindeerWaitingCondition.await();
                    }
                    System.out.println("Reindeer-" + id + " is harnessed and delivering toys.");
                    reindeerDelivered++;
                    if (reindeerDelivered == REINDEER) {
                        reindeerDelivered = 0;
                        delivering = false;
                        reindeerWaitingCondition.signalAll();
                    }
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                } finally {
                    lock.unlock();
                }
            }
        }

        private void onVacation() {
            try {
                Thread.sleep(rnd.nextInt(300) + 300);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }
    }

    public void solve() throws InterruptedException {
        Thread santaThread = new Thread(new SantaRunnable(), "Santa-Thread");
        List<Thread> elfThreads = new ArrayList<>();
        List<Thread> reindeerThreads = new ArrayList<>();

        for (int i = 0; i < this.elfCnt; i++) {
            elfThreads.add(new Thread(new ElfRunnable(i), "Elf-Runnable-" + i));
        }
        for (int i = 0; i < REINDEER; i++) {
            reindeerThreads.add(new Thread(new ReindeerRunnable(i), "Reindeer-Thread-" + i));
        }
        santaThread.start();
        for (Thread t : elfThreads) {
            t.start();
        }
        for (Thread t : reindeerThreads) {
            t.start();
        }

        santaThread.join();
        for (Thread t : elfThreads) {
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
