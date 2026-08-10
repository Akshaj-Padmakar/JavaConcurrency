package Problems.S01_Classic.P01_BlockingQueue;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BlockingQueue<T> {
    private final int maxCapacity;

    private final Queue<T> queue;

    private final Lock lock;
    private final Condition emptyCondition;
    private final Condition fullCondition;

    public BlockingQueue(int maxCapacity) {
        this(maxCapacity, false);
    }

    public BlockingQueue(int maxCapacity, boolean fair) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0.");
        }
        this.maxCapacity = maxCapacity;
        this.lock = new ReentrantLock(fair);
        this.queue = new LinkedList<>();
        this.emptyCondition = this.lock.newCondition();
        this.fullCondition = this.lock.newCondition();
    }

    /* ================= INSERT METHODS ================= */
    public void add(T item) {
        nullCheckOnItem(item);

        lock.lock();
        try {
            if (isFull()) {
                throw new IllegalStateException("Queue is full.");
            }
            addItem(item);
        } finally {
            lock.unlock();
        }
    }


    public boolean offer(T item) {
        nullCheckOnItem(item);

        lock.lock();
        try {
            if (isFull()) {
                return false;
            }
            addItem(item);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void put(T item) throws InterruptedException {
        nullCheckOnItem(item);

        lock.lockInterruptibly();
        try {
            while (isFull()) {
                fullCondition.await();
            }
            addItem(item);
        } finally {
            lock.unlock();
        }
    }

    public boolean offer(T item, long timeout, TimeUnit timeUnit) throws InterruptedException {
        nullCheckOnItem(item);

        long deadline = System.nanoTime() + timeUnit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (isFull()) {
                long nanos = deadline - System.nanoTime();
                if (nanos <= 0) {
                    return false;
                }
                boolean awaitSuccess = fullCondition.await(nanos, TimeUnit.NANOSECONDS);
                if (!awaitSuccess) {
                    return false;
                }
            }
            addItem(item);
            return true;
        } finally {
            lock.unlock();
        }
    }

    private void nullCheckOnItem(T item) {
        if (item == null) {
            throw new NullPointerException("Item must be non-null.");
        }
    }

    private boolean isFull() {
        return queue.size() == maxCapacity;
    }

    private void addItem(T item) {
        queue.add(item);
        emptyCondition.signal();
    }

    /* ================= REMOVE METHODS ================= */

    public T poll() {
        lock.lock();
        try {
            if (queue.isEmpty()) {
                return null;
            }
            return pollElement();
        } finally {
            lock.unlock();
        }
    }

    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                emptyCondition.await();
            }
            return pollElement();
        } finally {
            lock.unlock();
        }
    }

    public T poll(long timeout, TimeUnit timeUnit) throws InterruptedException {
        long deadline = System.nanoTime() + timeUnit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                long nanos = deadline - System.nanoTime();
                if (nanos <= 0) {
                    return null;
                }
                boolean awaitSuccess = emptyCondition.await(nanos, TimeUnit.NANOSECONDS);
                if (!awaitSuccess) {
                    return null;
                }
            }
            return pollElement();
        } finally {
            lock.unlock();
        }
    }


    private T pollElement() {
        fullCondition.signal();
        return queue.poll();
    }

    /* ================= EXAMINE METHODS ================= */

    public T peek() {
        lock.lock();
        try {
            return queue.peek();
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    public int remainingCapacity() {
        lock.lock();
        try {
            return maxCapacity - queue.size();
        } finally {
            lock.unlock();
        }
    }

}
