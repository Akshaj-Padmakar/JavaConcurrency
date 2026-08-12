package Problems.S05_SystemCoding.P05_JobBatcher;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public class JobBatcher {
    private final int workerCnt;
    private final Consumer<Job> handler;

    private final List<Thread> workers = new ArrayList<>();

    private final Lock lock = new ReentrantLock();
    private final Condition workAvailable = lock.newCondition();

    private boolean shutdown = false;
    private boolean shutdownNow = false;

    private final Map<String, Deque<Job>> pending = new HashMap<>();
    private final Set<String> running = new HashSet<>();
    private final Deque<String> ready = new ArrayDeque<>();

    private int pendingJobs = 0;


    public JobBatcher(int workerCnt, Consumer<Job> handler) {
        if (workerCnt <= 0) throw new IllegalArgumentException("Worker Count should be > 0");
        if (handler == null) throw new IllegalArgumentException("handle must not be null.");
        this.workerCnt = workerCnt;
        this.handler = handler;
        for (int i = 0; i < this.workerCnt; i++) {
            Thread worker = new Thread(this::workerLoop, "Job-worker-" + i);
            workers.add(worker);
            worker.start();
        }
    }

    public void submit(Job job) {
        if (job == null || job.getId() == null) {
            throw new IllegalArgumentException("Job and Job.id must be non-null.");
        }

        lock.lock();
        try {
            if (shutdown) {
                throw new IllegalStateException("Job Batcher is shut-down.");
            }
            Deque<Job> queue = pending.get(job.getId());
            if (queue == null) {
                queue = new ArrayDeque<>();
                pending.put(job.getId(), queue);
            }

            boolean becameRunnable = queue.isEmpty() && !running.contains(job.getId());
            queue.add(job);
            pendingJobs++;

            if (becameRunnable) {
                ready.add(job.getId());
                workAvailable.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        lock.lock();
        try {
            this.shutdown = true;
            workAvailable.signalAll(); // unblock empty thread's run...
        } finally {
            lock.unlock();
        }
    }

    public List<Job> shutdownNow() {
        List<Job> abandoned = new ArrayList<>();
        lock.lock();
        try {
            shutdown = true;
            shutdownNow = true;

            for (Deque<Job> job : pending.values()) {
                abandoned.addAll(job);
            }
            pending.clear();
            ready.clear();
            pendingJobs = running.size();
            workAvailable.signalAll();
        } finally {
            lock.unlock();
        }
        for (Thread worker : workers) {
            worker.interrupt();
        }
        return abandoned;
    }

    public boolean awaitTermination(long timeout, TimeUnit timeUnit) throws InterruptedException {
        long deadline = System.nanoTime() + timeUnit.toNanos(timeout);
        for (Thread worker : workers) {
            long remainingMillis = (deadline - System.nanoTime()) / 1_000_000;
            if (remainingMillis <= 0) {
                break;
            }
            worker.join(remainingMillis);
        }

        for (Thread worker : workers) {
            if (worker.isAlive()) {
                return false;
            }
        }
        return true;
    }

    private void workerLoop() {
        while (true) {
            Job job;
            String id;
            lock.lock();
            try {
                while (!shutdownNow && ready.isEmpty() && !(shutdown && pendingJobs == 0)) {
                    workAvailable.await();
                }
                if (ready.isEmpty() || shutdownNow) {
                    // => shutdown is called and no pendingJob
                    // Since shutdown, stops taking new jobs, but completes the existing jobs.

                    // If shutdownNow, don't try to do queued up jobs.
                    return;
                }

                id = ready.poll();
                job = pending.get(id).poll();
                running.add(id);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }

            try {
                handler.accept(job);
            } catch (Throwable t) {
                System.err.println(Thread.currentThread().getName() + ": job " + job + " threw " + t);
            } finally {
                finish(id);
            }
        }
    }

    private void finish(String id) {
        lock.lock();
        try {
            running.remove(id);
            pendingJobs--;
            Deque<Job> queue = pending.get(id);
            if (queue == null || queue.isEmpty()) {
                pending.remove(id); // no job of this id have been submitted.
            } else {
                ready.add(id);
                workAvailable.signal();
            }

            if (shutdown && pendingJobs == 0) { // unblock empty thread's run...
                workAvailable.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }


    public static class Job {
        private final String type;
        private final String id;
        private final Map<String, Object> payload;

        public Job(String type, String id) {
            this(type, id, null);
        }

        public Job(String type, String id, Map<String, Object> payload) {
            this.type = type;
            this.id = id;
            this.payload = payload;
        }

        public String getType() {
            return this.type;
        }

        public String getId() {
            return this.id;
        }

        public Map<String, Object> getPayload() {
            return this.payload;
        }
    }
}
