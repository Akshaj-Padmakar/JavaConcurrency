package Problems.S00_General.P06_ThreadPoolWithShutdown;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A bounded blocking queue, built from scratch on a Lock + two Conditions -- the same monitor
 * idiom as the bounded-buffer solutions elsewhere in this repo (Dining Savages, etc.), not
 * java.util.concurrent.BlockingQueue.
 */
public class MyBlockingQueue<T> {

    private final Queue<T> queue = new LinkedList<>();
    private final int capacity;

    private final Lock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    public MyBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
    }

    public void put(T item) throws InterruptedException {
        lock.lock();
        try {
            while (queue.size() == capacity) {
                notFull.await();
            }
            queue.add(item);
            notEmpty.signal(); // exactly one item added -> exactly one waiting take() can proceed
        } finally {
            lock.unlock();
        }
    }

    public T take() throws InterruptedException {
        lock.lock();
        try {
            while (queue.isEmpty()) {
                notEmpty.await();
            }
            T item = queue.poll();
            notFull.signal(); // exactly one slot freed -> exactly one waiting put() can proceed
            return item;
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

    /** Empties the queue and returns everything that was in it, without blocking. Used by shutdownNow(). */
    public List<T> drainAll() {
        lock.lock();
        try {
            List<T> drained = new ArrayList<>(queue);
            queue.clear();
            notFull.signalAll(); // there's room now -- wake every blocked put()
            return drained;
        } finally {
            lock.unlock();
        }
    }
}
