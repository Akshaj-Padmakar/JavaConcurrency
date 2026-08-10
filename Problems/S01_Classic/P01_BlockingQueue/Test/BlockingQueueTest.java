package Problems.S01_Classic.P01_BlockingQueue.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import Problems.S01_Classic.P01_BlockingQueue.BlockingQueue;

//AI-TEST.
public class BlockingQueueTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== BlockingQueue tests start ===");

        // behaviour
        testAddAndOfferBehavior();
        testPutBlocksUntilSpace();
        testOfferWithTimeout();
        testPollAndPollTimeout();
        testTakeBlocksAndInterrupt();
        testMultiProducerConsumer();

        // contract
        testConstructorValidation();
        testNullRejection();
        testPeekAndRemainingCapacity();

        // timing semantics
        testExpiredDeadlineStillInsertsWhenNotFull();
        testTimedCallsHonourTheirBudget();

        // signalling / blocking invariants
        testAddSignalsBlockedTake();
        testOfferSignalsBlockedTake();
        testOneSlotWakesExactlyOneProducer();
        testPutIsInterruptible();

        // stress invariants
        testTakeNeverReturnsNullUnderContention();
        testCapacityAndFifoUnderStress();

        System.out.println("=== All tests passed ===");
    }

    /* ================= helpers ================= */

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond)
            throw new AssertionError("Assertion failed: " + msg);
    }

    // called as assertEquals(expected, actual, msg)
    private static void assertEquals(Object expected, Object actual, String msg) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Assertion failed: " + msg + " — expected: " + expected + ", actual: " + actual);
        }
    }

    private static void assertThrows(Class<? extends Throwable> expected, ThrowingRunnable body, String msg) {
        try {
            body.run();
        } catch (Throwable t) {
            if (expected.isInstance(t))
                return;
            throw new AssertionError("Assertion failed: " + msg + " — expected " + expected.getSimpleName()
                    + ", got " + t.getClass().getName() + "(\"" + t.getMessage() + "\")");
        }
        throw new AssertionError("Assertion failed: " + msg + " — expected " + expected.getSimpleName()
                + ", but nothing was thrown");
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    // Runs body on a daemon thread with a bounded join, so a deadlock or missing signal
    // fails with a message instead of freezing the suite.
    private static void withinBoundedTime(ThrowingRunnable body, long joinMs, String msg) throws Exception {
        final AtomicReference<Throwable> err = new AtomicReference<>();
        Thread t = daemon(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                err.set(e);
            }
        }, "bounded-" + msg);
        t.start();
        t.join(joinMs);
        assertTrue(!t.isAlive(), msg + " — still running after " + joinMs + "ms");
        if (err.get() instanceof AssertionError)
            throw (AssertionError) err.get();
        assertTrue(err.get() == null, msg + " — threw " + err.get());
    }

    private static void testAddAndOfferBehavior() {
        System.out.println("[testAddAndOfferBehavior]");

        BlockingQueue<Integer> q = new BlockingQueue<>(2, false);

        // add two items
        q.add(10);
        q.add(20);
        assertEquals(2, q.size(), "size after two adds");

        // add when full should throw IllegalStateException
        boolean addThrew = false;
        try {
            q.add(30);
        } catch (IllegalStateException ise) {
            addThrew = true;
        }
        assertTrue(addThrew, "add() should throw when full");

        // offer returns false when full
        boolean offerResult = q.offer(40);
        assertTrue(!offerResult, "offer() should return false when full");

        // remove one and then offer should succeed
        Integer polled = q.poll();
        assertEquals(10, polled, "poll returned first inserted item");
        offerResult = q.offer(50);
        assertTrue(offerResult, "offer should succeed when space available");
        assertEquals(2, q.size(), "size after poll + offer");

        System.out.println("  ok");
    }

    private static void testPutBlocksUntilSpace() throws Exception {
        System.out.println("[testPutBlocksUntilSpace]");

        final BlockingQueue<Integer> q = new BlockingQueue<>(2, false);
        q.add(1);
        q.add(2);

        CountDownLatch producerStarted = new CountDownLatch(1);
        CountDownLatch producerDone = new CountDownLatch(1);

        Thread producer = daemon(() -> {
            try {
                producerStarted.countDown();
                // This should block until a consumer removes an item
                q.put(3);
                producerDone.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "put-producer");

        producer.start();
        // ensure producer started and likely blocked
        producerStarted.await(1, TimeUnit.SECONDS);
        // small wait to let producer attempt to put
        Thread.sleep(150);

        // producer should not have completed yet (queue is full)
        assertTrue(producerDone.getCount() == 1, "producer should be blocked on put");

        // take one from queue to free space for producer
        Integer first = q.take();
        assertEquals(1, first, "take should return first element");

        // now producer should finish
        boolean finished = producerDone.await(1, TimeUnit.SECONDS);
        assertTrue(finished, "producer should finish after space freed");

        // queue should now contain remaining original and the produced element
        assertEquals(2, q.size(), "queue size after producer finished");
        Integer next = q.take();
        Integer last = q.take();
        // order should be 2 then 3
        assertEquals(2, next, "expected 2 after take");
        assertEquals(3, last, "expected 3 after take");

        producer.join(1000);
        System.out.println("  ok");
    }

    private static void testOfferWithTimeout() throws Exception {
        System.out.println("[testOfferWithTimeout]");

        final BlockingQueue<Integer> q = new BlockingQueue<>(1, false);
        q.add(99); // make it full

        // offer with short timeout should return false
        long t0 = System.nanoTime();
        boolean res = q.offer(100, 200, TimeUnit.MILLISECONDS);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(!res, "offer should timeout and return false when full");
        assertTrue(elapsed >= 150, "offer waited approx timeout (elapsed ms = " + elapsed + ")");

        // now test offer that will succeed when space freed by consumer
        CountDownLatch consumerReady = new CountDownLatch(1);
        CountDownLatch offerCompleted = new CountDownLatch(1);
        AtomicBoolean offerResult = new AtomicBoolean(false);

        Thread offeringThread = daemon(() -> {
            try {
                consumerReady.countDown();
                // This offer will wait for up to 2 seconds; main thread will remove element
                boolean r = q.offer(200, 2, TimeUnit.SECONDS);
                offerResult.set(r);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                offerCompleted.countDown();
            }
        }, "offer-with-timeout-thread");

        offeringThread.start();
        // wait until the offering thread is ready & likely waiting
        consumerReady.await(1, TimeUnit.SECONDS);
        Thread.sleep(200);

        // remove element to free space
        Integer val = q.poll();
        assertEquals(99, val, "polled value before offer completes");

        // the offering thread should succeed now
        boolean done = offerCompleted.await(1, TimeUnit.SECONDS);
        assertTrue(done, "offer thread should complete");
        assertTrue(offerResult.get(), "offer should succeed after space freed");

        // cleanup
        Integer got = q.poll();
        assertEquals(200, got, "queue should contain offered value");

        offeringThread.join(1000);
        System.out.println("  ok");
    }

    private static void testPollAndPollTimeout() throws Exception {
        System.out.println("[testPollAndPollTimeout]");

        BlockingQueue<String> q = new BlockingQueue<>(2, false);

        // empty queue poll returns null
        assertTrue(q.poll() == null, "poll on empty should return null");

        // poll with timeout should return null after timeout
        long start = System.nanoTime();
        String res = q.poll(200, TimeUnit.MILLISECONDS);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(res == null, "poll(timeout) on empty returns null");
        assertTrue(millis >= 150, "poll(timeout) waited approx timeout");

        // test blocking poll via take
        CountDownLatch consumerStarted = new CountDownLatch(1);
        CountDownLatch consumerDone = new CountDownLatch(1);
        AtomicInteger consumedValue = new AtomicInteger(-1);

        Thread consumer = daemon(() -> {
            try {
                consumerStarted.countDown();
                String v = q.take(); // should block until producer puts
                consumedValue.set(Integer.parseInt(v));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                consumerDone.countDown();
            }
        }, "take-consumer");

        consumer.start();
        consumerStarted.await(1, TimeUnit.SECONDS);
        Thread.sleep(150); // let consumer block

        // put an item to wake consumer
        q.put("42");

        boolean ok = consumerDone.await(1, TimeUnit.SECONDS);
        assertTrue(ok, "consumer should have been unblocked by put");
        assertEquals(42, consumedValue.get(), "consumer consumed correct value");

        consumer.join(1000);
        System.out.println("  ok");
    }

    private static void testTakeBlocksAndInterrupt() throws Exception {
        System.out.println("[testTakeBlocksAndInterrupt]");

        final BlockingQueue<Integer> q = new BlockingQueue<>(1, false);

        AtomicBoolean interruptedCaught = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        Thread t = daemon(() -> {
            try {
                started.countDown();
                q.take(); // will block
            } catch (InterruptedException e) {
                interruptedCaught.set(true);
                Thread.currentThread().interrupt();
            }
        }, "interruptible-taker");

        t.start();
        started.await(1, TimeUnit.SECONDS);
        Thread.sleep(150); // give it time to block on take

        // interrupt the blocking thread
        t.interrupt();
        t.join(1000);

        assertTrue(!t.isAlive(), "take() must unblock on interrupt, not hang");
        assertTrue(interruptedCaught.get(), "take() thread should detect InterruptedException");
        System.out.println("  ok");
    }

    private static void testMultiProducerConsumer() throws Exception {
        System.out.println("[testMultiProducerConsumer]");

        final int PRODUCERS = 3;
        final int CONSUMERS = 3;
        final int PER_PRODUCER = 1000;
        final BlockingQueue<Integer> q = new BlockingQueue<>(100, false);

        final ExecutorService ex = Executors.newFixedThreadPool(PRODUCERS + CONSUMERS);
        final CountDownLatch produced = new CountDownLatch(PRODUCERS);
        final CountDownLatch consumed = new CountDownLatch(CONSUMERS);

        final List<Integer> results = Collections.synchronizedList(new ArrayList<>());

        // producers
        for (int p = 0; p < PRODUCERS; p++) {
            final int base = p * PER_PRODUCER;
            ex.submit(() -> {
                try {
                    for (int i = 0; i < PER_PRODUCER; i++) {
                        q.put(base + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    produced.countDown();
                }
            });
        }

        // consumers
        final AtomicInteger totalConsumed = new AtomicInteger(0);
        for (int c = 0; c < CONSUMERS; c++) {
            ex.submit(() -> {
                try {
                    // each consumer will consume until all producers done AND queue empty
                    while (true) {
                        // try to take with timeout to eventually exit
                        Integer v = q.poll(500, TimeUnit.MILLISECONDS);
                        if (v != null) {
                            results.add(v);
                            totalConsumed.incrementAndGet();
                        } else {
                            // if producers finished and queue is empty -> exit
                            if (produced.getCount() == 0 && q.size() == 0)
                                break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    consumed.countDown();
                }
            });
        }

        // wait for producers to finish producing
        boolean prodOk = produced.await(60, TimeUnit.SECONDS);
        assertTrue(prodOk, "producers finished in time");

        // wait for consumers to finish consuming
        boolean consOk = consumed.await(60, TimeUnit.SECONDS);
        assertTrue(consOk, "consumers finished in time");

        // check total count
        int expected = PRODUCERS * PER_PRODUCER;
        assertEquals(expected, totalConsumed.get(), "all items produced were consumed");
        // optionally check for duplicates by counting unique values
        assertTrue(results.size() == expected, "results list size matches expected");

        ex.shutdownNow();

        System.out.println("  ok");
    }

    /* ================= contract ================= */

    private static void testConstructorValidation() {
        System.out.println("[testConstructorValidation]");

        // A non-positive capacity leaves isFull() permanently false, so the bound
        // silently does not exist. Must be rejected at construction.
        assertThrows(IllegalArgumentException.class, () -> new BlockingQueue<String>(0), "capacity 0");
        assertThrows(IllegalArgumentException.class, () -> new BlockingQueue<String>(-5), "capacity -5");
        assertThrows(IllegalArgumentException.class, () -> new BlockingQueue<String>(0, true), "capacity 0, fair");
        assertThrows(IllegalArgumentException.class, () -> new BlockingQueue<String>(Integer.MIN_VALUE),
                "capacity Integer.MIN_VALUE");

        System.out.println("  ok");
    }

    private static void testNullRejection() {
        System.out.println("[testNullRejection]");

        final BlockingQueue<String> q = new BlockingQueue<>(2);

        // Collection contract: null element -> NullPointerException, NOT IllegalArgumentException.
        // (IllegalArgumentException is for a *property* of an element preventing insertion.)
        assertThrows(NullPointerException.class, () -> q.add(null), "add(null)");
        assertThrows(NullPointerException.class, () -> q.offer(null), "offer(null)");
        assertThrows(NullPointerException.class, () -> q.put(null), "put(null)");
        assertThrows(NullPointerException.class, () -> q.offer(null, 1, TimeUnit.SECONDS), "offer(null, timeout)");

        assertEquals(0, q.size(), "no null leaked into the queue");

        // add() on a full queue is IllegalStateException (capacity is a state, not a bad argument)
        q.add("a");
        q.add("b");
        assertThrows(IllegalStateException.class, () -> q.add("c"), "add() when full");

        System.out.println("  ok");
    }

    private static void testPeekAndRemainingCapacity() {
        System.out.println("[testPeekAndRemainingCapacity]");

        BlockingQueue<Integer> q = new BlockingQueue<>(3);
        assertTrue(q.peek() == null, "peek on empty returns null");
        assertEquals(3, q.remainingCapacity(), "remainingCapacity when empty");

        q.add(1);
        q.add(2);
        assertEquals(1, q.peek(), "peek returns head");
        assertEquals(1, q.peek(), "peek is idempotent — must not remove");
        assertEquals(2, q.size(), "peek did not change size");
        assertEquals(1, q.remainingCapacity(), "remainingCapacity after two adds");

        q.poll();
        assertEquals(2, q.peek(), "peek reflects new head after poll");
        assertEquals(2, q.remainingCapacity(), "remainingCapacity after poll");

        System.out.println("  ok");
    }

    /* ================= timing semantics ================= */

    // The timeout bounds how long we wait FOR SPACE, not how long the call takes.
    // If the deadline is already gone but the queue is not full, there was nothing
    // to wait for — insert and report success. Never fail an operation you can complete.
    private static void testExpiredDeadlineStillInsertsWhenNotFull() throws Exception {
        System.out.println("[testExpiredDeadlineStillInsertsWhenNotFull]");

        BlockingQueue<String> q = new BlockingQueue<>(2);
        assertTrue(q.offer("a", 0, TimeUnit.MILLISECONDS), "offer(0 timeout) must insert when not full");
        assertTrue(q.offer("b", -5, TimeUnit.SECONDS), "offer(negative timeout) must insert when not full");
        assertEquals(2, q.size(), "both items inserted despite expired deadlines");

        // ...but a genuinely full queue must decline immediately
        long t0 = System.nanoTime();
        assertTrue(!q.offer("c", 0, TimeUnit.MILLISECONDS), "offer(0 timeout) on full queue returns false");
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(ms < 100, "offer(0 timeout) on full queue must not block (took " + ms + "ms)");

        // symmetric for the removal side
        assertEquals("a", q.poll(0, TimeUnit.MILLISECONDS), "poll(0 timeout) returns head when non-empty");
        BlockingQueue<String> empty = new BlockingQueue<>(1);
        t0 = System.nanoTime();
        assertTrue(empty.poll(0, TimeUnit.MILLISECONDS) == null, "poll(0 timeout) on empty returns null");
        ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(ms < 100, "poll(0 timeout) on empty must not block (took " + ms + "ms)");

        System.out.println("  ok");
    }

    // A timed call must return inside its own budget, and consecutive timed calls must each
    // get a fresh, independent budget.
    //
    // LIMIT OF THIS TEST: it does NOT cover "a waiter is woken repeatedly and recomputes its
    // remaining time". That path is not reachable from outside the class — every wakeup is
    // caused by a slot genuinely opening, and a correct timed call is then *supposed* to take
    // it and succeed. Attempts to starve the waiter with competing drainers/bargers produced
    // 12/12 immediate successes (0-13ms), i.e. the waiter never looped. That property is
    // therefore verified by reading, not by this suite.
    private static void testTimedCallsHonourTheirBudget() throws Exception {
        System.out.println("[testTimedCallsHonourTheirBudget]");

        // bounded join: if a timed call never returns, this fails with a message
        // instead of freezing the suite on the main thread.
        withinBoundedTime(() -> {
            final BlockingQueue<Integer> full = new BlockingQueue<>(1);
            full.add(0);

            long t0 = System.nanoTime();
            assertTrue(!full.offer(1, 200, TimeUnit.MILLISECONDS), "offer on a permanently full queue times out");
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue(ms >= 150, "offer(200ms) returned too early: " + ms + "ms");
            assertTrue(ms < 800, "offer(200ms) overran its budget: " + ms + "ms");

            final BlockingQueue<Integer> empty = new BlockingQueue<>(1);
            t0 = System.nanoTime();
            assertTrue(empty.poll(200, TimeUnit.MILLISECONDS) == null, "poll on a permanently empty queue times out");
            ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue(ms >= 150, "poll(200ms) returned too early: " + ms + "ms");
            assertTrue(ms < 800, "poll(200ms) overran its budget: " + ms + "ms");

            // three consecutive timed calls, each entitled to its own 100ms
            t0 = System.nanoTime();
            for (int i = 0; i < 3; i++)
                assertTrue(!full.offer(9, 100, TimeUnit.MILLISECONDS), "each timed offer times out independently");
            ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue(ms >= 250, "three 100ms offers returned too fast: " + ms + "ms");
            assertTrue(ms < 1200, "three 100ms offers overran their budgets: " + ms + "ms");
        }, 8000, "timed calls must return within their budget");

        System.out.println("  ok");
    }

    /* ================= signalling / blocking invariants ================= */

    // REGRESSION (2026-08-11, 20-min cold write): add() inserted via queue.add() directly
    // instead of the addItem() helper, so it never signalled emptyCondition. A consumer
    // already parked in take() was never woken — the item sat in the queue while the
    // consumer waited forever. Presents as an intermittent stall, since any later
    // put()/offer() heals it. Lesson: once a helper owns the signalling, no path may
    // touch the backing collection directly.
    private static void testAddSignalsBlockedTake() throws Exception {
        System.out.println("[testAddSignalsBlockedTake]");

        final BlockingQueue<String> q = new BlockingQueue<>(4);
        final AtomicReference<String> got = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);

        Thread consumer = daemon(() -> {
            try {
                got.set(q.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        }, "add-signal-consumer");
        consumer.start();

        Thread.sleep(200); // let the consumer park in await()
        assertEquals(1L, done.getCount(), "consumer must still be parked before add()");

        q.add("hello");

        assertTrue(done.await(2, TimeUnit.SECONDS),
                "add() must signal a consumer blocked in take() — lost wakeup");
        assertEquals("hello", got.get(), "consumer received the item added by add()");

        consumer.join(1000);
        System.out.println("  ok");
    }

    // Same wakeup obligation for the non-blocking insert path.
    private static void testOfferSignalsBlockedTake() throws Exception {
        System.out.println("[testOfferSignalsBlockedTake]");

        final BlockingQueue<String> q = new BlockingQueue<>(4);
        final AtomicReference<String> got = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);

        Thread consumer = daemon(() -> {
            try {
                got.set(q.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        }, "offer-signal-consumer");
        consumer.start();

        Thread.sleep(200);
        assertEquals(1L, done.getCount(), "consumer must still be parked before offer()");

        assertTrue(q.offer("world"), "offer succeeds on a non-full queue");

        assertTrue(done.await(2, TimeUnit.SECONDS),
                "offer() must signal a consumer blocked in take() — lost wakeup");
        assertEquals("world", got.get(), "consumer received the offered item");

        consumer.join(1000);
        System.out.println("  ok");
    }

    // Freeing exactly one slot must release exactly one blocked producer.
    // Catches both a missing signal (zero proceed) and a capacity breach (two proceed).
    private static void testOneSlotWakesExactlyOneProducer() throws Exception {
        System.out.println("[testOneSlotWakesExactlyOneProducer]");

        final int N = 5;
        final BlockingQueue<Integer> q = new BlockingQueue<>(1);
        q.add(0); // full

        final AtomicInteger completed = new AtomicInteger(0);
        final CountDownLatch started = new CountDownLatch(N);
        final List<Thread> producers = new ArrayList<>();

        for (int i = 0; i < N; i++) {
            final int id = i + 1;
            Thread t = daemon(() -> {
                started.countDown();
                try {
                    q.put(id);
                    completed.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "blocked-producer-" + id);
            producers.add(t);
            t.start();
        }

        assertTrue(started.await(2, TimeUnit.SECONDS), "all producers started");
        Thread.sleep(250); // let them all park on fullCondition
        assertEquals(0, completed.get(), "no producer may proceed while the queue is full");
        assertEquals(1, q.size(), "queue still holds exactly its capacity");

        q.poll(); // free exactly one slot

        Thread.sleep(400);
        assertEquals(1, completed.get(), "exactly one producer proceeds per freed slot");
        assertEquals(1, q.size(), "capacity respected after the hand-off");

        for (Thread t : producers)
            t.interrupt();
        for (Thread t : producers)
            t.join(500);

        System.out.println("  ok");
    }

    private static void testPutIsInterruptible() throws Exception {
        System.out.println("[testPutIsInterruptible]");

        final BlockingQueue<Integer> q = new BlockingQueue<>(1);
        q.add(1); // full

        final AtomicBoolean caught = new AtomicBoolean(false);
        final CountDownLatch started = new CountDownLatch(1);
        Thread t = daemon(() -> {
            started.countDown();
            try {
                q.put(2);
            } catch (InterruptedException e) {
                caught.set(true);
            }
        }, "interruptible-putter");

        t.start();
        started.await(1, TimeUnit.SECONDS);
        Thread.sleep(150); // let it park

        t.interrupt();
        t.join(1000);

        assertTrue(!t.isAlive(), "put() must unblock on interrupt, not hang");
        assertTrue(caught.get(), "put() should throw InterruptedException when interrupted");
        assertEquals(1, q.size(), "an interrupted put() must not have inserted");

        System.out.println("  ok");
    }

    /* ================= stress invariants ================= */

    // take() must NEVER return null — it either returns an element or throws InterruptedException.
    // Needs many consumers: a woken consumer can find the queue already emptied by a barging
    // consumer that arrived fresh and never waited. With `if` instead of `while` around the
    // await, the woken thread falls straight through to a poll() on an empty queue.
    // This is the second reason wait must sit in a loop — barging, not just spurious wakeups.
    private static void testTakeNeverReturnsNullUnderContention() throws Exception {
        System.out.println("[testTakeNeverReturnsNullUnderContention]");

        final int CONSUMERS = 6, PRODUCERS = 6, PER_PRODUCER = 3000;
        final int TOTAL = PRODUCERS * PER_PRODUCER;
        final BlockingQueue<Integer> q = new BlockingQueue<>(4);
        final AtomicReference<String> violation = new AtomicReference<>();
        final AtomicInteger consumed = new AtomicInteger(0);
        final List<Thread> threads = new ArrayList<>();

        for (int c = 0; c < CONSUMERS; c++) {
            Thread t = daemon(() -> {
                try {
                    while (consumed.get() < TOTAL && violation.get() == null) {
                        Integer v = q.take();
                        if (v == null) {
                            violation.compareAndSet(null, "take() returned null instead of blocking");
                            break;
                        }
                        consumed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "null-check-consumer-" + c);
            threads.add(t);
            t.start();
        }

        for (int p = 0; p < PRODUCERS; p++) {
            Thread t = daemon(() -> {
                try {
                    for (int i = 0; i < PER_PRODUCER; i++)
                        q.put(i);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "null-check-producer-" + p);
            threads.add(t);
            t.start();
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (consumed.get() < TOTAL && violation.get() == null && System.nanoTime() < deadline)
            Thread.sleep(10);

        for (Thread t : threads)
            t.interrupt();
        for (Thread t : threads)
            t.join(2000);

        assertTrue(violation.get() == null, "take() must never return null — " + violation.get());
        assertEquals(TOTAL, consumed.get(), "every item taken exactly once (no null fall-through, no loss)");

        System.out.println("  ok");
    }

    // Invariant-based rather than count-based: a single consumer means observation order
    // IS dequeue order, so per-producer sequence numbers must arrive as an unbroken run.
    // That one check catches reordering, loss, AND duplication simultaneously. A watchdog
    // thread flags a capacity breach the instant it happens rather than inferring it later.
    private static void testCapacityAndFifoUnderStress() throws Exception {
        System.out.println("[testCapacityAndFifoUnderStress]");

        final int CAP = 4, PRODUCERS = 4, PER_PRODUCER = 4000;
        final int TOTAL = PRODUCERS * PER_PRODUCER;
        final BlockingQueue<int[]> q = new BlockingQueue<>(CAP);
        final AtomicReference<String> violation = new AtomicReference<>();

        Thread watchdog = daemon(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                int s = q.size();
                if (s > CAP)
                    violation.compareAndSet(null, "size=" + s + " exceeded capacity " + CAP);
                Thread.yield();
            }
        }, "capacity-watchdog");
        watchdog.start();

        final List<Thread> producers = new ArrayList<>();
        for (int p = 0; p < PRODUCERS; p++) {
            final int id = p;
            Thread t = daemon(() -> {
                try {
                    for (int s = 0; s < PER_PRODUCER; s++) {
                        int[] item = { id, s };
                        if ((s & 1) == 0)
                            q.put(item); // exercise the blocking path
                        else
                            while (!q.offer(item, 50, TimeUnit.MILLISECONDS)) {
                            } // and the timed path
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "stress-producer-" + id);
            producers.add(t);
            t.start();
        }

        final int[] lastSeq = new int[PRODUCERS];
        Arrays.fill(lastSeq, -1);

        int consumed = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (consumed < TOTAL && System.nanoTime() < deadline && violation.get() == null) {
            int[] item = q.poll(200, TimeUnit.MILLISECONDS);
            if (item == null)
                continue;
            int p = item[0], seq = item[1];
            if (seq != lastSeq[p] + 1) {
                violation.compareAndSet(null,
                        "FIFO/loss/duplicate for producer " + p + ": expected seq " + (lastSeq[p] + 1) + ", got " + seq);
                break;
            }
            lastSeq[p] = seq;
            consumed++;
        }

        watchdog.interrupt();
        for (Thread t : producers)
            t.join(5000);

        assertTrue(violation.get() == null, "stress invariant violated: " + violation.get());
        assertEquals(TOTAL, consumed, "every produced item consumed exactly once, in per-producer order");
        assertEquals(0, q.size(), "queue drained at end of stress");

        System.out.println("  ok");
    }
}
