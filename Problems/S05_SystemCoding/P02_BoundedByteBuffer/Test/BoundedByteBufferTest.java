package Problems.S05_SystemCoding.P02_BoundedByteBuffer.Test;

import Problems.S05_SystemCoding.P02_BoundedByteBuffer.BoundedByteBuffer;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Plain main()-based tests, matching this repo's style (no JUnit).
 *
 * Every test that could block runs its threads as daemons with a bounded join, so a deadlock or a
 * lost wakeup fails the suite instead of freezing it.
 *
 * The tests that matter most are the ones with MISMATCHED write/read chunk sizes against a small
 * capacity -- equal-sized chunks never wrap in the way that exposes index/accounting bugs.
 */
public class BoundedByteBufferTest {

    private static final long JOIN_TIMEOUT_MILLIS = 10_000;

    public static void main(String[] args) {
        testSmallPayloadRoundTrip();
        testPayloadContainingZeroAndFFBytes();
        testLargePayloadThroughTinyBuffer();
        testWriteLongerThanCapacity();
        testCapacityOfOne();
        testEmptyPayload();
        testReadReturnsWhatIsAvailableRatherThanWaitingForLen();
        testZeroLengthReadReturnsZero();
        testCloseWakesBlockedReader();
        testDrainThenEndOfStreamThenWriteRejected();
        testInvalidArgumentsRejected();
        testRandomisedChunkSizes();
        testManyProducersAndConsumersLoseNoBytes();

        System.out.println("All BoundedByteBuffer tests passed.");
    }

    private static void testSmallPayloadRoundTrip() {
        byte[] payload = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes();
        byte[] received = pumpThrough(8, payload, 5, 6);

        assertBytesEqual("26 bytes through a capacity-8 buffer, write 5 / read 6", payload, received);
    }

    /**
     * Regression: an earlier version used buf[i] != 0 to mean "occupied", so a legitimate 0x00 byte
     * looked like empty space and the reader parked forever. All 256 byte values are valid data.
     */
    private static void testPayloadContainingZeroAndFFBytes() {
        byte[] payload = { 1, 2, 0, 3, 0, 0, (byte) 0xFF, (byte) 0xFF, 0, 7 };
        byte[] received = pumpThrough(4, payload, 3, 5);

        assertBytesEqual("0x00 and 0xFF must flow through untouched", payload, received);
    }

    /**
     * The important one. 512 KB through 64 bytes wraps thousands of times and blocks constantly,
     * which is what catches wrap-around and accounting mistakes.
     */
    private static void testLargePayloadThroughTinyBuffer() {
        byte[] payload = randomPayload(512 * 1024, 11L);
        byte[] received = pumpThrough(64, payload, 999, 337);

        assertBytesEqual("512 KB through a 64-byte buffer", payload, received);
    }

    /**
     * len > capacity can never fit in one go. write() must deposit what fits, wait, and continue --
     * waiting for the whole len to fit first would hang forever.
     */
    private static void testWriteLongerThanCapacity() {
        byte[] payload = randomPayload(100, 12L);
        byte[] received = pumpThrough(4, payload, 100, 7);

        assertBytesEqual("a single 100-byte write into a capacity-4 buffer", payload, received);
    }

    private static void testCapacityOfOne() {
        byte[] payload = randomPayload(2_000, 13L);
        byte[] received = pumpThrough(1, payload, 64, 64);

        assertBytesEqual("degenerate ring of one byte", payload, received);
    }

    private static void testEmptyPayload() {
        byte[] received = pumpThrough(8, new byte[0], 4, 4);

        assertEquals("an empty payload yields nothing and must not hang", 0, received.length);
    }

    /** read() is best-effort: it returns what is there rather than blocking for the full len. */
    private static void testReadReturnsWhatIsAvailableRatherThanWaitingForLen() {
        BoundedByteBuffer buffer = new BoundedByteBuffer(64);
        byte[] destination = new byte[1024];

        Thread reader = daemon("reader", () -> {
            try {
                buffer.write("0123456789".getBytes(), 0, 10);
                int read = buffer.read(destination, 0, destination.length);
                assertEquals("asked for 1024 with 10 buffered -> must return 10", 10, read);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        joinOrFail(reader);
    }

    private static void testZeroLengthReadReturnsZero() {
        BoundedByteBuffer buffer = new BoundedByteBuffer(8);
        Thread caller = daemon("zero-len", () -> {
            try {
                assertEquals("read(len=0) returns 0 without blocking", 0, buffer.read(new byte[4], 0, 0));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        joinOrFail(caller);
    }

    /** A reader parked on an empty buffer must be released by close(), not stranded. */
    private static void testCloseWakesBlockedReader() {
        BoundedByteBuffer buffer = new BoundedByteBuffer(8);
        int[] result = { Integer.MIN_VALUE };

        Thread reader = daemon("blocked-reader", () -> {
            try {
                result[0] = buffer.read(new byte[4], 0, 4);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });

        sleep(200); // let the reader actually park
        buffer.close();
        joinOrFail(reader);

        assertEquals("a reader released by close() on an empty buffer sees end of stream", -1, result[0]);
    }

    /** After close(), remaining bytes are still readable; only once drained does read() return -1. */
    private static void testDrainThenEndOfStreamThenWriteRejected() {
        BoundedByteBuffer buffer = new BoundedByteBuffer(8);
        Thread caller = daemon("drain", () -> {
            try {
                buffer.write(new byte[] { 7, 7 }, 0, 2);
                buffer.close();

                byte[] out = new byte[8];
                assertEquals("buffered bytes survive close()", 2, buffer.read(out, 0, 8));
                assertEquals("then end of stream", -1, buffer.read(out, 0, 8));

                boolean rejected = false;
                try {
                    buffer.write(new byte[] { 1 }, 0, 1);
                } catch (IllegalStateException expected) {
                    rejected = true;
                }
                assertTrue("write() after close() must be rejected", rejected);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        joinOrFail(caller);
    }

    private static void testInvalidArgumentsRejected() {
        boolean badCapacity = false;
        try {
            new BoundedByteBuffer(0);
        } catch (IllegalArgumentException expected) {
            badCapacity = true;
        }
        assertTrue("capacity 0 must be rejected", badCapacity);

        BoundedByteBuffer buffer = new BoundedByteBuffer(8);
        byte[] small = new byte[4];

        assertTrue("negative offset must be rejected", throwsOnWrite(buffer, small, -1, 2));
        assertTrue("negative len must be rejected", throwsOnWrite(buffer, small, 0, -1));
        assertTrue("offset+len past the caller's array must be rejected", throwsOnWrite(buffer, small, 2, 3));
        assertTrue("null array must be rejected", throwsOnWrite(buffer, null, 0, 1));
    }

    /**
     * Randomised capacities and chunk sizes. Seeded, so a failure is reproducible.
     * This is the test most likely to catch a wrap-around regression.
     */
    private static void testRandomisedChunkSizes() {
        Random random = new Random(20260808L);
        for (int trial = 0; trial < 25; trial++) {
            byte[] payload = randomPayload(random.nextInt(60_000) + 1, random.nextLong());
            int capacity = random.nextInt(200) + 1;
            int writeChunk = random.nextInt(5_000) + 1;
            int readChunk = random.nextInt(5_000) + 1;

            byte[] received = pumpThrough(capacity, payload, writeChunk, readChunk);

            assertBytesEqual("trial " + trial + ": payload=" + payload.length + " capacity=" + capacity
                    + " writeChunk=" + writeChunk + " readChunk=" + readChunk, payload, received);
        }
    }

    /**
     * Several producers and several consumers. Byte ORDER is not defined across multiple consumers,
     * so this asserts the multiset instead: every byte written comes out exactly once, none lost,
     * none duplicated. Also the case where a notify()-instead-of-notifyAll() regression would show.
     */
    private static void testManyProducersAndConsumersLoseNoBytes() {
        int producerCount = 4;
        int consumerCount = 4;
        int bytesPerProducer = 20_000;

        BoundedByteBuffer buffer = new BoundedByteBuffer(97); // deliberately not a power of two
        long[] writtenHistogram = new long[256];
        long[] readHistogram = new long[256];
        List<Thread> threads = new ArrayList<>();

        for (int p = 0; p < producerCount; p++) {
            byte[] payload = randomPayload(bytesPerProducer, 500L + p);
            for (byte value : payload) {
                writtenHistogram[value & 0xFF]++;
            }
            threads.add(daemon("producer-" + p, () -> {
                try {
                    int pos = 0;
                    while (pos < payload.length) {
                        int n = Math.min(701, payload.length - pos); // odd size -> lots of wrapping
                        buffer.write(payload, pos, n);
                        pos += n;
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        for (int c = 0; c < consumerCount; c++) {
            threads.add(daemon("consumer-" + c, () -> {
                try {
                    byte[] out = new byte[311]; // also odd, and != the write chunk
                    int n;
                    while ((n = buffer.read(out, 0, out.length)) != -1) {
                        synchronized (readHistogram) {
                            for (int i = 0; i < n; i++) {
                                readHistogram[out[i] & 0xFF]++;
                            }
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // producers first in the list -- wait for them, then close so consumers can finish
        for (int i = 0; i < producerCount; i++) {
            joinOrFail(threads.get(i));
        }
        buffer.close();
        for (int i = producerCount; i < threads.size(); i++) {
            joinOrFail(threads.get(i));
        }

        assertTrue("every byte written must be read exactly once, none lost or duplicated",
                Arrays.equals(writtenHistogram, readHistogram));
        assertEquals("buffer must be empty at the end", 0, buffer.size());
    }

    // ---------- harness ----------

    /**
     * Runs one producer and one consumer against a fresh buffer; returns everything the consumer
     * drained. writeChunk and readChunk deliberately differ so writes and reads never line up.
     */
    private static byte[] pumpThrough(int capacity, byte[] payload, int writeChunk, int readChunk) {
        BoundedByteBuffer buffer = new BoundedByteBuffer(capacity);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        Thread producer = daemon("producer", () -> {
            try {
                int pos = 0;
                while (pos < payload.length) {
                    int n = Math.min(writeChunk, payload.length - pos);
                    buffer.write(payload, pos, n);
                    pos += n;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                buffer.close();
            }
        });

        Thread consumer = daemon("consumer", () -> {
            try {
                byte[] out = new byte[readChunk];
                int n;
                while ((n = buffer.read(out, 0, out.length)) != -1) {
                    sink.write(out, 0, n);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });

        joinOrFail(producer);
        joinOrFail(consumer);
        return sink.toByteArray();
    }

    private static Thread daemon(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Turns a deadlock into a failed assertion rather than a frozen suite. */
    private static void joinOrFail(Thread thread) {
        try {
            thread.join(JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            throw new AssertionError("FAILED: thread '" + thread.getName() + "' did not finish in "
                    + JOIN_TIMEOUT_MILLIS + "ms -- deadlock or lost wakeup");
        }
    }

    private static boolean throwsOnWrite(BoundedByteBuffer buffer, byte[] array, int offset, int len) {
        try {
            buffer.write(array, offset, len);
            return false;
        } catch (NullPointerException | IndexOutOfBoundsException expected) {
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static byte[] randomPayload(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
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

    private static void assertBytesEqual(String message, byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            int firstDiff = -1;
            for (int i = 0; i < Math.min(expected.length, actual.length); i++) {
                if (expected[i] != actual[i]) {
                    firstDiff = i;
                    break;
                }
            }
            throw new AssertionError("FAILED: " + message
                    + " (expected " + expected.length + " bytes, got " + actual.length
                    + "; first difference at index " + firstDiff + ")");
        }
    }
}
