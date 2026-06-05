package Problems.S03_NotSoClassical.P01_SearchInsertDeleteList;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SearchInsertDeleteLock {

    private final Lock lock = new ReentrantLock();
    private final Condition searchCondition = lock.newCondition();
    private final Condition insertCondition = lock.newCondition();
    private final Condition deleterCondition = lock.newCondition();

    private int waitingSearchers = 0;
    private int waitingInserters = 0;
    private int waitingDeleters = 0;

    private int activeSearchers = 0;
    private boolean inserterActive = false;
    private boolean deleterActive = false;

    public void searchEnter() throws InterruptedException {
        lock.lock();
        try {
            waitingSearchers++;
            while (deleterActive || waitingDeleters > 0) {
                searchCondition.await();
            }
            waitingSearchers--;
            activeSearchers++;
        } finally {
            lock.unlock();
        }
    }

    public void searchExit() {
        lock.lock();
        try {
            activeSearchers--;
            if (activeSearchers == 0 && waitingDeleters > 0) {
                deleterCondition.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    public void insertEnter() throws InterruptedException {
        lock.lock();
        try {
            waitingInserters++;
            while (inserterActive || deleterActive || waitingDeleters > 0) {
                insertCondition.await();
            }
            waitingInserters--;
            inserterActive = true;
        } finally {
            lock.unlock();
        }
    }

    public void insertExit() {
        lock.lock();
        try {
            inserterActive = false;
            if (waitingDeleters > 0) {
                deleterCondition.signal();
            } else if (waitingInserters > 0) {
                insertCondition.signal();
                // searchCondition.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    public void deleteEnter() throws InterruptedException {
        lock.lock();
        try {
            waitingDeleters++;
            while (activeSearchers > 0 || inserterActive || deleterActive) {
                deleterCondition.await();
            }
            waitingDeleters--;
            deleterActive = true;
        } finally {
            lock.unlock();
        }
    }

    public void deleteExit() {
        lock.lock();
        try {
            deleterActive = false;
            if (waitingDeleters > 0) {
                deleterCondition.signal();
            } else {
                if (waitingInserters > 0) {
                    insertCondition.signal();
                }
                if (waitingSearchers > 0) {
                    searchCondition.signalAll();
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
