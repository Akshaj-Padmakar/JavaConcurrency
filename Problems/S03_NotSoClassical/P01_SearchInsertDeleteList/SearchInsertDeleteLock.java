package Problems.S03_NotSoClassical.P01_SearchInsertDeleteList;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SearchInsertDeleteLock {

    private final Lock lock;
    private final Condition searchWaitCondition;
    private final Condition insertWaitCondition;
    private final Condition deleteWaitCondition;

    private int waitingSearchers = 0;
    private int waitingInserters = 0;
    private int waitingDeleters = 0;

    private int activeSearchers = 0;
    private boolean activeInserters = false;
    private boolean activeDeleters = false;

    public SearchInsertDeleteLock() {
        this(false);
    }

    public SearchInsertDeleteLock(boolean fair) {
        this.lock = new ReentrantLock(fair);
        this.searchWaitCondition = this.lock.newCondition();
        this.insertWaitCondition = this.lock.newCondition();
        this.deleteWaitCondition = this.lock.newCondition();
    }


    public void lockSearch() throws InterruptedException {
        lock.lock();
        try {
            waitingSearchers++;
            try {
                while (activeDeleters || waitingDeleters > 0) {
                    searchWaitCondition.await();
                }
            } catch (InterruptedException ex) {
                waitingSearchers--;
                throw ex;
            }
            waitingSearchers--;
            activeSearchers++;
        } finally {
            lock.unlock();
        }
    }

    public void unlockSearch() {
        lock.lock();
        try {
            activeSearchers--;
            if (activeSearchers == 0 && waitingDeleters > 0) {
                deleteWaitCondition.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    public void lockInsert() throws InterruptedException {
        lock.lock();
        try {
            waitingInserters++;
            try {
                while (activeInserters || activeDeleters || waitingDeleters > 0) { // prefer deleter
                    insertWaitCondition.await();
                }
            } catch (InterruptedException ex) {
                waitingInserters--;
                throw ex;
            }
            waitingInserters--;
            activeInserters = true;
        } finally {
            lock.unlock();
        }
    }

    public void unlockInsert() {
        lock.lock();
        try {
            activeInserters = false;
            if (waitingDeleters > 0) {
                deleteWaitCondition.signal();
            } else if (waitingInserters > 0) {
                insertWaitCondition.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    public void lockDelete() throws InterruptedException {
        lock.lock();
        try {
            waitingDeleters++;
            try {
                while (activeDeleters || activeInserters || activeSearchers > 0) {
                    deleteWaitCondition.await();
                }
            } catch (InterruptedException ex) {
                waitingDeleters--;
                if (waitingDeleters == 0) {
                    if (waitingInserters > 0) insertWaitCondition.signal();
                    if (waitingSearchers > 0) searchWaitCondition.signalAll();
                }
                throw ex;
            }
            waitingDeleters--;
            activeDeleters = true;
        } finally {
            lock.unlock();
        }
    }

    public void unlockDelete() {
        lock.lock();
        try {
            activeDeleters = false;
            if (waitingDeleters > 0) {
                deleteWaitCondition.signal();
            } else {
                if (waitingInserters > 0) {
                    insertWaitCondition.signal();
                }
                if (waitingSearchers > 0) {
                    searchWaitCondition.signalAll();
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
