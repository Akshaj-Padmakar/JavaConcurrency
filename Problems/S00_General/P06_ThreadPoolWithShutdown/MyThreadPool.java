package Problems.S00_General.P06_ThreadPoolWithShutdown;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A from-scratch, fixed-size thread pool -- no ExecutorService/ThreadPoolExecutor, backed by
 * {@link MyBlockingQueue}. Supports both graceful ({@link #shutdown()}) and abrupt
 * ({@link #shutdownNow()}) termination.
 *
 * Graceful shutdown uses a "poison pill" sentinel: shutdown() enqueues exactly one poison pill
 * per worker. Because the queue is strict FIFO, every real task queued before shutdown() was
 * called is guaranteed to be drained and run before any worker reaches its poison pill and exits.
 * This avoids polling a "should I stop?" flag (which would need to distinguish "queue empty and
 * idle" from "queue empty and done" and risks a busy-wait), at the cost of needing every real
 * `execute()` call to be strictly ordered relative to the poison-pill batch -- see the `stateLock`
 * usage below for why that ordering is guaranteed, not just likely.
 */
public class MyThreadPool {

    private final MyBlockingQueue<Runnable> taskQueue;
    private final List<Thread> workers;
    private final int poolSize;

    // Guards isShutdown/isShutdownNow AND brackets the *entire* body of execute()'s put and
    // shutdown()'s/shutdownNow()'s state transitions -- not just the flag read. Without that, a
    // task submitted in the narrow window between "shutdown() sets the flag" and "shutdown()
    // finishes enqueueing all its poison pills" could land in the queue interleaved with (or
    // after) some poison pills, and never get picked up by a worker before that worker exits.
    private final Object stateLock = new Object();
    private volatile boolean isShutdown = false;
    private volatile boolean isShutdownNow = false;

    private static final Runnable POISON_PILL = () -> {};

    public MyThreadPool(int poolSize, int queueCapacity) {
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize must be > 0");
        }
        this.poolSize = poolSize;
        this.taskQueue = new MyBlockingQueue<>(queueCapacity);
        this.workers = new ArrayList<>();
        for (int i = 0; i < poolSize; i++) {
            Thread worker = new Thread(this::workerLoop, "MyThreadPool-Worker-" + i);
            workers.add(worker);
            worker.start();
        }
    }

    public void execute(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task cannot be null");
        }
        synchronized (stateLock) {
            if (isShutdown) {
                throw new RejectedExecutionException("Pool is shutting down, cannot accept new tasks");
            }
            try {
                taskQueue.put(task); // held under stateLock -- see class javadoc
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("Interrupted while submitting task", ex);
            }
        }
    }

    private void workerLoop() {
        while (true) {
            Runnable task;
            try {
                task = taskQueue.take();
            } catch (InterruptedException ex) {
                // shutdownNow() interrupts us directly, possibly while we're blocked here.
                Thread.currentThread().interrupt();
                return;
            }
            if (task == POISON_PILL) {
                return; // graceful shutdown: everything queued before us has already run
            }
            try {
                task.run();
            } catch (Throwable t) {
                // A single bad task must not take down the worker thread.
                System.err.println(Thread.currentThread().getName() + ": task threw " + t);
            }
        }
    }

    /** Graceful shutdown: stop accepting new tasks, let every already-queued task run to completion. */
    public void shutdown() {
        synchronized (stateLock) {
            if (isShutdown) {
                return; // idempotent
            }
            isShutdown = true;
            for (int i = 0; i < poolSize; i++) {
                try {
                    taskQueue.put(POISON_PILL);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Abrupt shutdown: stop accepting new tasks, best-effort interrupt whatever's currently
     * running (a task that never checks its interrupt status / never blocks won't actually stop
     * early -- same "best effort" contract as ExecutorService.shutdownNow()), and return whatever
     * was still queued but never started.
     */
    public List<Runnable> shutdownNow() {
        List<Runnable> neverStarted;
        synchronized (stateLock) {
            isShutdown = true;
            isShutdownNow = true;
            neverStarted = taskQueue.drainAll();
        }
        for (Thread worker : workers) {
            worker.interrupt();
        }
        return neverStarted;
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        for (Thread worker : workers) {
            long remainingMillis = (deadline - System.nanoTime()) / 1_000_000;
            if (remainingMillis <= 0) {
                return isTerminated();
            }
            worker.join(remainingMillis);
        }
        return isTerminated();
    }

    public boolean isShutdown() {
        return isShutdown;
    }

    public boolean isShutdownNow() {
        return isShutdownNow;
    }

    public boolean isTerminated() {
        if (!isShutdown) {
            return false;
        }
        for (Thread worker : workers) {
            if (worker.isAlive()) {
                return false;
            }
        }
        return true;
    }
}
