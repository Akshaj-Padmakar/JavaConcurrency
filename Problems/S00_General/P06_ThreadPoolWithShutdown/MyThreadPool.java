package Problems.S00_General.P06_ThreadPoolWithShutdown;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class MyThreadPool {
    private final MyBlockingQueue<Runnable> taskQueue;
    private final List<Thread> workers;
    private final int poolSize;

    private final Lock lock = new ReentrantLock();

    private volatile boolean isShutdown = false;
    private volatile boolean isShutdownNow = false;

    private static final Runnable POSION_PILL = () -> {
    };

    public MyThreadPool(int poolSize, int queueCapacity) {
        if (poolSize <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("Invalid paramaeters passed to ThreadPool!");
        }
        this.taskQueue = new MyBlockingQueue<Runnable>(queueCapacity);
        this.poolSize = poolSize;
        this.workers = new ArrayList<>();
        for (int i = 0; i < poolSize; i++) {
            Thread thread = new Thread(new WorkerLoopRunnable(), "Worker-Thread-" + i);
            this.workers.add(thread);
            thread.start();
        }
    }

    public void execute(Runnable task) {
        if (task == null) {
            throw new NullPointerException("Task cannot be null");
        }

        lock.lock();
        try {
            if (isShutdown) {
                throw new RejectedExecutionException("Thread-Pool Shutdown, cannot execute.");
            }
            taskQueue.put(task);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
            Thread.currentThread().interrupt();
            throw new RejectedExecutionException("Interrupted while submitting task", ex);
        } finally {
            lock.unlock();
        }
    }

    private class WorkerLoopRunnable implements Runnable {
        @Override
        public void run() {
            while (true) {
                Runnable task;
                try {
                    task = taskQueue.take();
                    if (isShutdownNow) {
                        return;
                    }
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                    Thread.currentThread().interrupt();
                    return;
                }

                if (task == POSION_PILL) {
                    // gracefull shutdown the ThreadPool, every task have been executed.
                    return;
                }

                try {
                    task.run();
                } catch (Throwable t) {
                    // Single bad task should not take down threadPool.
                    System.err.println(Thread.currentThread().getName() + ": task threw " + t);
                }
            }
        }
    }

    public void shutdown() {
        // Allow the ongoing task(i.e. existing in queue) to complete,
        // o.w let the thread eat Posion-Pill.
        lock.lock();
        try {
            if (isShutdown) {
                return; // Idempotent API.
            }

            isShutdown = true;
            for (int i = 0; i < this.poolSize; i++) {
                try {
                    taskQueue.put(POSION_PILL);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public List<Runnable> shutdownNow() {
        // tries to interrupt the thread, if interruput is catched by its runnable,
        // shutdown plausible.
        // drain is outside the lock, since when it does shutdown, no other thread can
        // add new task, since other operations are inside lock.
        lock.lock();
        try {
            isShutdown = true;
            isShutdownNow = true;
        } finally {
            lock.unlock();
        }
        List<Runnable> neverStarted = taskQueue.drainAll();
        for (Thread t : workers) {
            t.interrupt();
        }
        return neverStarted;
    }

    public boolean awaitTermination(long timeout, TimeUnit timeUnit) throws InterruptedException {
        // return true of false, that after T time, have all the threads returned ?
        // blocking...
        long deadline = System.nanoTime() + timeUnit.toNanos(timeout);
        for (Thread worker : workers) {
            long remainingNano = deadline - System.nanoTime();
            if (remainingNano / 1_000_000 <= 0) {
                return isTerminated();
            }
            worker.join(remainingNano / 1_000_000);
        }

        return isTerminated();
    }

    public boolean isTerminated() {
        if (!isShutdown) {
            return false;
        }

        for (Thread t : workers) {
            if (t.isAlive()) {
                return false;
            }
        }
        return true;
    }

    public boolean isShutdown() {
        return isShutdown;
    }

    public boolean isShutdownNow() {
        return isShutdownNow;
    }

}
