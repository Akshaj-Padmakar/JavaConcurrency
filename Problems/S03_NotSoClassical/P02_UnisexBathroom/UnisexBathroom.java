package Problems.S03_NotSoClassical.P02_UnisexBathroom;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class UnisexBathroom {
    private final int N; // number of men
    private final int M; // number of women

    public UnisexBathroom(int N, int M) {
        this.N = N;
        this.M = M;
    }

    private Lock lock = new ReentrantLock();
    private Random rnd = new Random();

    public enum TYPE {
        NONE, MEN, WOMEN;
    }

    private TYPE currentGender = TYPE.NONE;
    private int currentGenderCnt = 0; // How many of current gender are processed.
    private int insideCnt = 0; // How many are inside.
    
    private int BATCH_SIZE = 5;
    private final int MAX_CAPACITY = 3;

    private Queue<Node> menWaitingList = new LinkedList<>();
    private Queue<Node> womenWaitingList = new LinkedList<>();

    private class Node {
        private final TYPE type;
        private final int id;
        private Condition condition;

        public Node(int id, TYPE type) {
            this.id = id;
            this.type = type;
        }
    }
    
    

    public class MenRunnable implements Runnable {
        private int id;
        public MenRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            lock.lock();
            
            Node node = new Node(this.id, TYPE.MEN);
            node.condition = lock.newCondition();
            menWaitingList.add(node);
            
            try {
                while(!allowMen()) {
                    node.condition.await();
                }
                currentGender = TYPE.MEN;
                currentGenderCnt++;
                insideCnt++;
                
                menWaitingList.poll();
                System.out.println("Men-" + this.id + " has entered the bathroom");
            } catch(InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }

            try {
                Thread.sleep(f()) ;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            lock.lock();
            try {
                System.out.println("Men-" + this.id + " is done, leaving the bathroom.");
                insideCnt--;
                if(currentGenderCnt == BATCH_SIZE) {
                    if(insideCnt == 0) {
                        if(womenWaitingList.size() != 0) {
                            List<Node> signalWomen = peekFirstK(womenWaitingList, MAX_CAPACITY);
                            
                            currentGender = TYPE.WOMEN;
                            currentGenderCnt = 0;
                            
                            for(Node Wnode : signalWomen) {
                                Wnode.condition.signal();
                            }
                        } else {
                            // no women waiting singal men again
                            currentGenderCnt = 0;
                            currentGender = TYPE.NONE;
                            
                            List<Node> signalMen = peekFirstK(menWaitingList, MAX_CAPACITY);
                            
                            for(Node Mnode : signalMen) {
                                Mnode.condition.signal();
                            }
                        }
                    } else {
                        // we wait for the last men to exit, who will signal the next men/women
                    }
                } else {
                    // signal more men.
                    if(menWaitingList.size() > 0) {
                        Node mNode = menWaitingList.peek();
                        mNode.condition.signal(); // allow 1 more men.
                    } else {
                        // no men waiting.
                        if(insideCnt == 0) {
                            currentGenderCnt = 0;
                            currentGender = TYPE.NONE;
                            List<Node> signalWomen = peekFirstK(womenWaitingList, MAX_CAPACITY);
                                
                            for(Node Wnode : signalWomen) {
                                Wnode.condition.signal();
                            }
                        }
                    }
                }
            } finally {
                lock.unlock();
            }

        }



        private int f() {
            return 300 + rnd.nextInt(100);
        }

        private boolean allowMen() {
            if(currentGender == TYPE.NONE) {
                return true;
            } else if(currentGender == TYPE.MEN && currentGenderCnt < BATCH_SIZE && insideCnt < MAX_CAPACITY) {
                return true;
            } else {
                return false;
            }
        }
    }



    public class WomenRunnable implements Runnable {
        private int id;
        public WomenRunnable(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            lock.lock();
            Node node = new Node(this.id, TYPE.WOMEN);
            node.condition = lock.newCondition();
            womenWaitingList.add(node);
            try {
                while(!allowWomen()) {
                    node.condition.await();
                }
                currentGender = TYPE.WOMEN;
                currentGenderCnt++;
                insideCnt++;

                womenWaitingList.poll();
                System.out.println("Women-" + this.id + " has entered the bathroom.");
            } catch(InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }

            try {
                Thread.sleep(f()); 
            } catch(InterruptedException e) {
                e.printStackTrace();
            }

            lock.lock();

            try {
                System.out.println("Women-" + this.id + " is done, leaving the bathroom.");
                insideCnt--;

                if(currentGenderCnt == BATCH_SIZE) {
                    if(insideCnt == 0) {
                        if(menWaitingList.size() > 0) {
                            List<Node> signalMen = peekFirstK(menWaitingList, MAX_CAPACITY);
                            
                            currentGenderCnt = 0;
                            currentGender = TYPE.MEN;
                            
                            for(Node mNode : signalMen) {
                                mNode.condition.signal();
                            }
                        } else {
                            currentGenderCnt = 0;
                            currentGender = TYPE.NONE;
                            List<Node> signalWomen = peekFirstK(womenWaitingList, MAX_CAPACITY);
                            for(Node wNode : signalWomen) {
                                wNode.condition.signal();
                            }
                        }
                    } else {
                        // lets wait for the last women to leave to signal men to enter(if they are waiting).
                    }
                } else {
                    // signal more women to enter.
                    if(womenWaitingList.size() > 0) {
                        Node wNode = womenWaitingList.peek();
                        wNode.condition.signal();
                    } else {
                        // no women are waiting, allow men if there are any.
                        if(insideCnt == 0) {
                            currentGender = TYPE.NONE;
                            currentGenderCnt = 0;
                            
                            List<Node> singalMen = peekFirstK(menWaitingList, MAX_CAPACITY);
                            for(Node mNode : singalMen) {
                                mNode.condition.signal();
                            }
                        }
                    }
                }
            } finally {
                lock.unlock();
            }

        }

        private int f() {
            return 300 + rnd.nextInt(100);
        }

        private boolean allowWomen() {
            if(currentGender == TYPE.NONE) {
                return true;
            } else if(currentGender == TYPE.WOMEN && currentGenderCnt < BATCH_SIZE && insideCnt < MAX_CAPACITY) {
                return true;
            } else {
                return false;
            }
        }
    }


    // helper: peek first K entries from queue without removing
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
        List<Thread> menThread = new ArrayList<>();
        List<Thread> womenThread = new ArrayList<>();
        
        for(int i = 0; i < this.N; i++) {
            menThread.add(new Thread(new MenRunnable(i), "Men-Thread-" + i));
        }

        for(int i = 0; i < this.M; i++) {
            womenThread.add(new Thread(new WomenRunnable(i), "Women-Thread-" + i));
        }

        for(Thread t : menThread) {
            t.start();
        }

        for(Thread t : womenThread) {
            t.start();
        }

        for(Thread t : menThread) {
            t.join();
        }

        for(Thread t : womenThread) {
            t.join();
        }
    }
    public static void main(String[] args) throws InterruptedException {
        int N = 22;
        int M = 17;
        new UnisexBathroom(N, M).solve();
    }
}
