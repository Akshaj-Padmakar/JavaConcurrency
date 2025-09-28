package Problems.Classic.BlockingQueue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

//AI-TEST.
public class BlockingQueueTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== BlockingQueue tests start ===");

        testAddAndOfferBehavior();
        testPutBlocksUntilSpace();
        testOfferWithTimeout();
        testPollAndPollTimeout();
        testTakeBlocksAndInterrupt();
        testMultiProducerConsumer();

        System.out.println("=== All tests passed ===");
    }

    // helper asserts
    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("Assertion failed: " + msg);
    }
    private static void assertEquals(Object a, Object b, String msg) {
        if (a == null ? b != null : !a.equals(b)) {
            throw new AssertionError("Assertion failed: " + msg + " — expected: " + b + ", actual: " + a);
        }
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

        Thread producer = new Thread(() -> {
            try {
                producerStarted.countDown();
                // This should block until a consumer removes an item
                q.put(3);
                producerDone.countDown();
            } catch (InterruptedException e) {
                // propagate for debugging
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

        Thread offeringThread = new Thread(() -> {
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

        Thread consumer = new Thread(() -> {
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
        Thread t = new Thread(() -> {
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
                            if (produced.getCount() == 0 && q.size() == 0) break;
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
}
