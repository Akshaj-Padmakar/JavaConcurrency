package Problems.S05_SystemCoding.P05_JobBatcher.Test;

import Problems.S05_SystemCoding.P05_JobBatcher.JobBatcher;
import Problems.S05_SystemCoding.P05_JobBatcher.JobBatcher.Job;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plain main()-based tests, matching this repo's style (no JUnit).
 *
 * The two invariants worth stating up front, because most of the suite exists to check them:
 *
 *  1. EXCLUSION + ORDER -- two jobs with the same id never overlap, and jobs for one id run in
 *     submission order. Checked live inside the handler, not inferred afterwards: a shared
 *     "currently executing" set flags any double entry the moment it happens.
 *
 *  2. ACCOUNTING -- after shutdownNow(), every submitted job either entered the handler or came
 *     back in the returned list. Never both, never neither.
 *
 * Every test that could block uses a bounded join, so a missing signal fails with a message instead
 * of freezing the suite -- which is how the signal()/signalAll() bug on the termination path was
 * found.
 */
public class JobBatcherTest {

    private static final long JOIN_TIMEOUT_MILLIS = 30_000;

    public static void main(String[] args) throws Exception {
        testIdleWorkersAllExitOnShutdown();
        testSameIdNeverOverlapsAndKeepsSubmissionOrder();
        testDifferentIdsRunConcurrently();
        testThrowingJobDoesNotWedgeItsId();
        testHotIdDoesNotStarveAnUnrelatedId();
        testShutdownRunsEverythingAlreadySubmitted();
        testSubmitAfterShutdownIsRejected();
        testSingleWorkerDrainsFully();
        testShutdownNowReturnsQueuedWorkAndInterruptsRunning();
        testShutdownNowAccountsForEverySubmittedJob();
        testShutdownNowOnAnIdleBatcher();
        testShutdownNowIsIdempotent();
        testGracefulThenAbruptShutdown();
        testInvalidConstructorArgumentsRejected();

        System.out.println("All JobBatcher tests passed.");
    }

    /**
     * Regression. Termination is a BROADCAST -- every worker's predicate flips at once, so it needs
     * signalAll(). With signal() exactly one worker woke and exited; the rest parked forever.
     */
    private static void testIdleWorkersAllExitOnShutdown() throws Exception {
        JobBatcher batcher = new JobBatcher(8, job -> { });
        Thread.sleep(150); // let all eight actually park

        batcher.shutdown();
        assertTrue("every idle worker must exit on shutdown(), not just one",
                batcher.awaitTermination(5, TimeUnit.SECONDS));
    }

    /** The core guarantee, checked live: 40 ids x 50 jobs on 8 workers. */
    private static void testSameIdNeverOverlapsAndKeepsSubmissionOrder() throws Exception {
        int ids = 40;
        int perId = 50;

        Set<String> executing = new HashSet<>();
        Map<String, Integer> lastSequence = new HashMap<>();
        AtomicBoolean overlapped = new AtomicBoolean(false);
        AtomicBoolean misordered = new AtomicBoolean(false);
        AtomicInteger completed = new AtomicInteger();

        JobBatcher batcher = new JobBatcher(8, job -> {
            synchronized (executing) {
                if (!executing.add(job.getId())) {
                    overlapped.set(true);   // this id was ALREADY running -- the one thing forbidden
                }
            }
            sleepNanos(200_000);            // widen the window so an overlap would be observable
            synchronized (lastSequence) {
                int sequence = (Integer) job.getPayload().get("seq");
                Integer previous = lastSequence.get(job.getId());
                if (previous != null && sequence != previous + 1) {
                    misordered.set(true);
                }
                lastSequence.put(job.getId(), sequence);
            }
            synchronized (executing) {
                executing.remove(job.getId());
            }
            completed.incrementAndGet();
        });

        for (int sequence = 0; sequence < perId; sequence++) {
            for (int id = 0; id < ids; id++) {
                batcher.submit(new Job("work", "id-" + id, payload("seq", sequence)));
            }
        }
        batcher.shutdown();

        assertTrue("the batcher must terminate", batcher.awaitTermination(60, TimeUnit.SECONDS));
        assertTrue("two jobs for one id must never run at the same time", !overlapped.get());
        assertTrue("jobs for one id must run in submission order", !misordered.get());
        assertEquals("every job ran exactly once", ids * perId, completed.get());
    }

    /** Exclusion is per id -- different ids must genuinely overlap, or the pool is pointless. */
    private static void testDifferentIdsRunConcurrently() throws Exception {
        int workers = 6;
        CountDownLatch allInsideHandler = new CountDownLatch(workers);

        JobBatcher batcher = new JobBatcher(workers, job -> {
            allInsideHandler.countDown();
            await(allInsideHandler, 5);   // only completes if all six are in the handler together
        });
        for (int i = 0; i < workers; i++) {
            batcher.submit(new Job("work", "id-" + i));
        }

        boolean overlapped = allInsideHandler.await(5, TimeUnit.SECONDS);
        batcher.shutdown();
        batcher.awaitTermination(10, TimeUnit.SECONDS);

        assertTrue(workers + " different ids must run concurrently, not serialize", overlapped);
    }

    /** A failed job must release its id, or every later job for that id is stranded. */
    private static void testThrowingJobDoesNotWedgeItsId() throws Exception {
        AtomicInteger ranAfterTheFailure = new AtomicInteger();

        JobBatcher batcher = new JobBatcher(4, job -> {
            if ("boom".equals(job.getPayload().get("mode"))) {
                throw new RuntimeException("deliberate test failure");
            }
            ranAfterTheFailure.incrementAndGet();
        });

        batcher.submit(new Job("work", "id-1", payload("mode", "boom")));
        for (int i = 0; i < 5; i++) {
            batcher.submit(new Job("work", "id-1", payload("mode", "ok")));
        }
        batcher.shutdown();

        assertTrue("the batcher must terminate", batcher.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals("later jobs for the same id must still run", 5, ranAfterTheFailure.get());
    }

    /**
     * 500 jobs for one id plus a single job for another. The lone job must not queue behind all 500
     * -- that is the head-of-line blocking that hash-partitioning to per-worker queues would cause.
     */
    private static void testHotIdDoesNotStarveAnUnrelatedId() throws Exception {
        CountDownLatch coldJobDone = new CountDownLatch(1);
        AtomicInteger hotJobsRun = new AtomicInteger();
        AtomicInteger hotJobsWhenColdRan = new AtomicInteger(-1);

        JobBatcher batcher = new JobBatcher(4, job -> {
            if (job.getId().equals("hot")) {
                sleepMillis(1);
                hotJobsRun.incrementAndGet();
            } else {
                hotJobsWhenColdRan.set(hotJobsRun.get());
                coldJobDone.countDown();
            }
        });

        for (int i = 0; i < 500; i++) {
            batcher.submit(new Job("work", "hot"));
        }
        batcher.submit(new Job("work", "cold"));

        boolean coldRanQuickly = coldJobDone.await(5, TimeUnit.SECONDS);
        batcher.shutdown();
        batcher.awaitTermination(60, TimeUnit.SECONDS);

        assertTrue("the cold job must not wait for the hot backlog", coldRanQuickly);
        assertTrue("the cold job ran after " + hotJobsWhenColdRan.get()
                + " hot jobs -- it should not have waited for all 500",
                hotJobsWhenColdRan.get() < 100);
    }

    /**
     * Graceful shutdown must drain. Note work is REGENERATED while draining -- a finishing job
     * re-queues its id -- which is why termination is "outstanding == 0" and not "queue is empty".
     */
    private static void testShutdownRunsEverythingAlreadySubmitted() throws Exception {
        AtomicInteger completed = new AtomicInteger();
        JobBatcher batcher = new JobBatcher(3, job -> {
            sleepMillis(2);
            completed.incrementAndGet();
        });

        for (int i = 0; i < 300; i++) {
            batcher.submit(new Job("work", "id-" + (i % 10)));
        }
        batcher.shutdown();

        assertTrue("the batcher must terminate", batcher.awaitTermination(60, TimeUnit.SECONDS));
        assertEquals("everything submitted before shutdown() must run", 300, completed.get());
    }

    private static void testSubmitAfterShutdownIsRejected() throws Exception {
        JobBatcher batcher = new JobBatcher(2, job -> { });
        batcher.shutdown();

        boolean rejected = false;
        try {
            batcher.submit(new Job("work", "id-1"));
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        batcher.awaitTermination(5, TimeUnit.SECONDS);

        assertTrue("submit() after shutdown() must be rejected", rejected);
    }

    private static void testSingleWorkerDrainsFully() throws Exception {
        AtomicInteger completed = new AtomicInteger();
        JobBatcher batcher = new JobBatcher(1, job -> completed.incrementAndGet());

        for (int i = 0; i < 200; i++) {
            batcher.submit(new Job("work", "id-" + (i % 7)));
        }
        batcher.shutdown();

        assertTrue("a single worker must still terminate", batcher.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals("and still run everything", 200, completed.get());
    }

    /**
     * Abrupt shutdown must not wait out in-flight work. The handler sleeps 5 seconds; if the
     * interrupt is delivered and observed, termination happens in milliseconds.
     */
    private static void testShutdownNowReturnsQueuedWorkAndInterruptsRunning() throws Exception {
        CountDownLatch twoJobsStarted = new CountDownLatch(2);
        JobBatcher batcher = new JobBatcher(2, job -> {
            twoJobsStarted.countDown();
            sleepMillis(5_000);   // restores the interrupt flag, see sleepMillis
        });

        for (int i = 0; i < 200; i++) {
            batcher.submit(new Job("work", "id-" + i));
        }
        assertTrue("two jobs should be in flight", twoJobsStarted.await(5, TimeUnit.SECONDS));

        long startNanos = System.nanoTime();
        List<Job> abandoned = batcher.shutdownNow();
        boolean terminated = batcher.awaitTermination(5, TimeUnit.SECONDS);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertTrue("shutdownNow() must terminate the batcher", terminated);
        assertTrue("it must interrupt rather than wait out the 5s jobs (took " + elapsedMillis + "ms)",
                elapsedMillis < 2_000);
        assertTrue("queued-but-unstarted jobs must be returned", abandoned.size() > 0);
    }

    /** The accounting invariant: started + returned == submitted. Never both, never neither. */
    private static void testShutdownNowAccountsForEverySubmittedJob() throws Exception {
        int submitted = 200;
        AtomicInteger started = new AtomicInteger();
        CountDownLatch twoJobsStarted = new CountDownLatch(2);

        JobBatcher batcher = new JobBatcher(2, job -> {
            started.incrementAndGet();
            twoJobsStarted.countDown();
            sleepMillis(5_000);
        });

        for (int i = 0; i < submitted; i++) {
            batcher.submit(new Job("work", "id-" + i));
        }
        assertTrue("two jobs should be in flight", twoJobsStarted.await(5, TimeUnit.SECONDS));

        List<Job> abandoned = batcher.shutdownNow();
        assertTrue("the batcher must terminate", batcher.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals("every job either started or was returned -- never lost, never double counted",
                submitted, started.get() + abandoned.size());
    }

    private static void testShutdownNowOnAnIdleBatcher() throws Exception {
        JobBatcher batcher = new JobBatcher(4, job -> { });
        Thread.sleep(150);

        List<Job> abandoned = batcher.shutdownNow();

        assertEquals("nothing was queued, so nothing comes back", 0, abandoned.size());
        assertTrue("idle workers must still exit", batcher.awaitTermination(5, TimeUnit.SECONDS));

        boolean rejected = false;
        try {
            batcher.submit(new Job("work", "id-1"));
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        assertTrue("submit() after shutdownNow() must be rejected", rejected);
    }

    private static void testShutdownNowIsIdempotent() throws Exception {
        JobBatcher batcher = new JobBatcher(2, job -> sleepMillis(50));
        for (int i = 0; i < 50; i++) {
            batcher.submit(new Job("work", "id-" + i));
        }

        batcher.shutdownNow();
        List<Job> second = batcher.shutdownNow();

        assertEquals("a second shutdownNow() has nothing left to abandon", 0, second.size());
        assertTrue("the batcher must still terminate", batcher.awaitTermination(10, TimeUnit.SECONDS));
    }

    /** Escalation: start draining gracefully, then give up and abandon the rest. */
    private static void testGracefulThenAbruptShutdown() throws Exception {
        AtomicInteger completed = new AtomicInteger();
        JobBatcher batcher = new JobBatcher(2, job -> {
            sleepMillis(50);
            completed.incrementAndGet();
        });

        for (int i = 0; i < 100; i++) {
            batcher.submit(new Job("work", "id-" + i));
        }
        batcher.shutdown();
        Thread.sleep(80);                       // let a couple of jobs through
        List<Job> abandoned = batcher.shutdownNow();

        assertTrue("the batcher must terminate", batcher.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue("escalating must actually abandon the remaining work", abandoned.size() > 0);
        assertTrue("nothing was both completed and abandoned",
                completed.get() + abandoned.size() <= 100);
    }

    private static void testInvalidConstructorArgumentsRejected() {
        assertTrue("workerCount of 0 must be rejected",
                constructorRejects(() -> new JobBatcher(0, job -> { })));
        assertTrue("a negative workerCount must be rejected",
                constructorRejects(() -> new JobBatcher(-1, job -> { })));
        assertTrue("a null handler must be rejected",
                constructorRejects(() -> new JobBatcher(2, null)));
    }

    // ---------- harness ----------

    private static Map<String, Object> payload(String key, Object value) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(key, value);
        return payload;
    }

    /** Restores the interrupt flag, so a shutdownNow() interrupt is not swallowed by the handler. */
    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepNanos(int nanos) {
        try {
            Thread.sleep(0, nanos);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch, long seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean constructorRejects(Runnable construction) {
        try {
            construction.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        } catch (NullPointerException expected) {
            return true;
        }
    }

    private static void assertTrue(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError("FAILED: " + message);
        }
    }

    private static void assertEquals(String message, long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError("FAILED: " + message + " (expected " + expected + ", got " + actual + ")");
        }
    }

    static {
        // Unused, but documents the bound every join in this suite uses.
        assert JOIN_TIMEOUT_MILLIS > 0;
    }
}
