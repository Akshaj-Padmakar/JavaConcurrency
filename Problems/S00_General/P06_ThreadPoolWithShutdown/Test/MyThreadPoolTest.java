package Problems.S00_General.P06_ThreadPoolWithShutdown.Test;

import Problems.S00_General.P06_ThreadPoolWithShutdown.MyThreadPool;
import Problems.S00_General.P06_ThreadPoolWithShutdown.RejectedExecutionException;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MyThreadPoolTest {

    public static void main(String[] args) throws InterruptedException {
        testAllSubmittedTasksCompleteBeforeGracefulShutdown();
        testRejectsSubmissionsAfterShutdown();
        testShutdownIsIdempotent();
        testShutdownNowReturnsNeverStartedTasksAndInterruptsRunning();
        testConcurrentSubmittersDontLoseOrCorruptTasks();
        testExecuteRejectsNullTask();
        testTaskExceptionDoesNotKillWorker();

        System.out.println("All MyThreadPool tests passed.");
    }

    private static void testAllSubmittedTasksCompleteBeforeGracefulShutdown() throws InterruptedException {
        int taskCount = 30;
        MyThreadPool pool = new MyThreadPool(4, 3); // small queue -> forces execute() to block/backpressure
        Set<Integer> completed = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < taskCount; i++) {
            int id = i;
            pool.execute(() -> {
                sleepQuietly(5);
                completed.add(id);
            });
        }
        pool.shutdown();
        boolean terminatedInTime = pool.awaitTermination(5, TimeUnit.SECONDS);

        assertTrue("pool should terminate within the timeout", terminatedInTime);
        assertTrue("pool should report terminated", pool.isTerminated());
        assertEquals("every submitted task should have completed", taskCount, completed.size());
        for (int i = 0; i < taskCount; i++) {
            assertTrue("task " + i + " should have completed", completed.contains(i));
        }
    }

    private static void testRejectsSubmissionsAfterShutdown() throws InterruptedException {
        MyThreadPool pool = new MyThreadPool(2, 4);
        pool.execute(() -> sleepQuietly(1));
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.SECONDS);

        boolean rejected = false;
        try {
            pool.execute(() -> {});
        } catch (RejectedExecutionException ex) {
            rejected = true;
        }
        assertTrue("submitting after shutdown() must throw RejectedExecutionException", rejected);
    }

    private static void testShutdownIsIdempotent() throws InterruptedException {
        MyThreadPool pool = new MyThreadPool(3, 4);
        AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            pool.execute(ran::incrementAndGet);
        }
        pool.shutdown();
        pool.shutdown(); // calling twice must not hang or double up poison pills
        boolean terminated = pool.awaitTermination(3, TimeUnit.SECONDS);

        assertTrue("pool should still terminate cleanly after a double shutdown() call", terminated);
        assertEquals("all 5 tasks should have run exactly once", 5, ran.get());
    }

    private static void testShutdownNowReturnsNeverStartedTasksAndInterruptsRunning()
            throws InterruptedException {
        MyThreadPool pool = new MyThreadPool(1, 10); // single worker -> easy to control what's "running"
        AtomicInteger wasInterrupted = new AtomicInteger(0);
        CountDownLatch runningTaskStarted = new CountDownLatch(1);

        pool.execute(() -> {
            runningTaskStarted.countDown();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ex) {
                wasInterrupted.incrementAndGet();
                Thread.currentThread().interrupt();
            }
        });
        assertTrue("first task should have started", runningTaskStarted.await(2, TimeUnit.SECONDS));

        for (int i = 0; i < 3; i++) {
            pool.execute(() -> sleepQuietly(1));
        }

        List<Runnable> neverStarted = pool.shutdownNow();
        boolean terminated = pool.awaitTermination(3, TimeUnit.SECONDS);

        assertTrue("pool should terminate promptly after shutdownNow()", terminated);
        assertEquals("the 3 queued-but-not-yet-started tasks should be returned", 3, neverStarted.size());
        assertEquals("the actively running task should have observed the interrupt", 1, wasInterrupted.get());
    }

    private static void testConcurrentSubmittersDontLoseOrCorruptTasks() throws InterruptedException {
        MyThreadPool pool = new MyThreadPool(4, 5);
        int submitterCount = 8;
        int tasksPerSubmitter = 25;
        AtomicInteger totalRun = new AtomicInteger();
        CountDownLatch submittersDone = new CountDownLatch(submitterCount);

        for (int s = 0; s < submitterCount; s++) {
            Thread submitter = new Thread(() -> {
                for (int i = 0; i < tasksPerSubmitter; i++) {
                    pool.execute(totalRun::incrementAndGet);
                }
                submittersDone.countDown();
            });
            submitter.start();
        }

        assertTrue("all submitter threads should finish submitting", submittersDone.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        boolean terminated = pool.awaitTermination(10, TimeUnit.SECONDS);

        assertTrue("pool should terminate", terminated);
        assertEquals("every task from every concurrent submitter should have run exactly once",
                submitterCount * tasksPerSubmitter, totalRun.get());
    }

    private static void testExecuteRejectsNullTask() throws InterruptedException {
        MyThreadPool pool = new MyThreadPool(2, 4);
        boolean threw = false;
        try {
            pool.execute(null);
        } catch (NullPointerException ex) {
            threw = true;
        }
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.SECONDS);
        assertTrue("execute(null) should throw NullPointerException", threw);
    }

    private static void testTaskExceptionDoesNotKillWorker() throws InterruptedException {
        MyThreadPool pool = new MyThreadPool(1, 4); // single worker -- must survive the throwing task
        AtomicInteger ranAfter = new AtomicInteger();

        pool.execute(() -> {
            throw new RuntimeException("boom -- deliberate test exception");
        });
        for (int i = 0; i < 5; i++) {
            pool.execute(ranAfter::incrementAndGet);
        }
        pool.shutdown();
        boolean terminated = pool.awaitTermination(3, TimeUnit.SECONDS);

        assertTrue("pool should still terminate normally", terminated);
        assertEquals("tasks submitted after a throwing task must still run", 5, ranAfter.get());
    }

    private static void sleepQuietly(long millis) {
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
}
