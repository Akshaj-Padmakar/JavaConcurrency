package Problems.Classic.ReadWriteLock.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import Problems.Classic.ReadWriteLock.ReadWriteLock;

/**
 * ReadWriteLockTest - runs multiple tests on ReadWriteLock implementation and reports failures.
 *
 * Usage: compile with ReadWriteLock in same package and run.
 */
public class ReadWriteLockTest {

    // Helpers
    private static void pass(String name) {
        System.out.printf("[PASS] %s%n", name);
    }

    private static void fail(String name, String reason) {
        System.out.printf("[FAIL] %s : %s%n", name, reason);
    }

    private static boolean joinWithTimeout(Thread t, long ms) {
        try {
            t.join(ms);
            return !t.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // Test 1: Read reentrancy (acquire read lock twice, release twice)
    private static void testReadReentrancy() {
        final String name = "testReadReentrancy";
        ReadWriteLock lock = new ReadWriteLock();

        Thread t = new Thread(() -> {
            try {
                lock.readLock();
                // reentrant read
                lock.readLock();
                // simulate work
                Thread.sleep(50);
                lock.readUnlock();
                lock.readUnlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } catch (Throwable ex) {
                throw new RuntimeException(ex);
            }
        });

        t.start();
        if (joinWithTimeout(t, 1000)) pass(name);
        else fail(name, "thread hung or threw (see stacktrace)");
    }

    // Test 2: readUnlock without readLock should throw IllegalMonitorStateException
    private static void testReadUnlockWithoutLock() {
        final String name = "testReadUnlockWithoutLock";
        ReadWriteLock lock = new ReadWriteLock();
        boolean ok = false;
        try {
            lock.readUnlock();
            fail(name, "expected IllegalMonitorStateException but none thrown");
            return;
        } catch (IllegalMonitorStateException ex) {
            ok = true;
        } catch (Throwable ex) {
            fail(name, "unexpected exception: " + ex);
            return;
        }
        if (ok) pass(name);
    }

    // Test 3: Write reentrancy (acquire write lock twice, release twice)
    private static void testWriteReentrancy() {
        final String name = "testWriteReentrancy";
        ReadWriteLock lock = new ReadWriteLock();

        Thread t = new Thread(() -> {
            try {
                lock.writeLock();
                // reentrant write
                lock.writeLock();
                Thread.sleep(50);
                lock.writeUnlock();
                lock.writeUnlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } catch (Throwable ex) {
                throw new RuntimeException(ex);
            }
        });

        t.start();
        if (joinWithTimeout(t, 1000)) pass(name);
        else fail(name, "thread hung or threw (see stacktrace)");
    }

    // Test 4: Multiple readers may concurrently hold the read lock
    private static void testReadersConcurrent() {
        final String name = "testReadersConcurrent";
        ReadWriteLock lock = new ReadWriteLock();
        final int readers = 3;
        final AtomicInteger active = new AtomicInteger(0);
        final AtomicInteger maxActive = new AtomicInteger(0);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < readers; i++) {
            Thread t = new Thread(() -> {
                try {
                    lock.readLock();
                    int now = active.incrementAndGet();
                    maxActive.updateAndGet(prev -> Math.max(prev, now));
                    // hold a bit
                    Thread.sleep(200);
                    active.decrementAndGet();
                    lock.readUnlock();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable ex) {
                    throw new RuntimeException(ex);
                }
            });
            threads.add(t);
        }

        threads.forEach(Thread::start);
        boolean ok = true;
        for (Thread t : threads) {
            if (!joinWithTimeout(t, 2000)) ok = false;
        }

        if (!ok) fail(name, "one or more reader threads hung or threw");
        else if (maxActive.get() >= readers) pass(name);
        else fail(name, "readers not concurrent: maxActive=" + maxActive.get());
    }

    // Test 5: Writer exclusivity - while writer holds lock, readers must not enter
    private static void testWriterExclusive() {
        final String name = "testWriterExclusive";
        ReadWriteLock lock = new ReadWriteLock();
        final AtomicBoolean writerActive = new AtomicBoolean(false);
        final AtomicBoolean readerEnteredDuringWrite = new AtomicBoolean(false);

        Thread writer = new Thread(() -> {
            try {
                lock.writeLock();
                writerActive.set(true);
                // hold the writer lock for a bit
                Thread.sleep(300);
                writerActive.set(false);
                lock.writeUnlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable ex) {
                throw new RuntimeException(ex);
            }
        });

        Thread reader = new Thread(() -> {
            try {
                // small delay to allow writer to start first
                Thread.sleep(20);
                lock.readLock();
                // If writerActive true here, then reader entered while writer held it
                if (writerActive.get()) {
                    readerEnteredDuringWrite.set(true);
                }
                // hold briefly, then unlock
                Thread.sleep(10);
                lock.readUnlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable ex) {
                throw new RuntimeException(ex);
            }
        });

        writer.start();
        reader.start();

        boolean ok = joinWithTimeout(writer, 2000) && joinWithTimeout(reader, 2000);
        if (!ok) fail(name, "threads hung or threw");
        else if (readerEnteredDuringWrite.get()) fail(name, "reader entered while writer active");
        else pass(name);
    }

    // Test 6: try upgrading from readLock to writeLock (no other readers)
    private static void testUpgradeReadToWriteAlone() {
        final String name = "testUpgradeReadToWriteAlone";
        ReadWriteLock lock = new ReadWriteLock();

        Thread t = new Thread(() -> {
            try {
                lock.readLock();
                // Now try to acquire write lock (upgrade)
                lock.writeLock();
                // success if acquired reentrantly
                lock.writeUnlock();
                lock.readUnlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } catch (Throwable ex) {
                throw new RuntimeException(ex);
            }
        });

        t.start();
        if (joinWithTimeout(t, 1000)) pass(name);
        else fail(name, "upgrade blocked or exception occurred");
    }

    // Test 7: upgrade read->write blocked when another reader present
    private static void testUpgradeReadToWriteBlocked() {
        final String name = "testUpgradeReadToWriteBlocked";
        ReadWriteLock lock = new ReadWriteLock();
        final CountDownLatch bothLocked = new CountDownLatch(2);

        Thread reader1 = new Thread(() -> {
            try {
                lock.readLock();
                bothLocked.countDown();
                // hold until main interrupts/ends
                Thread.sleep(500);
                lock.readUnlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread upgrader = new Thread(() -> {
            try {
                lock.readLock();            // first holds a read lock
                bothLocked.countDown();
                // wait a bit to ensure reader1 also holds read lock
                Thread.sleep(50);
                // try to upgrade to write - should block because reader1 exists
                lock.writeLock();          // if implementation allowed upgrade incorrectly, this will proceed
                // if we get here, upgrade succeeded unexpectedly
                lock.writeUnlock();
                lock.readUnlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable ex) {
                throw new RuntimeException(ex);
            }
        });

        reader1.start();
        upgrader.start();

        try {
            // wait for both to have obtained read locks
            bothLocked.await(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // wait a short time for upgrader to attempt upgrade
        boolean finished = joinWithTimeout(upgrader, 200);
        if (finished) {
            // upgrader finished quickly -> upgrade didn't block -> INCORRECT if another reader was present
            fail(name, "upgrade succeeded while another reader present (should block). This indicates incorrect grantWriteAccess logic.");
        } else {
            // still blocked -> expected
            // cleanup: interrupt upgrader and join
            upgrader.interrupt();
            reader1.interrupt();
            joinWithTimeout(upgrader, 200);
            joinWithTimeout(reader1, 200);
            pass(name);
        }
    }

    // Test 8: writeUnlock without writeLock -> IllegalMonitorStateException
    private static void testIllegalWriteUnlock() {
        final String name = "testIllegalWriteUnlock";
        ReadWriteLock lock = new ReadWriteLock();
        try {
            lock.writeUnlock();
            fail(name, "expected IllegalMonitorStateException but none thrown");
        } catch (IllegalMonitorStateException ex) {
            pass(name);
        } catch (Throwable ex) {
            fail(name, "unexpected exception: " + ex);
        }
    }

    // Test 9: stress scenario: many readers, writer attempts and ensure writer eventually gets access (no starvation)
    private static void testWriterNotStarved() {
        final String name = "testWriterNotStarved";
        ReadWriteLock lock = new ReadWriteLock();
        final AtomicBoolean writerAcquired = new AtomicBoolean(false);
        final AtomicBoolean stop = new AtomicBoolean(false);

        // continuous readers
        Thread readerFeeder = new Thread(() -> {
            while (!stop.get()) {
                Thread r = new Thread(() -> {
                    try {
                        lock.readLock();
                        Thread.sleep(20);
                        lock.readUnlock();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                r.start();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        Thread writer = new Thread(() -> {
            try {
                // let readers run for a bit
                Thread.sleep(50);
                lock.writeLock();
                writerAcquired.set(true);
                lock.writeUnlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable ex) {
                throw new RuntimeException(ex);
            }
        });

        readerFeeder.start();
        writer.start();

        boolean ok = joinWithTimeout(writer, 2000);
        stop.set(true);
        try { readerFeeder.join(200); } catch (InterruptedException ignored) {}
        if (!ok) fail(name, "writer thread hung or starved");
        else if (!writerAcquired.get()) fail(name, "writer did not acquire lock");
        else pass(name);
    }

    private static void test() throws InterruptedException {
        final ReadWriteLock lock = new ReadWriteLock();

        // 1) Two concurrent readers
        Thread reader1 = new Thread(() -> {
            try {
                System.out.println("R1: trying to read");
                lock.readLock();
                System.out.println("R1: started reading");
                Thread.sleep(300);
                System.out.println("R1: finished reading");
                lock.readUnlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "R1");

        Thread reader2 = new Thread(() -> {
            try {
                Thread.sleep(20);
                System.out.println("R2: trying to read");
                lock.readLock();
                System.out.println("R2: started reading");
                Thread.sleep(250);
                System.out.println("R2: finished reading");
                lock.readUnlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "R2");

        // 2) Writer that arrives while readers active (should wait)
        Thread writer1 = new Thread(() -> {
            try {
                Thread.sleep(60);
                System.out.println("W1: trying to write");
                lock.writeLock();
                System.out.println("W1: started writing");
                Thread.sleep(200);
                System.out.println("W1: finished writing");
                lock.writeUnlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "W1");

        // 3) Reader that arrives after writer requested — should block until writer completes
        Thread reader3 = new Thread(() -> {
            try {
                Thread.sleep(120);
                System.out.println("R3: trying to read (should wait until W1 done)");
                lock.readLock();
                System.out.println("R3: started reading");
                Thread.sleep(150);
                System.out.println("R3: finished reading");
                lock.readUnlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "R3");

        // 4) Reentrant writer test
        Thread writerReentrant = new Thread(() -> {
            try {
                Thread.sleep(700);
                System.out.println("WR: trying to write reentrantly");
                lock.writeLock();
                System.out.println("WR: got write lock first time");
                lock.writeLock();
                System.out.println("WR: got write lock second time (reentrant)");
                Thread.sleep(120);
                lock.writeUnlock();
                System.out.println("WR: released write lock once");
                lock.writeUnlock();
                System.out.println("WR: fully released write lock");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "WR");

        // 5) Reader->Writer upgrade (only works if it's the only reader)
        Thread upgrader = new Thread(() -> {
            try {
                Thread.sleep(950);
                System.out.println("UP: acquiring read lock then will try to upgrade to write");
                lock.readLock();
                System.out.println("UP: has read lock, now attempting to upgrade to write...");
                Thread.sleep(50);
                // If it's the only reader, this should succeed
                lock.writeLock();
                System.out.println("UP: upgrade to write succeeded");
                // release both
                lock.writeUnlock();
                System.out.println("UP: released write (after upgrade)");
                lock.readUnlock();
                System.out.println("UP: released read");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "UP");

        // Start the threads
        reader1.start();
        reader2.start();
        writer1.start();
        reader3.start();
        writerReentrant.start();
        upgrader.start();

        // Join
        reader1.join();
        reader2.join();
        writer1.join();
        reader3.join();
        writerReentrant.join();
        upgrader.join();

        System.out.println("[PASS] finalTest");
    }

    public static void main(String[] args) throws InterruptedException{
        System.out.println("Running ReadWriteLock tests...");

        testReadReentrancy();
        testReadUnlockWithoutLock();
        testWriteReentrancy();
        testReadersConcurrent();
        testWriterExclusive();
        testUpgradeReadToWriteAlone();
        testUpgradeReadToWriteBlocked();
        testIllegalWriteUnlock();
        testWriterNotStarved();
        test();

        System.out.println("Tests finished.");
    }
}
