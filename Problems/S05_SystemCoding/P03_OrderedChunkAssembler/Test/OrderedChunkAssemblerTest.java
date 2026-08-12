package Problems.S05_SystemCoding.P03_OrderedChunkAssembler.Test;

import Problems.S05_SystemCoding.P03_OrderedChunkAssembler.OrderedChunkAssembler;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Plain main()-based tests, matching this repo's style (no JUnit).
 *
 * Every test that can block runs its threads as daemons with a bounded join, so a deadlock or a
 * missing signal FAILS the suite instead of freezing it -- which is how the missing
 * takeCondition.signal() in finish() was found.
 */
public class OrderedChunkAssemblerTest {

    private static final long JOIN_TIMEOUT_MILLIS = 10_000;

    public static void main(String[] args) throws Exception {
        testOutOfOrderChunksDrainInOrder();
        testWindowBlocksFarAheadProducerAndReleasesOnSlide();
        testFinishDrainsBufferedChunksBeforeEndOfStream();
        testFinishWakesAParkedConsumer();
        testFailReleasesBlockedProducerAndConsumer();
        testFailStillDeliversTheValidPrefixFirst();
        testStaleSequenceIsDropped();
        testWindowOfOne();
        testInvalidArgumentsRejected();
        testConcurrentProducersDeliverInOrder();

        System.out.println("All OrderedChunkAssembler tests passed.");
    }

    /** Chunks arrive scrambled; the consumer must still see 0,1,2,... */
    private static void testOutOfOrderChunksDrainInOrder() throws Exception {
        OrderedChunkAssembler assembler = new OrderedChunkAssembler(8);
        for (int sequence : new int[] { 3, 0, 4, 1, 2 }) {
            assembler.put(sequence, chunk(sequence));
        }
        assembler.finish(4);

        assertEquals("five scrambled chunks drain in sequence order", "01234", drain(assembler));
    }

    /**
     * The window bound: a producer whose sequence sits past currentId + windowSize - 1 must park,
     * and must be released when the consumer advances currentId far enough.
     */
    private static void testWindowBlocksFarAheadProducerAndReleasesOnSlide() throws Exception {
        OrderedChunkAssembler assembler = new OrderedChunkAssembler(3); // window [currentId .. +2]

        Thread farAhead = daemon("put-5", () -> put(assembler, 5));
        sleep(150);
        assertTrue("sequence 5 must block while the window is [0..2]", farAhead.isAlive());

        for (int sequence = 0; sequence <= 2; sequence++) {
            assembler.put(sequence, chunk(sequence));
        }
        for (int i = 0; i < 3; i++) {
            assembler.take(); // currentId -> 3, window becomes [3..5]
        }
        joinOrFail(farAhead); // must now be admissible

        assembler.put(3, chunk(3));
        assembler.put(4, chunk(4));
        assembler.finish(5);

        assertEquals("everything drains once the window has slid", "345", drain(assembler));
    }

    /** finish() must not truncate: chunks already buffered are delivered before end-of-stream. */
    private static void testFinishDrainsBufferedChunksBeforeEndOfStream() throws Exception {
        OrderedChunkAssembler assembler = new OrderedChunkAssembler(8);
        assembler.put(0, chunk(0));
        assembler.put(1, chunk(1));
        assembler.finish(1); // declared while both chunks are still sitting in the buffer

        assertEquals("buffered chunks survive finish()", "01", drain(assembler));
    }

    /**
     * Regression. A consumer already parked in take() is waiting on a predicate that finish()
     * changes -- so finish() must signal, or it parks forever. This was a race: it passed the small
     * tests and deadlocked the concurrent one.
     */
    private static void testFinishWakesAParkedConsumer() throws Exception {
        OrderedChunkAssembler assembler = new OrderedChunkAssembler(8);
        assembler.put(0, chunk(0));
        assembler.take(); // currentId -> 1, buffer empty

        byte[][] result = new byte[1][];
        Thread consumer = daemon("parked-consumer", () -> {
            try {
                result[0] = assembler.take(); // parks: chunk 1 has not arrived
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });

        sleep(200); // make sure it is genuinely parked, not merely slow
        assembler.finish(0); // the stream actually ended at 0
        joinOrFail(consumer);

        assertTrue("a parked consumer must be released by finish()", result[0] == null);
    }

    /** A permanently unfetchable chunk must not leave the pipeline parked on a gap forever. */
    private static void testFailReleasesBlockedProducerAndConsumer() throws Exception {
        OrderedChunkAssembler assembler = new OrderedChunkAssembler(2); // window [0..1]

        Thread producer = daemon("blocked-producer", () -> put(assembler, 9)); // outside the window
        boolean[] consumerThrew = { false };
        Thread consumer = daemon("blocked-consumer", () -> {
            try {
                assembler.take(); // parks: chunk 0 has not arrived
            } catch (IllegalStateException expected) {
                consumerThrew[0] = true;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });

        sleep(200);
        assembler.fail(new RuntimeException("chunk 0 unfetchable after retries"));

        joinOrFail(producer);
        joinOrFail(consumer);
        assertTrue("the consumer must see the failure, not hang on the gap", consumerThrew[0]);
    }

    /**
     * Drain-before-report: the consumer receives the longest valid contiguous prefix and only then
     * learns the stream broke. Failing fast instead would discard good chunks AND lose the
     * information about where the break actually is.
     */
    private static void testFailStillDeliversTheValidPrefixFirst() throws Exception {
        OrderedChunkAssembler assembler = new OrderedChunkAssembler(8);
        assembler.put(0, chunk(0));
        assembler.put(1, chunk(1));
        assembler.put(2, chunk(2));
        assembler.fail(new RuntimeException("chunk 3 unfetchable"));

        StringBuilder delivered = new StringBuilder();
        boolean threw = false;
        try {
            byte[] c;
            while ((c = assembler.take()) != null) {
                delivered.append((char) c[0]);
            }
        } catch (IllegalStateException expected) {
            threw = true;
        }

        assertEquals("the valid prefix is delivered before the failure surfaces", "012",
                delivered.toString());
        assertTrue("and then the failure surfaces at the gap", threw);
    }

    /** A retried fetch redelivering an already-consumed sequence must not be buffered forever. */
    private static void testStaleSequenceIsDropped() throws Exception {
        OrderedChunkAssembler assembler = new OrderedChunkAssembler(4);
        assembler.put(0, chunk(0));
        assembler.take(); // currentId -> 1

        assembler.put(0, chunk(9)); // stale duplicate; must be dropped, not leaked
        assembler.put(1, chunk(1));
        assembler.finish(1);

        assertEquals("a stale sequence is dropped and does not corrupt the stream", "1",
                drain(assembler));
    }

    /** Degenerate window: every producer blocks until the consumer takes the previous chunk. */
    private static void testWindowOfOne() throws Exception {
        OrderedChunkAssembler assembler = new OrderedChunkAssembler(1);
        int count = 50;

        Thread producer = daemon("lockstep-producer", () -> {
            try {
                for (int sequence = 0; sequence < count; sequence++) {
                    assembler.put(sequence, chunk(sequence));
                }
                assembler.finish(count - 1);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });

        int received = 0;
        boolean ordered = true;
        byte[] c;
        while ((c = assembler.take()) != null) {
            if (c[0] != (byte) ('0' + (received % 10))) {
                ordered = false;
            }
            received++;
        }
        joinOrFail(producer);

        assertTrue("window of 1 still delivers everything in order", ordered);
        assertEquals("all chunks delivered", count, received);
    }

    private static void testInvalidArgumentsRejected() throws Exception {
        OrderedChunkAssembler assembler = new OrderedChunkAssembler(4);

        boolean nullRejected = false;
        try {
            assembler.put(0, null);
        } catch (IllegalArgumentException | NullPointerException expected) {
            nullRejected = true;
        }
        assertTrue("a null chunk must be rejected", nullRejected);

        boolean negativeRejected = false;
        try {
            assembler.put(-1, chunk(0));
        } catch (IllegalArgumentException expected) {
            negativeRejected = true;
        }
        assertTrue("a negative sequence must be rejected", negativeRejected);

        assembler.finish(0);
        boolean pastEndRejected = false;
        try {
            assembler.put(1, chunk(1));
        } catch (IllegalArgumentException expected) {
            pastEndRejected = true;
        }
        assertTrue("a sequence past the declared end must be rejected", pastEndRejected);
    }

    /**
     * The real test. Sequences are DISPATCHED in increasing order from a shared counter but
     * COMPLETE out of order, which is how a real fetch pool behaves.
     *
     * Note the dispatch discipline is load-bearing: hand each worker a shuffled list instead and
     * you deadlock, because a worker can block on a far-ahead sequence while still owing the one
     * the consumer needs. No window size fixes that -- see Solution.md.
     */
    private static void testConcurrentProducersDeliverInOrder() throws Exception {
        for (int trial = 0; trial < 5; trial++) {
            final int chunkCount = 1_000;
            final int workerCount = 8;
            OrderedChunkAssembler assembler = new OrderedChunkAssembler(32);
            final long[] nextToDispatch = { 0 };

            List<Thread> workers = new ArrayList<>();
            for (int w = 0; w < workerCount; w++) {
                workers.add(daemon("fetcher-" + w, () -> {
                    Random random = new Random();
                    try {
                        while (true) {
                            long sequence;
                            synchronized (nextToDispatch) {
                                sequence = nextToDispatch[0]++;
                            }
                            if (sequence >= chunkCount) {
                                return;
                            }
                            if (random.nextInt(4) == 0) {
                                Thread.sleep(1); // variable fetch latency -> out-of-order completion
                            }
                            assembler.put(sequence, chunk((int) sequence));
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }

            daemon("finisher", () -> {
                try {
                    for (Thread worker : workers) {
                        worker.join();
                    }
                    assembler.finish(chunkCount - 1);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });

            int received = 0;
            byte[] c;
            while ((c = assembler.take()) != null) {
                assertTrue("trial " + trial + ": chunk " + received + " out of order",
                        c[0] == (byte) ('0' + (received % 10)));
                received++;
            }
            assertEquals("trial " + trial + ": every chunk delivered", chunkCount, received);
        }
    }

    // ---------- harness ----------

    /** Chunks are single bytes '0'..'9' keyed off the sequence, so order is visible at a glance. */
    private static byte[] chunk(int sequence) {
        return new byte[] { (byte) ('0' + (sequence % 10)) };
    }

    private static String drain(OrderedChunkAssembler assembler) throws InterruptedException {
        StringBuilder out = new StringBuilder();
        byte[] c;
        while ((c = assembler.take()) != null) {
            out.append((char) c[0]);
        }
        return out.toString();
    }

    private static void put(OrderedChunkAssembler assembler, int sequence) {
        try {
            assembler.put(sequence, chunk(sequence));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IllegalStateException ignored) {
            // expected when the assembler was failed while this producer was parked
        }
    }

    private static Thread daemon(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Turns a missing signal into a failed assertion rather than a frozen suite. */
    private static void joinOrFail(Thread thread) {
        try {
            thread.join(JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            throw new AssertionError("FAILED: thread '" + thread.getName() + "' did not finish in "
                    + JOIN_TIMEOUT_MILLIS + "ms -- deadlock or missing signal");
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

    private static void assertEquals(String message, int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("FAILED: " + message + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void assertEquals(String message, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("FAILED: " + message + " (expected \"" + expected + "\", got \"" + actual + "\")");
        }
    }
}
