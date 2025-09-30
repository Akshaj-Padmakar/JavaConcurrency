package Problems.S03_NotSoClassical.P01_SearchInsertDeleteList;


import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Three-way mutual exclusion but with single public methods:
 *   boolean search(K key)
 *   void insert(V value)
 *   boolean delete(K key)
 *
 * Internals:
 * - many searchers concurrently
 * - one inserter at a time (inserterActive), but inserter may run with searchers
 * - one deleter at a time, exclusive with both searchers and inserters
 * - deleters have priority (waitingDeleters > 0 blocks new searchers/inserters)
 */
public class SearchInsertDeleteList<E> {
    private static class Node<E> {
        E value; Node<E> next;
        Node(E v){ value = v; next = null; }
    }
    private Node<E> head = null;

    // concurrency state & primitives
    private final ReentrantLock lock = new ReentrantLock(true); // fair optional
    private final Condition canSearch = lock.newCondition();
    private final Condition canInsert = lock.newCondition();
    private final Condition canDelete = lock.newCondition();

    private int activeSearchers = 0;
    private boolean inserterActive = false;
    private int waitingDeleters = 0;
    private boolean deleterActive = false;

    private final Random rnd = new Random();

    // -------------------- public combined methods --------------------

    /**
     * Search for key in the list. Many searchers allowed concurrently.
     * Blocks if a deleter is active or waiting (deleter priority).
     */
    public boolean search(E key) throws InterruptedException {
        // entry protocol
        lock.lock();
        try {
            while (deleterActive || waitingDeleters > 0) {
                canSearch.await();
            }
            activeSearchers++;
        } finally {
            lock.unlock();
        }

        // do the actual search outside the lock so searchers run concurrently with other searchers and inserters
        try {
            return doSearch(key);
        } finally {
            // exit protocol
            lock.lock();
            try {
                activeSearchers--;
                if (activeSearchers == 0 && waitingDeleters > 0) {
                    canDelete.signal();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Insert value at list tail. Only one inserter at a time.
     * Inserter may run concurrently with searchers but not with deleters.
     */
    public void insert(E value) throws InterruptedException {
        // entry
        lock.lock();
        try {
            while (deleterActive || waitingDeleters > 0 || inserterActive) {
                canInsert.await();
            }
            inserterActive = true;
        } finally {
            lock.unlock();
        }

        // perform insertion outside lock (safe w.r.t searches)
        try {
            doInsert(value);
        } finally {
            // exit
            lock.lock();
            try {
                inserterActive = false;
                if (waitingDeleters > 0) {
                    canDelete.signal();
                } else {
                    // let one inserter (if any) go, and wake searchers
                    canInsert.signal();
                    canSearch.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Delete first occurrence of key. Exclusive operation:
     * no other deleters, no searchers, no inserters while deleting.
     */
    public boolean delete(E key) throws InterruptedException {
        // entry
        lock.lock();
        try {
            waitingDeleters++;
            while (deleterActive || activeSearchers > 0 || inserterActive) {
                canDelete.await();
            }
            waitingDeleters--;
            deleterActive = true;
        } finally {
            lock.unlock();
        }

        // perform deletion (exclusive)
        try {
            return doDelete(key);
        } finally {
            // exit
            lock.lock();
            try {
                deleterActive = false;
                if (waitingDeleters > 0) {
                    // wake next deleter
                    canDelete.signal();
                } else {
                    // no waiting deleters -> allow inserters and searchers
                    canInsert.signal();
                    canSearch.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    // -------------------- internal list operations (no locking here) --------------------

    // return true if found
    private boolean doSearch(E key) {
        Node<E> cur = head;
        while (cur != null) {
            if ((cur.value == null && key == null) || (cur.value != null && cur.value.equals(key))) return true;
            cur = cur.next;
        }
        // small delay to make thread interleavings observable in demo
        try { Thread.sleep(5 + rnd.nextInt(10)); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        return false;
    }

    // append to tail
    private void doInsert(E value) {
        Node<E> n = new Node<>(value);
        if (head == null) {
            head = n;
            return;
        }
        Node<E> cur = head;
        while (cur.next != null) cur = cur.next;
        cur.next = n;
        try { Thread.sleep(10 + rnd.nextInt(20)); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    // delete first occurrence; return true if removed
    private boolean doDelete(E key) {
        Node<E> prev = null, cur = head;
        while (cur != null) {
            if ((cur.value == null && key == null) || (cur.value != null && cur.value.equals(key))) {
                if (prev == null) head = cur.next;
                else prev.next = cur.next;
                try { Thread.sleep(15 + rnd.nextInt(25)); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                return true;
            }
            prev = cur; cur = cur.next;
        }
        try { Thread.sleep(10 + rnd.nextInt(20)); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        return false;
    }

    // -------------------- simple demo harness --------------------
    public static void main(String[] args) throws InterruptedException {
        final SearchInsertDeleteList<Integer> list = new SearchInsertDeleteList<>();
        final int S = 5, I = 3, D = 2;

        Thread[] searchers = new Thread[S];
        Thread[] inserters = new Thread[I];
        Thread[] deleters = new Thread[D];

        for (int i = 0; i < S; i++) {
            final int id = i;
            searchers[i] = new Thread(() -> {
                try {
                    for (int k = 0; k < 20; k++) {
                        boolean found = list.search(k % 10);
                        System.out.printf("Search-%d found(%d)=%b activeSearchers=%d%n", id, k % 10, found, list.activeSearchers);
                        Thread.sleep(30 + list.rnd.nextInt(40));
                    }
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }, "Searcher-" + i);
        }

        for (int i = 0; i < I; i++) {
            final int id = i;
            inserters[i] = new Thread(() -> {
                try {
                    for (int k = 0; k < 10; k++) {
                        int val = id * 100 + k;
                        list.insert(val);
                        System.out.printf("Inserter-%d inserted %d (inserterActive=%b)%n", id, val, list.inserterActive);
                        Thread.sleep(50 + list.rnd.nextInt(80));
                    }
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }, "Inserter-" + i);
        }

        for (int i = 0; i < D; i++) {
            final int id = i;
            deleters[i] = new Thread(() -> {
                try {
                    for (int k = 0; k < 6; k++) {
                        boolean removed = list.delete(k); // try delete small numbers
                        System.out.printf("Deleter-%d removed(%d)=%b deleterActive=%b%n", id, k, removed, list.deleterActive);
                        Thread.sleep(120 + list.rnd.nextInt(150));
                    }
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }, "Deleter-" + i);
        }

        for (Thread t : searchers) t.start();
        for (Thread t : inserters) t.start();
        for (Thread t : deleters) t.start();

        for (Thread t : searchers) t.join();
        for (Thread t : inserters) t.join();
        for (Thread t : deleters) t.join();

        System.out.println("Done.");
    }
}
