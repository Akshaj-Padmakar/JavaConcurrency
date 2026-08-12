package Problems.S05_SystemCoding.P04_RangeLock;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class RangeLock {

    private final Lock lock = new ReentrantLock();
    private final Condition acquireCondition = lock.newCondition();
    private final Set<Entry> waiting = new HashSet<>();
    private final Set<Entry> granted = new HashSet<>();

    public Handle acquire(long start, long end, boolean exclusive) throws InterruptedException {
        validate(start, end);

        Entry entry = new Entry(start, end, exclusive);
        lock.lock();
        try {
            waiting.add(entry);
            try {
                while (!isAllowed(entry)) {
                    acquireCondition.await();
                }
            } catch (InterruptedException ex) {
                waiting.remove(entry);
                acquireCondition.signalAll();
                throw ex;
            }
            waiting.remove(entry);
            granted.add(entry);
        } finally {
            lock.unlock();
        }

        return new Handle() {
            private boolean closed = false;

            @Override
            public void close() {
                if (closed) { // Idempotent API.
                    return;
                }
                closed = true;
                release(entry);
            }
        };
    }

    private void release(Entry entry) {
        lock.lock();
        try {
            granted.remove(entry);
            acquireCondition.signalAll();
        } finally {
            lock.unlock();
        }
    }


    private boolean isAllowed(Entry entry) {
        for (Entry e : granted) {
            if (conflict(e, entry)) {
                return false;
            }
        }
        if (!entry.getExclusive()) {
            for (Entry e : waiting) {
                if (e != entry && e.getExclusive() && overlaps(e, entry)) {
                    // anyone wants an exclusive entry and already in waiting set, allow that...
                    return false;
                }
            }
        }
        return true;
    }

    private boolean conflict(Entry a, Entry b) {
        return overlaps(a, b) && (a.getExclusive() || b.getExclusive());
    }

    private boolean overlaps(Entry a, Entry b) {
        return (a.getStart() >= b.getStart() && a.getEnd() <= b.getEnd())
                || (a.getStart() <= b.getStart() && a.getEnd() >= b.getStart())
                || (a.getStart() <= b.getEnd() && a.getEnd() >= b.getEnd());
    }


    private void validate(long start, long end) {
        if (start > end || start < 0) {
            throw new IllegalArgumentException("Invalid range passed.");
        }
    }

    private static final class Entry {
        private final long start;
        private final long end;
        private final boolean exclusive;

        private Entry(long start, long end, boolean exclusive) {
            this.start = start;
            this.end = end;
            this.exclusive = exclusive;
        }

        public long getStart() {
            return this.start;
        }

        public long getEnd() {
            return this.end;
        }

        public boolean getExclusive() {
            return this.exclusive;
        }

        /**
         * Deliberately does NOT override equals/hashCode. Two threads can hold the same range in shared
         * mode; those are distinct grants that must be tracked and released independently. Value equality
         * would collapse them and one close() would free both.
         */
    }

    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }

}
