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
    private final int N; // Number of left Babons.
    private final int M; // Number of right Babons.
    
    private Random rnd = new Random();
    public BaboonCrossing(int N, int M) {
        this.N = N;
        this.M = M;
    }

    private Lock lock = new ReentrantLock();
    
    public enum DIR {
        NONE, LEFT, RIGHT;
    }
    
    private DIR currentDir = DIR.NONE;
    private int currentCnt = 0;
    private int batchCnt = 0;
    private int MAX_CNT = 5;
    private int BATCH_SIZE = 6;


    private class Node {
        private int id;
        private DIR dir;
        private Condition condition;
        public Node(int id, DIR dir) {
            this.id = id;
            this.dir = dir;
        }
    }

    private Queue<Node> leftWaiting = new LinkedList<>();
    private Queue<Node> rightWaiting = new LinkedList<>();

    public class LeftBaboonRunnable implements Runnable {
        private final int id;
        public LeftBaboonRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            lock.lock();
            
            Node node = new Node(this.id, DIR.LEFT);
            node.condition = lock.newCondition();
            leftWaiting.add(node);
            
            try {
                while(!allowLeft()) {
                    node.condition.await();
                }
                currentDir = DIR.LEFT;
                currentCnt++;
                batchCnt++;
                leftWaiting.poll();
                
                System.out.println("Left-Baboon-" + this.id + " has entered on the rope");
            } catch(InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }

            try {
                Thread.sleep(rnd.nextInt(300));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            
            lock.lock();
            try {
                currentCnt--;
                if(batchCnt == BATCH_SIZE) {
                    if(currentCnt == 0) {
                        batchCnt = 0;
                        currentDir = DIR.NONE;
                        if(rightWaiting.size() > 0) {
                            List<Node>rightSignal = peekFirstK(rightWaiting, MAX_CNT);
                            for(Node rNode : rightSignal) {
                                rNode.condition.signal();
                            }
                        } else {
                            // no right babon waiting, signal more left baboon to go.
                            List<Node> leftSignal = peekFirstK(leftWaiting, MAX_CNT);
                            for(Node lNode : leftSignal) {
                                lNode.condition.signal();
                            }
                        }
                    } else {
                        // lets wait for the last baboon to cross.
                    }
                } else {
                    if(leftWaiting.size() > 0) {
                        Node lNode = leftWaiting.peek();
                        lNode.condition.signal();
                    }else {
                        if(currentCnt == 0) {
                            batchCnt = 0;
                            currentDir = DIR.NONE;
                            List<Node> rightSignal = peekFirstK(rightWaiting, MAX_CNT);
                            for(Node rNode : rightSignal) {
                                rNode.condition.signal();
                            }
                        }
                    }
                }
                System.out.println("Left-Baboon-" + this.id + " has reached its destination.");
            } finally {
                lock.unlock();
            }
        }

        private boolean allowLeft() {
            if(currentDir == DIR.NONE) {
                return true;
            } else if (currentDir == DIR.LEFT && currentCnt < MAX_CNT && batchCnt < BATCH_SIZE) {
                return true;
            } else {
                return false;
            }
        }
    }

    public class RightBaboonRunnable implements Runnable {
        private final int id;
        public RightBaboonRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            lock.lock();
            Node node = new Node(this.id, DIR.RIGHT);
            node.condition = lock.newCondition();
            rightWaiting.add(node);
            try {
                while(!allowRight()){
                    node.condition.await();
                }   
                currentCnt++;
                batchCnt++;
                currentDir = DIR.RIGHT;
                rightWaiting.poll();

                System.out.println("Right-Baboon-" + this.id + " has entered the rope.");
            } catch(InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }

            try {
                Thread.sleep(rnd.nextInt(300));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            lock.lock();
            try {
                currentCnt--;
                if(batchCnt == BATCH_SIZE) {
                    if(currentCnt == 0) {
                        batchCnt = 0;
                        currentDir = DIR.NONE;
                        if(leftWaiting.size() > 0) {
                            List<Node> leftSignal = peekFirstK(leftWaiting, MAX_CNT);
                            for(Node lNode : leftSignal) {
                                lNode.condition.signal();
                            }
                        } else {
                            List<Node> rightSignal = peekFirstK(rightWaiting, MAX_CNT);
                            for(Node rNode : rightSignal) {
                                rNode.condition.signal();
                            }
                        }
                    } else {
                        // let the last thread handle.
                    }
                } else {
                    if(rightWaiting.size() > 0) {
                        Node rNode = rightWaiting.peek();
                        rNode.condition.signal();
                    } else {
                        if(currentCnt == 0) {
                            batchCnt = 0;
                            currentDir = DIR.NONE;
                            List<Node> leftSignal = peekFirstK(leftWaiting, MAX_CNT);
                            for(Node lNode : leftSignal) {
                                lNode.condition.signal();
                            }
                        }
                    }
                }
                System.out.println("Right-Baboon-" + this.id + " has reached its destination.");
            } finally {
                lock.unlock();
            }
        }

        private boolean allowRight() {
            if(currentDir == DIR.NONE) {
                return true;
            } else if (currentDir == DIR.RIGHT && currentCnt < MAX_CNT && batchCnt < BATCH_SIZE) {
                return true;
            } else {
                return false;
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
        List<Thread> left = new ArrayList<>();
        List<Thread> right = new ArrayList<>();

        for(int i = 0; i < this.N; i++) {
            left.add(new Thread(new LeftBaboonRunnable(i), "Left-Baboon-Thread-" + i));
        }
        for(int i = 0; i < this.M; i++) {
            right.add(new Thread(new RightBaboonRunnable(i), "Right-Baboon-Thread-" + i));
        }

        for(Thread t : left) {
            t.start();
        }
        for(Thread t : right) {
            t.start();
        }

        for(Thread t : left) {
            t.join();
        }

        for(Thread t : right) {
            t.join();
        }
    }


    public static void main(String[] args) throws InterruptedException {
        int N = 10;
        int M = 10;
        new BaboonCrossing(N, M).solve();
    }
}
