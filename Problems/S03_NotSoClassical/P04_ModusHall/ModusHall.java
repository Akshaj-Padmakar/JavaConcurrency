package Problems.S03_NotSoClassical.P04_ModusHall;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ModusHall {
    private final int N; // Number of heathen
    private final int M; // Number of prude

    private final Lock lock = new ReentrantLock();

    private int heathenCnt = 0;
    private int prudeCnt = 0;

    private int onPathCnt = 0;

    private TYPE currentType = TYPE.NONE;

    private List<Node> heathenWaitingList = new ArrayList<>();
    private List<Node> prudeWaitingList = new ArrayList<>();

    private final Random rnd = new Random();

    public ModusHall(int N, int M) {
        this.N = N;
        this.M = M;
    }

    private enum TYPE {
        NONE, HEATHEN, PRUDE;
    }

    private class Node {
        private final int id;
        private final TYPE type;
        private final Condition condition;

        private Node(int id, TYPE type) {
            this.id = id;
            this.type = type;
            this.condition = lock.newCondition();
        }

        public int getId() {
            return this.id;
        }

        public TYPE getType() {
            return this.type;
        }

        public Condition getCondition() {
            return this.condition;
        }
    }

    private class CrosserRunnable implements Runnable {
        private final Node node;

        private CrosserRunnable(int id, TYPE type) {
            this.node = new Node(id, type);
            if (type != TYPE.HEATHEN && type != TYPE.PRUDE) {
                throw new IllegalStateException("Type can be Heathen/Prude only.");
            }
        }

        @Override
        public void run() {
            wander();

            enter();

            travel();

            exit();
        }

        private void wander() {
            try {
                Thread.sleep(rnd.nextInt(200));
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }

        private void enter() {
            lock.lock();
            try {
                incrementCrosserCnt();
                List<Node> waitingList = getCurrentWaitingList();
                waitingList.add(this.node);
                while (!allow()) {
                    this.node.getCondition().await();
                }
                onPathCnt++;
                enterInside();
                logStartWalking();
                waitingList.remove(this.node);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }

        private void travel() {
            try {
                logTravel();
                Thread.sleep(rnd.nextInt(200));
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                Thread.currentThread();
            }
        }

        private void exit() {
            lock.lock();
            try {
                decremenetCrosserCnt();
                onPathCnt--;
                logExit();
                if (onPathCnt == 0) {
                    currentType = TYPE.NONE;
                }

                if (checkCurrentCrosserStrength()) {
                    List<Node> waitingList = getCurrentWaitingList();
                    if (waitingList.size() > 0) {
                        waitingList.getFirst().getCondition().signal();
                    }
                } else {
                    List<Node> oppositeWaitingList = getOppositeWaitingList();
                    if (onPathCnt == 0 && oppositeWaitingList.size() > 0) {
                        oppositeWaitingList.getFirst().getCondition().signal();
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        private void incrementCrosserCnt() {
            if (this.node.getType() == TYPE.HEATHEN) {
                heathenCnt++;
            } else {
                prudeCnt++;
            }
        }

        private void decremenetCrosserCnt() {
            if (this.node.getType() == TYPE.HEATHEN) {
                heathenCnt--;
            } else {
                prudeCnt--;
            }
        }

        private List<Node> getCurrentWaitingList() {
            return this.node.getType() == TYPE.HEATHEN ? heathenWaitingList : prudeWaitingList;
        }

        private List<Node> getOppositeWaitingList() {
            return this.node.getType() == TYPE.HEATHEN ? prudeWaitingList : heathenWaitingList;
        }

        private boolean allow() {
            if (currentType == TYPE.NONE) {
                return checkCurrentCrosserStrength();
            } else {
                if (currentType == this.node.getType()) {
                    return checkCurrentCrosserStrength();
                } else {
                    return false;
                }
            }
        }

        private boolean checkCurrentCrosserStrength() {
            if (this.node.getType() == TYPE.HEATHEN) {
                return heathenCnt >= prudeCnt;
            } else {
                return prudeCnt >= heathenCnt;
            }
        }

        private void enterInside() {
            currentType = this.node.getType();
        }

        private void logStartWalking() {
            System.out.println("Person of type=" + this.node.getType() + ", id = " + this.node.getId()
                    + " has started WALKING !");
        }

        private void logTravel() {
            System.out.println("Person of type=" + this.node.getType() + ", id = " + this.node.getId()
                    + " is walking...");
        }

        private void logExit() {
            System.out.println("Person of type=" + this.node.getType() + ", id = " + this.node.getId()
                    + " is EXISTING !!");
        }
    }

    private class HeathenRunnable extends CrosserRunnable {
        public HeathenRunnable(int id) {
            super(id, TYPE.HEATHEN);
        }
    }

    private class PrudeRunnable extends CrosserRunnable {
        public PrudeRunnable(int id) {
            super(id, TYPE.PRUDE);
        }
    }

    public void solve() throws InterruptedException {
        List<Thread> heathenThreads = new ArrayList<>();
        List<Thread> prudeThreads = new ArrayList<>();

        for (int i = 0; i < this.N; i++) {
            heathenThreads.add(new Thread(new HeathenRunnable(i), "Heathen-Thread-" + i));
        }

        for (int i = 0; i < this.M; i++) {
            prudeThreads.add(new Thread(new PrudeRunnable(i), "Prude-Thread-" + i));
        }

        for (Thread t : heathenThreads) {
            t.start();
        }
        for (Thread t : prudeThreads) {
            t.start();
        }

        for (Thread t : heathenThreads) {
            t.join();
        }
        for (Thread t : prudeThreads) {
            t.join();
        }
    }

    public static void main(String args[]) throws InterruptedException {
        new ModusHall(10, 10).solve();
    }

}
