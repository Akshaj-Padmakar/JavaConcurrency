package Problems.S03_NotSoClassical.P01_SearchInsertDeleteList;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * FAIR (FIFO) Search-Insert-Delete lock: no role can starve.
 *
 * One FIFO queue of requests. grant() walks the queue FROM THE FRONT, admitting
 * each
 * request that is compatible with the currently-active set, and STOPS at the
 * first
 * incompatible one. Stopping at the first blocker is what enforces fairness: a
 * later
 * request can never overtake an earlier one it is incompatible with.
 *
 * Compatibility: search+search ok, search+insert ok, everything with delete is
 * exclusive,
 * insert+insert exclusive.
 */
public class FairSearchInsertDeleteLock {
    private enum Type {
        SEARCH, INSERT, DELETE
    }

    private static final class Waiter {
        final Type type;
        final Condition cond;
        boolean granted = false;

        Waiter(Type type, Condition cond) {
            this.type = type;
            this.cond = cond;
        }
    }

    private final Lock lock = new ReentrantLock();
    private final Deque<Waiter> queue = new ArrayDeque<>();

    private int activeSearchers = 0;
    private boolean activeInserter = false;
    private boolean activeDeleter = false;

    private boolean compatible(Type t) {
        switch (t) {
            case SEARCH:
                return !activeDeleter; // ok with searchers + one inserter
            case INSERT:
                return !activeDeleter && !activeInserter; // ok with searchers, not another inserter
            case DELETE:
                return !activeDeleter && !activeInserter && activeSearchers == 0; // alone
            default:
                return false;
        }
    }

    private void addActive(Type t) {
        if (t == Type.SEARCH)
            activeSearchers++;
        else if (t == Type.INSERT)
            activeInserter = true;
        else
            activeDeleter = true;
    }

    // Must hold lock. Grant a run of compatible requests from the FRONT; stop at
    // first blocker.
    private void grant() {
        while (!queue.isEmpty()) {
            Waiter head = queue.peekFirst();
            if (!compatible(head.type))
                break; // fairness: don't let anyone behind overtake
            queue.pollFirst();
            addActive(head.type);
            head.granted = true;
            head.cond.signal();
        }
    }

    private void enter(Type t) throws InterruptedException {
        lock.lock();
        try {
            Waiter w = new Waiter(t, lock.newCondition());
            queue.addLast(w);
            grant(); // maybe grant immediately (if at front & compatible)
            while (!w.granted) {
                try {
                    w.cond.await();
                } catch (InterruptedException ie) {
                    if (!w.granted) { // interrupted before being granted: remove ghost, unblock others
                        queue.remove(w);
                        grant();
                    }
                    throw ie;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void exit(Type t) {
        lock.lock();
        try {
            if (t == Type.SEARCH)
                activeSearchers--;
            else if (t == Type.INSERT)
                activeInserter = false;
            else
                activeDeleter = false;
            grant(); // an exit may unblock the queue head
        } finally {
            lock.unlock();
        }
    }

    public void searchLock() throws InterruptedException {
        enter(Type.SEARCH);
    }

    public void searchUnlock() {
        exit(Type.SEARCH);
    }

    public void insertLock() throws InterruptedException {
        enter(Type.INSERT);
    }

    public void insertUnlock() {
        exit(Type.INSERT);
    }

    public void deleteLock() throws InterruptedException {
        enter(Type.DELETE);
    }

    public void deleteUnlock() {
        exit(Type.DELETE);
    }
}
