package Problems.S05_SystemCoding.P04_RangeLock.Test;

import Problems.S05_SystemCoding.P04_RangeLock.RangeLock;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Plain main()-based tests, matching this repo's style (no JUnit).
 *
 * Ranges are CLOSED: [start, end] includes both endpoints.
 *
 * Two things this suite does deliberately:
 *
 *  - Every acquire that might block runs on its own daemon thread, so a starvation bug or a missing
 *    signal FAILS with a message instead of freezing the suite.
 *  - The stress test asserts the actual invariant -- that no two conflicting ranges are ever held at
 *    the same time -- rather than merely that nothing deadlocked. A lock that granted everything
 *    would sail through a deadlock-only test.
 */
public class RangeLockTest {

    private static final boolean WRITE = true;
    private static final boolean READ = false;

    private static final long GRANT_TIMEOUT_MILLIS = 5_000;
    private static final long BLOCK_OBSERVE_MILLIS = 200;

    /** volatile so the churning readers actually observe the stop request. */
    private static volatile boolean stopChurning = false;

    public static void main(String[] args) throws Exception {
        testDisjointRangesDoNotBlock();
        testAdjacentRangesDoNotBlock();
        testRangesSharingOneByteConflict();
        testOverlappingReadWaitsForWriter();
        testOverlappingWriteWaitsForReader();
        testSharedReadersRunConcurrently();
        testIdenticalSharedRangesReleaseIndependently();
        testSingleByteRangeInsideAWriteBlocks();
        testWaiterOverlappingSeveralHeldRanges();
        testWriterNotStarvedByContinuousReaders();
        testCloseIsIdempotent();
        testInvalidArgumentsRejected();
        testStressNoConflictingPairEverCoHeld();

        System.out.println("All RangeLock tests passed.");
    }

    private static void testDisjointRangesDoNotBlock() {
        RangeLock lock = new RangeLock();
        Acquirer first = acquire(lock, 0, 99, WRITE);
        first.awaitGranted("a writer on an empty lock table");

        Acquirer second = acquire(lock, 200, 299, WRITE);
        second.awaitGranted("a disjoint writer must not wait");
    }

    /** [0,99] and [100,199] are adjacent: byte 99 and byte 100 are different bytes. */
    private static void testAdjacentRangesDoNotBlock() {
        RangeLock lock = new RangeLock();
        acquire(lock, 0, 99, WRITE).awaitGranted("first writer");

        Acquirer adjacent = acquire(lock, 100, 199, READ);
        adjacent.awaitGranted("[100,199] is adjacent to [0,99] and shares no byte");
    }

    /** The one-byte case, on the other side of the boundary from the test above. */
    private static void testRangesSharingOneByteConflict() {
        RangeLock lock = new RangeLock();
        Acquirer writer = acquire(lock, 0, 99, WRITE);
        writer.awaitGranted("first writer");

        Acquirer touching = acquire(lock, 99, 150, READ);
        assertTrue("[99,150] shares byte 99 with [0,99] and must wait", touching.stillBlocked());

        writer.release();
        touching.awaitGranted("releasing the writer admits it");
    }

    private static void testOverlappingReadWaitsForWriter() {
        RangeLock lock = new RangeLock();
        Acquirer writer = acquire(lock, 0, 99, WRITE);
        writer.awaitGranted("writer");

        Acquirer reader = acquire(lock, 50, 150, READ);
        assertTrue("an overlapping read must wait for the writer", reader.stillBlocked());

        writer.release();
        reader.awaitGranted("the reader is admitted once the writer releases");
    }

    private static void testOverlappingWriteWaitsForReader() {
        RangeLock lock = new RangeLock();
        Acquirer reader = acquire(lock, 0, 99, READ);
        reader.awaitGranted("reader");

        Acquirer writer = acquire(lock, 50, 150, WRITE);
        assertTrue("an overlapping write must wait for the reader", writer.stillBlocked());

        reader.release();
        writer.awaitGranted("the writer is admitted once the reader releases");
    }

    /** Shared means shared: N readers hold the same bytes simultaneously, they do not queue. */
    private static void testSharedReadersRunConcurrently() {
        RangeLock lock = new RangeLock();
        List<Acquirer> readers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            readers.add(acquire(lock, 0, 999, READ));
        }
        for (Acquirer reader : readers) {
            reader.awaitGranted("8 readers must all hold [0,999] at once");
        }

        Acquirer writer = acquire(lock, 500, 600, WRITE);
        assertTrue("a writer waits while any reader still holds an overlapping range",
                writer.stillBlocked());

        for (Acquirer reader : readers) {
            reader.release();
        }
        writer.awaitGranted("the writer is admitted once every reader has released");
    }

    /**
     * Regression. Two threads may hold the SAME range in shared mode; those are two independent
     * grants. Storing them in a Set with value-based equals collapsed them into one element, so one
     * close() freed both and a writer was admitted while a reader was still holding.
     */
    private static void testIdenticalSharedRangesReleaseIndependently() {
        RangeLock lock = new RangeLock();
        Acquirer readerA = acquire(lock, 0, 10, READ);
        Acquirer readerB = acquire(lock, 0, 10, READ);
        readerA.awaitGranted("reader A");
        readerB.awaitGranted("reader B on the identical range");

        readerA.release();

        Acquirer writer = acquire(lock, 0, 10, WRITE);
        assertTrue("releasing ONE of two identical shared grants must not admit a writer",
                writer.stillBlocked());

        readerB.release();
        writer.awaitGranted("releasing the second grant admits the writer");
    }

    /** With closed ranges [500,500] is one byte, not an empty range. */
    private static void testSingleByteRangeInsideAWriteBlocks() {
        RangeLock lock = new RangeLock();
        acquire(lock, 0, 1_000_000, WRITE).awaitGranted("a write over the whole file");

        Acquirer oneByte = acquire(lock, 500, 500, READ);
        assertTrue("[500,500] is a single byte inside the write and must wait",
                oneByte.stillBlocked());
    }

    /** A request that straddles several held ranges must wait for all of them. */
    private static void testWaiterOverlappingSeveralHeldRanges() {
        RangeLock lock = new RangeLock();
        Acquirer first = acquire(lock, 0, 99, WRITE);
        Acquirer second = acquire(lock, 200, 299, WRITE);
        first.awaitGranted("first writer");
        second.awaitGranted("second writer");

        Acquirer spanning = acquire(lock, 50, 250, READ);
        assertTrue("a range overlapping two held writes must wait", spanning.stillBlocked());

        first.release();
        assertTrue("releasing only the first is not enough", spanning.stillBlocked());

        second.release();
        spanning.awaitGranted("releasing both admits the spanning reader");
    }

    /**
     * The liveness test. Readers overlapping each other are all admissible, so without extra state
     * a continuous stream of them means the writer's conflict check never comes up false and it
     * waits forever. This is correctness, not throughput.
     */
    private static void testWriterNotStarvedByContinuousReaders() {
        RangeLock lock = new RangeLock();
        stopChurning = false;
        List<Thread> readers = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            readers.add(daemon("churning-reader-" + i, () -> {
                try {
                    while (!stopChurning) {
                        try (RangeLock.Handle held = lock.acquire(0, 999, READ)) {
                            sleep(2);
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        sleep(200); // let the readers get into their cycle
        long startNanos = System.nanoTime();
        Acquirer writer = acquire(lock, 400, 499, WRITE);
        boolean granted = writer.grantedWithin(3_000);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        stopChurning = true;
        if (granted) {
            writer.release(); // readers parked behind the writer cannot see stopChurning until it lets go
        }
        for (Thread reader : readers) {
            joinOrFail(reader);
        }

        assertTrue("a writer must not be starved by continuously re-acquiring readers"
                + " (waited " + elapsedMillis + "ms)", granted);
    }

    private static void testCloseIsIdempotent() {
        RangeLock lock = new RangeLock();
        Acquirer reader = acquire(lock, 0, 99, READ);
        reader.awaitGranted("reader");

        reader.release();
        reader.release(); // second close must be a no-op, not a second removal

        Acquirer writer = acquire(lock, 0, 99, WRITE);
        writer.awaitGranted("the range is free after a double close, and not corrupted");
    }

    private static void testInvalidArgumentsRejected() {
        assertTrue("a negative start must be rejected", rejects(-1, 10));
        assertTrue("end < start is malformed and must be rejected", rejects(10, 5));
    }

    /**
     * The one that finds real bugs. Every holder records its range on entry and asserts that no
     * CONFLICTING range is already recorded -- checking the actual mutual-exclusion invariant, not
     * merely that nothing hung.
     */
    private static void testStressNoConflictingPairEverCoHeld() {
        final RangeLock lock = new RangeLock();
        final List<long[]> held = new ArrayList<>(); // {start, end, exclusive}
        final boolean[] violation = { false };

        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 16; t++) {
            final int seed = t;
            threads.add(daemon("worker-" + t, () -> {
                Random random = new Random(seed);
                for (int i = 0; i < 400; i++) {
                    long start = random.nextInt(500);
                    long end = start + random.nextInt(80);
                    boolean exclusive = random.nextInt(3) == 0;
                    try (RangeLock.Handle handle = lock.acquire(start, end, exclusive)) {
                        long[] mine = { start, end, exclusive ? 1 : 0 };
                        synchronized (held) {
                            for (long[] other : held) {
                                boolean overlaps = start <= other[1] && other[0] <= end; // closed
                                if (overlaps && (exclusive || other[2] == 1)) {
                                    violation[0] = true;
                                }
                            }
                            held.add(mine);
                        }
                        Thread.sleep(0, 50_000);
                        synchronized (held) {
                            held.remove(mine); // identity removal: long[] has no value equals
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }));
        }

        for (Thread thread : threads) {
            joinOrFail(thread);
        }
        assertTrue("no two conflicting ranges may ever be held at the same time", !violation[0]);
        synchronized (held) {
            assertTrue("every grant was released", held.isEmpty());
        }
    }

    // ---------- harness ----------

    /** An acquire running on its own thread, so a test can observe whether it blocked. */
    private static final class Acquirer {
        private final String name;
        private final Thread thread;
        private volatile RangeLock.Handle handle;
        private volatile Throwable error;

        Acquirer(RangeLock lock, long start, long end, boolean exclusive, String name) {
            this.name = name;
            this.thread = daemon(name, () -> {
                try {
                    handle = lock.acquire(start, end, exclusive);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    error = t;
                }
            });
        }

        /** Waits briefly and reports whether the acquire is still outstanding. */
        boolean stillBlocked() {
            sleep(BLOCK_OBSERVE_MILLIS);
            rethrowIfFailed();
            return handle == null;
        }

        boolean grantedWithin(long millis) {
            long deadline = System.nanoTime() + millis * 1_000_000L;
            while (handle == null && System.nanoTime() < deadline) {
                sleep(5);
            }
            rethrowIfFailed();
            return handle != null;
        }

        void awaitGranted(String what) {
            if (!grantedWithin(GRANT_TIMEOUT_MILLIS)) {
                throw new AssertionError("FAILED: " + what + " -- " + name + " never acquired"
                        + " (blocked for " + GRANT_TIMEOUT_MILLIS + "ms)");
            }
        }

        void release() {
            if (handle == null) {
                throw new AssertionError("FAILED: " + name + " has no grant to release");
            }
            handle.close();
        }

        private void rethrowIfFailed() {
            if (error != null) {
                throw new AssertionError("FAILED: " + name + " threw " + error);
            }
        }
    }

    private static Acquirer acquire(RangeLock lock, long start, long end, boolean exclusive) {
        return new Acquirer(lock, start, end, exclusive,
                (exclusive ? "W" : "R") + "[" + start + "," + end + "]");
    }

    private static boolean rejects(long start, long end) {
        try {
            new RangeLock().acquire(start, end, WRITE);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Thread daemon(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void joinOrFail(Thread thread) {
        try {
            thread.join(30_000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            throw new AssertionError("FAILED: thread '" + thread.getName()
                    + "' did not finish -- deadlock, starvation, or a missing signal");
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void assertTrue(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError("FAILED: " + message);
        }
    }
}
