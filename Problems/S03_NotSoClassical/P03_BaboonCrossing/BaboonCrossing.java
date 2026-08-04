package Problems.S03_NotSoClassical.P03_BaboonCrossing;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BaboonCrossing {
    private final int N;// Left-Going Babon
    private final int M;// Right-Going Babon

    private Lock lock = new ReentrantLock();

    private Queue<Node> leftBabonWaitingList = new LinkedList<>();
    private Queue<Node> rightBabonWaitingList = new LinkedList<>();

    private DIR currentDir = DIR.NONE;
    private int currentDirCnt = 0;
    private int onRopeCnt = 0;
    private final int BATCH_SIZE = 8;
    private final int MAX_CAPACITY = 5;

    private Random rnd = new Random();

    public BaboonCrossing(int N, int M) {
        this.N = N;
        this.M = M;
    }

    private enum DIR {
        LEFT, RIGHT, NONE;
    }

    private class Node {
        private final int id;
        private final DIR dir;
        private final Condition condition;

        private Node(int id, DIR dir) {
            this.id = id;
            this.dir = dir;
            this.condition = lock.newCondition();
        }

        private int getId() {
            return this.id;
        }

        private DIR getDir() {
            return this.dir;
        }

        private Condition getCondition() {
            return this.condition;
        }
    }

    private class BabonRunnable implements Runnable {
        private final Node node;

        private BabonRunnable(int id, DIR dir) {
            this.node = new Node(id, dir);
        }

        private int f() {
            return 300 + rnd.nextInt(100);
        }

        @Override
        public void run() {
            enter();

            travel();

            exit();
        }

        private void enter() {
            lock.lock();
            try {
                Queue<Node> currentWaitingList = getCurrentDirWaitingList();
                currentWaitingList.add(this.node);
                while (!allow()) {
                    this.node.getCondition().await();
                }

                enterInside();
                currentWaitingList.remove(this.node);
                logRopeEntry();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }

        private void exit() {
            lock.lock();
            logRopeExit();
            onRopeCnt--;
            try {
                if (currentDirCnt == BATCH_SIZE) {
                    if (onRopeCnt == 0) {
                        if (getOppositeDirWaitingList().size() > 0) {
                            signalOppositeDirBabon();
                        } else {
                            signalCurDirBabon();
                        }
                    } else {
                        // wait for last babon with this DIR to exit
                    }
                } else {
                    if (getCurrentDirWaitingList().size() > 0) {
                        Node node = getCurrentDirWaitingList().peek();
                        node.getCondition().signal();
                    } else {
                        if (onRopeCnt == 0) {
                            signalOppositeDirBabon();
                        }
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        private void travel() {
            try {
                Thread.sleep(f());
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }

        private boolean allow() {
            if (currentDir == DIR.NONE) {
                return true;
            } else if (this.node.getDir() == currentDir
                    && currentDirCnt < BATCH_SIZE
                    && onRopeCnt < MAX_CAPACITY) {
                return true;
            }
            return false;
        }

        private Queue<Node> getCurrentDirWaitingList() {
            return this.node.getDir() == DIR.LEFT ? leftBabonWaitingList : rightBabonWaitingList;
        }

        private Queue<Node> getOppositeDirWaitingList() {
            return this.node.getDir() == DIR.LEFT ? rightBabonWaitingList : leftBabonWaitingList;
        }

        private void signalOppositeDirBabon() {
            resetRope();
            List<Node> list = peekFirstK(getOppositeDirWaitingList(), MAX_CAPACITY);
            for (Node node : list) {
                node.getCondition().signal();
            }
        }

        private void signalCurDirBabon() {
            resetRope();
            List<Node> list = peekFirstK(getCurrentDirWaitingList(), MAX_CAPACITY);
            for (Node node : list) {
                node.getCondition().signal();
            }
        }

        private void enterInside() {
            currentDir = this.node.getDir();
            currentDirCnt++;
            onRopeCnt++;
        }

        private void resetRope() {
            currentDir = DIR.NONE;
            currentDirCnt = 0;
        }

        private List<Node> peekFirstK(Queue<Node> queue, int k) {
            List<Node> list = new ArrayList<>();
            if (queue == null) {
                return list;
            }
            int cur = 0;
            for (Node node : queue) {
                if (cur >= k) {
                    break;
                }
                list.add(node);
                cur++;
            }
            return list;
        }

        private void logRopeEntry() {
            System.out
                    .println("Babon-" + this.node.getId() + ", dir = " + this.node.getDir() + " has entered the rope.");
        }

        private void logRopeExit() {
            System.out.println(
                    "Babon-" + this.node.getId() + ", dir = " + this.node.getDir() + " is now exiting the rope.");
        }
    }

    private class LeftBabonRunnable extends BabonRunnable {
        private LeftBabonRunnable(int id) {
            super(id, DIR.LEFT);
        }
    }

    private class RightBabonRunnable extends BabonRunnable {
        private RightBabonRunnable(int id) {
            super(id, DIR.RIGHT);
        }
    }

    public void solve() throws InterruptedException {
        List<Thread> leftBabonThreads = new ArrayList<>();
        List<Thread> rightBabonThreads = new ArrayList<>();
        for (int i = 0; i < this.N; i++) {
            leftBabonThreads.add(new Thread(new LeftBabonRunnable(i), "Left-Babon-Thread-" + i));
        }

        for (int i = 0; i < this.M; i++) {
            rightBabonThreads.add(new Thread(new RightBabonRunnable(i), "Right-Babon-Thread-" + i));
        }

        for (Thread t : leftBabonThreads) {
            t.start();
        }
        for (Thread t : rightBabonThreads) {
            t.start();
        }

        for (Thread t : leftBabonThreads) {
            t.join();
        }
        for (Thread t : rightBabonThreads) {
            t.join();
        }
    }

    public static void main(String args[]) throws InterruptedException {
        new BaboonCrossing(10, 10).solve();
    }

}
