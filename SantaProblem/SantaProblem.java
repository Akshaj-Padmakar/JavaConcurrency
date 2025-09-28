package SantaProblem;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Santa Claus problem.
 *
 * - 9 reindeer: when all 9 arrive Santa prepares sleigh and then all reindeer get hitched.
 * - elves come; when 3 elves are waiting Santa helps them, the 3 call getHelp concurrently.
 * - while 3 elves are being helped, other elves wait.
 * - if both 9 reindeer and 3 elves are waiting, reindeer get priority.
 *
 * This implementation uses a ReentrantLock + Conditions plus Semaphores to coordinate group releases.
 */
public class SantaProblem {

    // Shared state + synchronization
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition santaCond = lock.newCondition();   // wake Santa
    private final Condition elfEntry = lock.newCondition();    // block more elves while group being helped

    private int reindeerCount = 0;
    private int elfCount = 0;
    private boolean elvesBeingHelped = false;

    // Semaphores for group coordination
    private final Semaphore reindeerSem = new Semaphore(0); // Santa releases 9 permits when sleigh ready
    private final Semaphore reindeerDone = new Semaphore(0); // reindeer signal when hitched

    private final Semaphore elfSem = new Semaphore(0);   // Santa releases 3 permits for the 3 elves to get help
    private final Semaphore elfDone = new Semaphore(0);  // elves signal when they have been helped

    // Running flag for demo shutdown
    private volatile boolean running = true;

    private final Random rnd = new Random();

    /* ------------------- Santa ------------------- */
    private class Santa implements Runnable {
        @Override
        public void run() {
            try {
                while (running) {
                    lock.lock();
                    try {
                        // Wait until either 9 reindeer OR 3 elves are waiting.
                        // Use while loop for spurious wakeups.
                        while (reindeerCount < 9 && elfCount < 3 && running) {
                            System.out.println("[Santa] sleeping...");
                            santaCond.await();
                        }

                        if (!running) break;

                        // Reindeer priority: if 9 reindeer present, handle them first.
                        if (reindeerCount == 9) {
                            System.out.println("[Santa] woken up by reindeer. Preparing sleigh...");
                            prepareSleigh();

                            // Release the 9 reindeer so each can getHitched()
                            reindeerSem.release(9);
                            // Now release the lock and wait for reindeer to get hitched (they'll call reindeerDone.release()).
                        } else if (elfCount == 3) {
                            // Help elves
                            System.out.println("[Santa] woken up by elves. Helping elves...");
                            elvesBeingHelped = true; // block other elves from entering
                            helpElves();
                            // release exactly 3 elves to get help concurrently
                            elfSem.release(3);
                            // After releasing, we will wait for the 3 elves to signal completion (below)
                        }
                    } finally {
                        lock.unlock();
                    }

                    // Wait for the group to finish *outside* of lock (so others can proceed)
                    if (reindeerCount == 9) {
                        // Wait for all 9 reindeer to signal they are hitched
                        for (int i = 0; i < 9; i++) {
                            reindeerDone.acquire();
                        }
                        // Reset reindeerCount under lock
                        lock.lock();
                        try {
                            reindeerCount = 0;
                        } finally {
                            lock.unlock();
                        }
                        System.out.println("[Santa] all reindeer hitched; ready for next time.");
                    } else if (elfCount == 3) {
                        // Wait for 3 elves to finish
                        for (int i = 0; i < 3; i++) {
                            elfDone.acquire();
                        }
                        // Reset elfCount and allow waiting elves to enter
                        lock.lock();
                        try {
                            elfCount = 0;
                            elvesBeingHelped = false;
                            // wake waiting elves (they will check elvesBeingHelped)
                            elfEntry.signalAll();
                        } finally {
                            lock.unlock();
                        }
                        System.out.println("[Santa] finished helping elves; going to sleep.");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.println("[Santa] exiting.");
        }

        private void prepareSleigh() {
            System.out.println("[Santa] prepareSleigh()");
            // simulate work
            try {
                Thread.sleep(200 + rnd.nextInt(200));
            } catch (InterruptedException ignored) {}
        }

        private void helpElves() {
            System.out.println("[Santa] helpElves()");
            try {
                Thread.sleep(200 + rnd.nextInt(200));
            } catch (InterruptedException ignored) {}
        }
    }

    /* ------------------- Reindeer ------------------- */
    private class Reindeer implements Runnable {
        private final int id;

        Reindeer(int id) { this.id = id; }

        @Override
        public void run() {
            try {
                while (running) {
                    // simulate vacation
                    Thread.sleep(500 + rnd.nextInt(1000));

                    // Arrive
                    lock.lock();
                    try {
                        reindeerCount++;
                        System.out.printf("[Reindeer-%d] arrived. reindeerCount=%d%n", id, reindeerCount);
                        if (reindeerCount == 9) {
                            // wake Santa
                            santaCond.signal();
                        }
                    } finally {
                        lock.unlock();
                    }

                    // Wait until Santa releases permit to get hitched
                    reindeerSem.acquire();

                    // Now get hitched
                    getHitched(id);

                    // signal Santa we're done hitched
                    reindeerDone.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.printf("[Reindeer-%d] exiting%n", id);
        }

        private void getHitched(int id) {
            System.out.printf("[Reindeer-%d] getHitched()%n", id);
            try {
                Thread.sleep(100 + rnd.nextInt(200));
            } catch (InterruptedException ignored) {}
        }
    }

    /* ------------------- Elf ------------------- */
    private class Elf implements Runnable {
        private final int id;

        Elf(int id) { this.id = id; }

        @Override
        public void run() {
            try {
                while (running) {
                    // Work for some time
                    Thread.sleep(200 + rnd.nextInt(800));

                    // Need help: try to join the group of up to 3 elves
                    lock.lock();
                    try {
                        // If a group is currently being helped, wait until it's done
                        while (elvesBeingHelped) {
                            elfEntry.await();
                        }

                        // If three are already waiting (shouldn't happen because we block more than 3),
                        // we still do the safety blocking loop.
                        if (elfCount == 3) {
                            // this should not be reached often due to the previous guard, but safe-check
                            while (elfCount == 3) {
                                elfEntry.await();
                            }
                        }

                        // join waiting elves
                        elfCount++;
                        System.out.printf("[Elf-%d] waiting for help. elfCount=%d%n", id, elfCount);
                        if (elfCount == 3) {
                            // wake Santa
                            santaCond.signal();
                        }
                    } finally {
                        lock.unlock();
                    }

                    // Wait until Santa allows the 3 elves to be helped
                    elfSem.acquire();

                    // get help concurrently (three of them)
                    getHelp(id);

                    // signal Santa that this elf is done getting help
                    elfDone.release();

                    // after being helped, loop back to work again
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.printf("[Elf-%d] exiting%n", id);
        }

        private void getHelp(int id) {
            System.out.printf("[Elf-%d] getHelp()%n", id);
            try {
                Thread.sleep(100 + rnd.nextInt(200));
            } catch (InterruptedException ignored) {}
        }
    }

    /* ------------------- Demo harness ------------------- */
    public void startSimulation(int elfCountTotal, int reindeerCountTotal, long simDurationMs) throws InterruptedException {
        Thread santa = new Thread(new Santa(), "Santa");
        List<Thread> reindeers = new ArrayList<>();
        List<Thread> elves = new ArrayList<>();

        // start santa
        santa.start();

        // start reindeers (should be 9 in problem)
        for (int i = 0; i < reindeerCountTotal; i++) {
            Thread t = new Thread(new Reindeer(i), "Reindeer-" + i);
            reindeers.add(t);
            t.start();
        }

        // start elves
        for (int i = 0; i < elfCountTotal; i++) {
            Thread t = new Thread(new Elf(i), "Elf-" + i);
            elves.add(t);
            t.start();
        }

        // Let the simulation run for simDurationMs, then stop cleanly
        Thread.sleep(simDurationMs);
        System.out.println("### Simulation stopping...");

        // stop
        running = false;

        // Wake everyone blocked so they can observe running==false and exit
        lock.lock();
        try {
            santaCond.signalAll();
            elfEntry.signalAll();
        } finally {
            lock.unlock();
        }
        // release semaphores to unblock threads possibly blocked on them
        reindeerSem.release(reindeerCountTotal); // in case some reindeer are waiting
        elfSem.release(elfCountTotal);
        // wait for threads to finish
        for (Thread t : reindeers) t.join(1000);
        for (Thread t : elves) t.join(1000);
        santa.interrupt();
        santa.join(1000);
        System.out.println("### Simulation finished.");
    }

    public static void main(String[] args) throws InterruptedException {
        SantaProblem sim = new SantaProblem();
        int totalElves = 10;      // number of elf threads
        int totalReindeer = 9;    // should be 9
        long durationMs = 15_000; // run for 15 seconds demo

        sim.startSimulation(totalElves, totalReindeer, durationMs);
    }
}
