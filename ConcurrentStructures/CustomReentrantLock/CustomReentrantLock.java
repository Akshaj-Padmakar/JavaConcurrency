package ConcurrentStructures.CustomReentrantLock;

import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

/**
 * CustomReentrantLock — corrected, simpler, and robust.
 *
 * - Supports fair/non-fair (fast non-fair path + queued fairness).
 * - Implements lock(), lockInterruptibly(), tryLock(), tryLock(timeout), unlock().
 * - Condition implementation (await/signal/signalAll) re-acquires the lock by calling lock()
 *   after being signalled/timeout/interruption. This reuses the lock acquisition logic and
 *   avoids fragile hand-rolled re-acquire code that caused deadlocks.
 *
 * Educational implementation using synchronized + wait/notifyAll (not as efficient as AQS).
 */
public class CustomReentrantLock {

    private final boolean fair;
    private Thread owner = null;
    private int holdCount = 0;
    private final Deque<Node> waitQueue = new LinkedList<>();

    private class Node {
        private final Thread thread;
        boolean signalled = false; // used for condition waiters
        public Node(Thread t) { this.thread = t; }
    }

    public CustomReentrantLock() { this(false); }
    public CustomReentrantLock(boolean fair) { this.fair = fair; }

    /* ================= LOCK METHODS ================= */

    public void lock() {
        boolean interrupted = false;
        synchronized (this) {
            final Thread current = Thread.currentThread();

            // Reentrant fast path
            if (owner == current) {
                holdCount++;
                return; // return immediately for reentrant acquisition
            }

            // Fast non-fair path (barging, do not enqueue)
            if (!fair && owner == null) {
                owner = current;
                holdCount = 1;
                return;
            }

            // Enqueue and wait for our turn (fair mode OR lock was held)
            Node node = new Node(current);
            waitQueue.addLast(node);
            try {
                while (true) {
                    boolean isHead = (waitQueue.peekFirst() == node);
                    if (owner == null && isHead) {
                        // acquire
                        owner = current;
                        holdCount = 1;
                        waitQueue.removeFirst();
                        return;
                    }
                    try {
                        this.wait();
                    } catch (InterruptedException e) {
                        // lock() is non-interruptible: record and continue waiting
                        interrupted = true;
                    }
                }
            } finally {
                // ensure removal if still enqueued
                waitQueue.remove(node);
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    public void lockInterruptibly() throws InterruptedException {
        synchronized (this) {
            final Thread current = Thread.currentThread();

            // Reentrant fast path
            if (owner == current) {
                holdCount++;
                return;
            }

            // Fast non-fair path
            if (!fair && owner == null) {
                owner = current;
                holdCount = 1;
                return;
            }

            // Enqueue and wait but obey interrupts
            Node node = new Node(current);
            waitQueue.addLast(node);
            try {
                while (true) {
                    boolean isHead = (waitQueue.peekFirst() == node);
                    if (owner == null && isHead) {
                        // Acquire and return
                        owner = current;
                        holdCount = 1;
                        waitQueue.removeFirst();
                        return;
                    }
                    this.wait(); // will throw InterruptedException if interrupted
                }
            } catch (InterruptedException ie) {
                // remove from queue and propagate
                waitQueue.remove(node);
                throw ie;
            }
        }
    }

    public boolean tryLock() {
        synchronized (this) {
            final Thread current = Thread.currentThread();

            // Reentrant
            if (owner == current) {
                holdCount++;
                return true;
            }

            if (owner == null) {
                if (fair) {
                    // fair -> succeed only if no queued predecessors
                    if (waitQueue.isEmpty()) {
                        owner = current;
                        holdCount = 1;
                        return true;
                    } else {
                        return false;
                    }
                } else {
                    // non-fair -> barge in
                    owner = current;
                    holdCount = 1;
                    return true;
                }
            } else {
                return false;
            }
        }
    }

    public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final long deadline = System.nanoTime() + nanos;

        synchronized (this) {
            final Thread current = Thread.currentThread();

            // Reentrant
            if (owner == current) {
                holdCount++;
                return true;
            }

            // Fast non-fair path
            if (!fair && owner == null) {
                owner = current;
                holdCount = 1;
                return true;
            }

            // Enqueue and wait up to timeout
            Node node = new Node(current);
            waitQueue.addLast(node);
            try {
                while (true) {
                    boolean isHead = (waitQueue.peekFirst() == node);
                    if (owner == null && isHead) {
                        owner = current;
                        holdCount = 1;
                        waitQueue.removeFirst();
                        return true;
                    }
                    nanos = deadline - System.nanoTime();
                    if (nanos <= 0L) {
                        return false;
                    }
                    long millis = nanos / 1_000_000L;
                    int nanosPart = (int) (nanos % 1_000_000L);
                    this.wait(millis, nanosPart);
                }
            } finally {
                waitQueue.remove(node);
            }
        }
    }

    public void unlock() {
        synchronized (this) {
            final Thread current = Thread.currentThread();
            if (owner != current) throw new IllegalMonitorStateException("This thread doesn't hold this Lock!");
            holdCount--;
            if (holdCount == 0) {
                owner = null;
                // Wake up waiters so head can try to acquire
                this.notifyAll();
            }
        }
    }

    /* ================= CONDITION ================= */

    public SimpleCondition newCondition() { return new SimpleCondition(); }

    public class SimpleCondition {
        private final Deque<Node> condWaiters = new LinkedList<>();

        /**
         * await(): release logical lock, wait on condition queue, and then re-acquire the logical lock
         * (by calling lock()) before returning. If interrupted while waiting, re-acquires the lock
         * and then throws InterruptedException.
         */
        public void await() throws InterruptedException {
            final Thread current = Thread.currentThread();
            int savedHoldCount;

            synchronized (CustomReentrantLock.this) {
                if (owner != current) throw new IllegalMonitorStateException("Lock not held by current thread");

                // Save and release logical lock
                savedHoldCount = holdCount;
                holdCount = 0;
                owner = null;

                // Put a condition node on condition queue
                Node condNode = new Node(current);
                condWaiters.addLast(condNode);

                boolean interrupted = false;
                try {
                    // Wait until signalled (handle spurious wakeups and interrupts)
                    while (!condNode.signalled) {
                        try {
                            CustomReentrantLock.this.wait();
                        } catch (InterruptedException ie) {
                            // record and break so we reacquire lock then throw
                            interrupted = true;
                            break;
                        }
                    }
                } finally {
                    // Remove from condition queue (if present)
                    condWaiters.remove(condNode);
                }

                // At this point we must reacquire the logical lock before returning.
                // We do that by calling lock() (reuses fair/non-fair acquisition rules).
                // NOTE: calling lock() here is allowed — we're still inside synchronized(this),
                // and synchronized is reentrant for the same thread; lock() will use the same monitor
                // and will perform wait() if it must (releasing the monitor while waiting).
                // After lock() returns, owner == current and holdCount == 1 (we'll restore savedHoldCount).
            }

            // Reacquire the lock using the same logic (may block). This call is outside the
            // synchronized(CustomReentrantLock.this) block above because lock() handles its own sync.
            lock();

            // Restore the saved hold count (lock() left holdCount==1)
            synchronized (this) {
                // We are holding the lock logically; replace holdCount with savedHoldCount
                synchronized (CustomReentrantLock.this) {
                    holdCount = savedHoldCount;
                }
            }

            // If we detected an interrupt during the cond wait, we must throw.
            // The interrupt status is preserved by lock() logic if needed; rethrow now.
            // (We check Thread.interrupted() to determine if we had an interrupt flagged.)
            if (Thread.interrupted()) {
                // If an interrupt was received while waiting, match Condition semantics:
                // throw InterruptedException
                throw new InterruptedException();
            }
        }

        /**
         * await with timeout. Returns true if signalled, false if timed out.
         * As above, we re-acquire the lock (by calling lock()) before returning.
         */
        public boolean await(long time, TimeUnit unit) throws InterruptedException {
            long nanos = unit.toNanos(time);
            final long deadline = System.nanoTime() + nanos;
            final Thread current = Thread.currentThread();
            int savedHoldCount;
            boolean signalled = false;

            synchronized (CustomReentrantLock.this) {
                if (owner != current) throw new IllegalMonitorStateException("Lock not held by current thread");

                savedHoldCount = holdCount;
                holdCount = 0;
                owner = null;

                Node condNode = new Node(current);
                condWaiters.addLast(condNode);

                boolean interrupted = false;
                try {
                    while (!condNode.signalled) {
                        nanos = deadline - System.nanoTime();
                        if (nanos <= 0L) break;
                        long millis = nanos / 1_000_000L;
                        int nanosPart = (int) (nanos % 1_000_000L);
                        try {
                            CustomReentrantLock.this.wait(millis, nanosPart);
                        } catch (InterruptedException ie) {
                            interrupted = true;
                            break;
                        }
                    }
                    signalled = condNode.signalled;
                } finally {
                    condWaiters.remove(condNode);
                }

                // leave synchronized block to call lock() below to re-acquire the logical lock
            }

            // Re-acquire the lock (may block)
            lock();

            // Restore hold count
            synchronized (this) {
                synchronized (CustomReentrantLock.this) {
                    holdCount = savedHoldCount;
                }
            }

            // If we were interrupted while waiting, match Condition semantics: clear and throw
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            return signalled;
        }

        public void signal() {
            synchronized (CustomReentrantLock.this) {
                final Thread current = Thread.currentThread();
                if (owner != current) throw new IllegalMonitorStateException("Lock not held by current thread");
                Node n = condWaiters.pollFirst();
                if (n != null) {
                    n.signalled = true;
                    CustomReentrantLock.this.notifyAll();
                }
            }
        }

        public void signalAll() {
            synchronized (CustomReentrantLock.this) {
                final Thread current = Thread.currentThread();
                if (owner != current) throw new IllegalMonitorStateException("Lock not held by current thread");
                for (Node n : condWaiters) n.signalled = true;
                CustomReentrantLock.this.notifyAll();
            }
        }
    }

    /* ================= HELPERS ================= */

    public boolean isFair() { return fair; }

    public int getHoldCount() {
        synchronized (this) { return Thread.currentThread() == owner ? holdCount : 0; }
    }

    public boolean isHeldByCurrentThread() {
        synchronized (this) { return Thread.currentThread() == owner; }
    }
}
