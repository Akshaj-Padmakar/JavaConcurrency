package Problems.S03_NotSoClassical.P01_SearchInsertDeleteList;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * FAIR (FIFO) Search-Insert-Delete lock: no role can starve.
 * <p>
 * One FIFO queue of requests. grant() walks the queue FROM THE FRONT, admitting
 * each
 * request that is compatible with the currently-active set, and STOPS at the
 * first
 * incompatible one. Stopping at the first blocker is what enforces fairness: a
 * later
 * request can never overtake an earlier one it is incompatible with.
 * <p>
 * Compatibility: search+search ok, search+insert ok, everything with delete is
 * exclusive,
 * insert+insert exclusive.
 */
public class FairSearchInsertDeleteLock {
    private enum TYPE {
        SEARCH, INSERT, DELETE;
    }

    private final Lock lock = new ReentrantLock();
    private final Queue<Node> queue = new LinkedList<>();

    private int activeSearchers = 0;
    private boolean activeInserter = false;
    private boolean activeDeleter = false;

    private static final class Node {
        private final TYPE type;
        private final Condition condition;
        private boolean granted = false;

        public Node(TYPE type, Condition condition) {
            this.type = type;
            this.condition = condition;
        }

        public TYPE getType() {
            return this.type;
        }

        public Condition getCondition() {
            return this.condition;
        }

        public boolean getGranted() {
            return this.granted;
        }

        public void setGranted(boolean value) {
            this.granted = value;
        }
    }

    private void addActive(TYPE type) {
        if (type == TYPE.SEARCH) {
            activeSearchers++;
        } else if (type == TYPE.INSERT) {
            activeInserter = true;
        } else {
            activeDeleter = true;
        }
    }

    private boolean compatible(TYPE type) {
        if (type == TYPE.SEARCH) {
            return !activeDeleter;
        } else if (type == TYPE.INSERT) {
            return !activeInserter && !activeDeleter;
        } else {
            return activeSearchers == 0 && !activeInserter && !activeDeleter;
        }
    }

    private void grant() {
        while (!queue.isEmpty()) {
            Node head = queue.peek();
            if (!compatible(head.getType())) {
                break;
            }
            queue.poll();
            addActive(head.getType());
            head.setGranted(true);
            head.getCondition().signal();
        }
    }


    private void enter(TYPE type) throws InterruptedException {
        lock.lock();
        try {
            Node node = new Node(type, lock.newCondition());
            queue.add(node);
            grant();
            while (!node.getGranted()) {
                try {
                    node.getCondition().await();
                } catch (InterruptedException ex) {
                    if (node.getGranted()) { // Interrupted but granted...
                        Thread.currentThread().interrupt();
                        return;
                    }
                    queue.remove(node);
                    grant();
                    throw ex;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void exit(TYPE type) {
        lock.lock();
        try {
            if (type == TYPE.SEARCH) {
                activeSearchers--;
            } else if (type == TYPE.INSERT) {
                activeInserter = false;
            } else {
                activeDeleter = false;
            }
            grant();
        } finally {
            lock.unlock();
        }
    }

    public void lockSearch() throws InterruptedException {
        enter(TYPE.SEARCH);
    }

    public void unlockSearch() {
        exit(TYPE.SEARCH);
    }

    public void lockInsert() throws InterruptedException {
        enter(TYPE.INSERT);
    }

    public void unlockInsert() {
        exit(TYPE.INSERT);
    }

    public void lockDelete() throws InterruptedException {
        enter(TYPE.DELETE);
    }

    public void unlockDelete() {
        exit(TYPE.DELETE);
    }
}
