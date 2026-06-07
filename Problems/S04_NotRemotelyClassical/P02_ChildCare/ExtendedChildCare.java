package Problems.S04_NotRemotelyClassical.P02_ChildCare;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/*
* In this problem there are 2 school of thoughts:
    1. Allow adults to exit over allowing new child entry:
    -----------------------------------------------------
        * Adult threads would never stave.
        * And once requested to leave, they will eventually leave.
        * chaning the child entry condition fro,
            * childCnt < 3 * adultCnt => childCnt < 3 * (adultCnt - adultWaitingToLeave)
            * Once adult wants to leave don't allow new children...
    
    
    2. Allow children to enter whenever possible
    --------------------------------------------
        * Current solution do this...
        * Better utilization of day care center....
        * But adult threads can starve -> Thoese wanting to leave may never be able to leave...
    
 */
public class ExtendedChildCare {

    private final int N; // Number of adults.
    private final int M; // Number of childern.

    public ExtendedChildCare(int N, int M) {
        this.N = N;
        this.M = M;
    }

    private final Lock lock = new ReentrantLock();
    private final Queue<Node> adultExitQueue = new LinkedList<>();
    private final Queue<Node> childEntryQueue = new LinkedList<>();

    private int adultCnt = 0;
    private int childCnt = 0;

    private static final int CHILD_TO_ADULT_RATIO = 3;

    private Random rnd = new Random();

    private class Node {
        private final int id;
        private final String type;
        private final Condition condition;

        public Node(int id, String type) {
            this.id = id;
            this.type = type;
            this.condition = lock.newCondition();
        }

        public int getId() {
            return id;
        }

        public String getType() {
            return type;
        }

        public Condition getCondition() {
            return condition;
        }
    }

    public abstract class ChildCareRunnable implements Runnable {
        public Node node;

        public ChildCareRunnable(int id, String type) {
            this.node = new Node(id, type);
        }

        @Override
        public void run() {
            enter();

            stay();

            exit();
        }

        abstract void enter();

        abstract void stay();

        abstract void exit();

        public void signalExitingAdultNode() {
            Node exitingAdultNode = adultExitQueue.peek();
            exitingAdultNode.getCondition().signal();
        }

        public void singalKChildNode(int k) {
            if (k > 1) {
                List<Node> signalChildren = peekFirstK(childEntryQueue, k);
                for (Node child : signalChildren) {
                    child.getCondition().signal();
                }
            } else if (k == 1) {
                Node childEntryNode = childEntryQueue.peek();
                childEntryNode.getCondition().signal();
            }
        }

        private void logChildCareClosed() {
            System.out.println("Child-Care is closed now...");
        }

        private void logEntry() {
            System.out.println(this.node.getType() + "-" + this.node.getId()
                    + " has entered the Child care, AdultCnt = " + adultCnt
                    + ", ChildCnt = " + childCnt);
        }

        private void logExit() {
            System.out.println(this.node.getType() + "-" + this.node.getId()
                    + " has exited the Child care, AdultCnt = " + adultCnt
                    + ", ChildCnt = " + childCnt);
        }

    }

    public class AdultRunnable extends ChildCareRunnable {

        public AdultRunnable(int id) {
            super(id, "Adult");
        }

        @Override
        public void enter() {
            boolean locked = false;
            try {
                lock.lockInterruptibly();
                locked = true;
                adultCnt++;
                if (childEntryQueue.size() > 0) { // Prefer child entry -> Increase day care efficiency.
                    singalKChildNode(CHILD_TO_ADULT_RATIO);
                }
                if (adultExitQueue.size() > 0) {
                    signalExitingAdultNode();
                }
                super.logEntry();
            } catch (InterruptedException ex) {
                super.logChildCareClosed();
            } finally {
                if (locked) {
                    lock.unlock();
                }
            }
        }

        @Override
        public void stay() {
            try {
                Thread.sleep(rnd.nextInt(300));
            } catch (InterruptedException ex) {
                super.logChildCareClosed();
            }
        }

        @Override
        public void exit() {
            boolean locked = false;
            try {
                lock.lockInterruptibly();
                locked = true;
                adultExitQueue.add(this.node);
                while (CHILD_TO_ADULT_RATIO * (adultCnt - 1) < childCnt) {
                    this.node.getCondition().await();
                }
                adultExitQueue.poll();
                adultCnt--;
                super.logExit();
            } catch (InterruptedException ex) {
                super.logChildCareClosed();
            } finally {
                if (locked) {
                    lock.unlock();
                }
            }
        }
    }

    public class ChildRunnable extends ChildCareRunnable {
        public ChildRunnable(int id) {
            super(id, "Child");
        }

        @Override
        public void enter() {
            boolean locked = false;
            try {
                lock.lockInterruptibly();
                locked = true;
                childEntryQueue.add(this.node);
                while (childCnt + 1 > CHILD_TO_ADULT_RATIO * adultCnt) {
                    this.node.getCondition().await();
                }
                childEntryQueue.poll();
                childCnt++;
                super.logEntry();
            } catch (InterruptedException ex) {
                super.logChildCareClosed();
            } finally {
                if (locked) {
                    lock.unlock();
                }
            }
        }

        @Override
        public void stay() {
            try {
                Thread.sleep(rnd.nextInt(300));
            } catch (InterruptedException ex) {
                super.logChildCareClosed();
            }
        }

        @Override
        public void exit() {
            boolean locked = false;
            try {
                lock.lockInterruptibly();
                locked = true;
                childCnt--;

                if (childEntryQueue.size() > 0) {
                    singalKChildNode(1);
                }
                if (adultExitQueue.size() > 0) {
                    signalExitingAdultNode();
                }

                super.logExit();
            } catch (InterruptedException ex) {
                super.logChildCareClosed();
            } finally {
                if (locked) {
                    lock.unlock();
                }
            }
        }

    }

    private List<Node> peekFirstK(Queue<Node> queue, int k) {
        List<Node> ret = new ArrayList<>();
        Iterator<Node> it = queue.iterator();
        int i = 0;
        while (i < k && it.hasNext()) {
            ret.add(it.next());
            i++;
        }
        return ret;
    }

    public void solve() throws InterruptedException {
        List<Thread> adultThreads = new ArrayList<>();
        List<Thread> childThreads = new ArrayList<>();

        for (int i = 0; i < this.N; i++) {
            adultThreads.add(new Thread(new AdultRunnable(i), "Adult-Thread-" + i));
        }

        for (int i = 0; i < this.M; i++) {
            childThreads.add(new Thread(new ChildRunnable(i), "Child-Thread-" + i));
        }

        for (Thread t : adultThreads) {
            t.start();
        }

        for (Thread t : childThreads) {
            t.start();
        }

        // for(Thread t : adultThreads) {
        // t.join();
        // }
        // for(Thread t : childThreads) {
        // t.join();
        // }

        Thread.sleep(5000);

        for (Thread t : adultThreads) {
            t.interrupt();
        }
        for (Thread t : childThreads) {
            t.interrupt();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        int N = 2;
        int M = 12;
        new ExtendedChildCare(N, M).solve();
    }

}
