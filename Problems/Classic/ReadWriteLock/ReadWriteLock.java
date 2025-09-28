package Problems.Classic.ReadWriteLock;

import java.util.HashMap;
import java.util.Map;

/* 
 * Conditions for Lock:
    * as many readers as possible can acquire the lock.
    * only single writer can acquire the lock.
    * writers are preffered over readers, so if a writer thread comes, no more readers would allowed. The already onging readers would be complete to complete the read.
    * lock is reentrant from writer to reader
    * lock is reentrant from reader to writer(if there is only 1 reader)
*/

public class ReadWriteLock {
    private final Map<Thread, Integer> readingThread;
    private int writingRequest = 0;
    private int writeCnt = 0;
    private Thread writer = null;

    public ReadWriteLock() {
        this.readingThread = new HashMap<>();
    }

    public synchronized void readLock() throws InterruptedException {
        Thread thread = Thread.currentThread();

        while(!grantReadAccess(thread)) {
            this.wait();
        }

        Integer cnt = readingThread.get(thread);
        if(cnt == null) {
            cnt = 0;
        }
        cnt++;
        readingThread.put(thread, cnt);
    }

    public synchronized void readUnlock() throws IllegalMonitorStateException {
        Thread thread = Thread.currentThread();

        Integer cnt = readingThread.get(thread);
        if(cnt == null) {
            throw new IllegalMonitorStateException("This thread doesnt hold the readLock, cannot unlock !");
        }
        cnt--;
        if(cnt == 0) {
            readingThread.remove(thread);
            this.notifyAll();
        } else {
            readingThread.put(thread, cnt);
        }
    }

    public synchronized void writeLock() throws InterruptedException {
        Thread thread = Thread.currentThread();
        writingRequest++;
        while(!grantWriteAccess(thread)) {
            this.wait();

        }
        writingRequest--;
        writer = thread;
        writeCnt++;
    }

    public synchronized void writeUnlock() throws IllegalMonitorStateException {
        Thread thread = Thread.currentThread();
        if(writer != thread) {
            throw new IllegalMonitorStateException("This thread doesnt hold the write lock");
        }

        writeCnt--;
        if(writeCnt == 0) {
            writer = null;
            this.notifyAll();
        }
    }

    private boolean grantReadAccess(Thread thread) {
        if(writer != null) { // writer-to-read reentrance
            return writer == thread;
        } else {
            if(readingThread.get(thread) != null) { // read-to-read reentrance
                return true;
            } else if(writingRequest > 0) {
                return false;
            }

            return true;
        }
    }

    private boolean grantWriteAccess(Thread thread) {
        if(writer != null) {
            return writer == thread; // writer-to-write reentrance
        } else {
            if(readingThread.size() == 0) {
                return true;
            } else if(readingThread.size() == 1) { // read-to-write reentrance[only when this is the only reading thread]
                return readingThread.containsKey(thread);
            } else {
                return false;
            }
        }
    }

    
    public static void main(String[] args) throws InterruptedException {

    }

}
