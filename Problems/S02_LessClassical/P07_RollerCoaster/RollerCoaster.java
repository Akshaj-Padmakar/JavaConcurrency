package Problems.S02_LessClassical.P07_RollerCoaster;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/*
 * One car, capacity C, N passengers (C < N). The car repeats: load C -> run -> unload C.
 * Each passenger: board -> ride -> unboard.
 *
 * Two rendezvous per ride, both re-armed each ride:
 *   1. BOARDING : car waits for C passengers aboard; passengers wait for departure.
 *   2. UNBOARDING: car waits for all C off;         passengers wait for arrival.
 *
 * Exact-C admission uses the "one cohort in flight" invariant (like River / H2O):
 * a boarding gate that admits exactly C, then closes until the ride finishes.
 *
 * Infinite simulation (matches the repo convention). See Solution.md for termination.
 */
public class RollerCoaster {
    private final int N; // passenger threads
    private final int C; // car capacity

    private final Lock lock = new ReentrantLock();
    private final Condition boardingOpen = lock.newCondition(); // passengers wait for a seat
    private final Condition carFull = lock.newCondition();      // car waits until C aboard
    private final Condition running = lock.newCondition();      // passengers wait for ride to finish
    private final Condition allAshore = lock.newCondition();    // car waits until C have unboarded

    private int boarded = 0;      // passengers seated in the current ride
    private int unboarded = 0;    // passengers who have gotten off the current ride
    private boolean loading = false; // gate: true while the car is at the platform accepting riders
    private int rideId = 0;       // generation: bumped when the ride finishes

    private final Random rnd = new Random();

    public RollerCoaster(int N, int C) {
        this.N = N;
        this.C = C;
    }

    // ---------------- Car ----------------
    private class CarRunnable implements Runnable {
        @Override
        public void run() {
            while (true) {
                lock.lock();
                try {
                    // Open the platform for boarding and wait until exactly C are aboard.
                    boarded = 0;
                    unboarded = 0;
                    loading = true;
                    boardingOpen.signalAll();          // let C passengers in
                    while (boarded < C) {
                        carFull.await();
                    }
                    loading = false;                   // close the gate: no more boarders this ride
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } finally {
                    lock.unlock();
                }

                runRide();                             // slow work OUTSIDE the lock

                lock.lock();
                try {
                    // Arrived: release the C riders and wait until all have unboarded.
                    rideId++;                          // ride finished -> wake the seated riders
                    running.signalAll();
                    while (unboarded < C) {
                        allAshore.await();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } finally {
                    lock.unlock();
                }
            }
        }

        private void runRide() {
            System.out.println(">>> Car is RUNNING with " + C + " passengers <<<");
            try {
                Thread.sleep(300);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            System.out.println("<<< Car has RETURNED >>>");
        }
    }

    // ---------------- Passenger ----------------
    private class PassengerRunnable implements Runnable {
        private final int id;

        PassengerRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            while (true) {
                wander();                              // slow work OUTSIDE the lock

                lock.lock();
                try {
                    // BOARD: wait for an open, not-yet-full car (exact-C gate).
                    while (!loading || boarded == C) {
                        boardingOpen.await();
                    }
                    boarded++;
                    int myRide = rideId;               // remember which ride I'm on
                    System.out.println("Passenger-" + id + " boarded (" + boarded + "/" + C + ").");
                    if (boarded == C) {
                        carFull.signal();              // last one in -> tell the car to depart
                    }

                    // RIDE: wait until the car returns from MY ride.
                    while (myRide == rideId) {
                        running.await();
                    }

                    // UNBOARD.
                    unboarded++;
                    System.out.println("Passenger-" + id + " unboarded (" + unboarded + "/" + C + ").");
                    if (unboarded == C) {
                        allAshore.signal();            // last one off -> car may start the next ride
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } finally {
                    lock.unlock();
                }
            }
        }

        private void wander() {
            try {
                Thread.sleep(rnd.nextInt(400) + 100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void solve() throws InterruptedException {
        Thread carThread = new Thread(new CarRunnable(), "Car");
        carThread.start();

        List<Thread> passengers = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            passengers.add(new Thread(new PassengerRunnable(i), "Passenger-" + i));
        }
        for (Thread t : passengers) {
            t.start();
        }

        carThread.join();   // infinite simulation — runs until interrupted
        for (Thread t : passengers) {
            t.join();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        new RollerCoaster(8, 4).solve();   // 8 passengers, car holds 4
    }
}
