package Problems.S02_LessClassical.P07_RollerCoaster;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class RollerCoaster {
    
    private final int N; // passenger Threads.
    private final int C; // capacity of car.

    private final Lock lock = new ReentrantLock();
    private Condition carStartCondition = lock.newCondition();
    private Condition carRunCondition = lock.newCondition();
    private Queue<Node> waitingList = new LinkedList<>();
    private int boarded = 0;
    private int rideId = 0;
    private boolean reached = false;
    private int unboard = 0;

    public class Node {
        public int id;
        public Condition condition;
        public Integer carRideId = null;
        public Node(int id, Condition condition)  {
            this.id = id;
            this.condition = condition;
        }
    }
    
    public RollerCoaster(int N, int C) {
        this.N = N;
        this.C = C;
    }

    public class CarRunnable implements Runnable {
        
        @Override
        public void run() {
            while(true) {
                lock.lock();
                List<Node> currentRide = new ArrayList<>();
                try {
                    while(waitingList.size() < RollerCoaster.this.C)  {
                        carStartCondition.await();
                    }
                    rideId++;
                    System.out.println("Started Loading in car, with CarRideId: " + rideId); // load
                    for(int i = 0; i < RollerCoaster.this.C; i++){
                        Node node = waitingList.poll();
                        node.carRideId = RollerCoaster.this.rideId;
                        node.condition.signal();

                        currentRide.add(node);
                    }
                    while(boarded < RollerCoaster.this.C) {
                        carRunCondition.await();
                    }
                    System.out.println("Starting car ride, with CarRideId: " + rideId); // run
                    boarded = 0;
                } catch(InterruptedException e) {
                    
                } finally {
                    lock.unlock();
                }
                
                try {
                    Thread.sleep(1000);
                } catch(InterruptedException e) {

                }

                lock.lock();
                try {
                    System.out.println("Car have reached the destination, with CarRideId: " + rideId);
                    reached = true;
                    for(Node node : currentRide) {
                        node.condition.signal();
                    }
                    while(unboard < RollerCoaster.this.C) {
                        carRunCondition.await();
                    }
                    reached = false;
                    unboard = 0;
                } catch(InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    lock.unlock();
                }

            }
        }
    }

    public class PassengerRunnable implements Runnable {
        private int id;
        public PassengerRunnable(int id) {
            this.id = id;
        }
        @Override
        public void run() {
            lock.lock();
            Node me = new Node(this.id, lock.newCondition());
            try {
                waitingList.add(me);
                carStartCondition.signal();
                while(me.carRideId == null) {
                    me.condition.await();
                }
                
                System.out.println("Passenger-" + this.id + " is boarding the car. CarRideId: " + me.carRideId); // board method
                boarded++;
                carRunCondition.signal();
            } catch(InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }

            lock.lock();
            try {
                while(!reached) {
                    me.condition.await();
                }
                System.out.println("Passenger-" + this.id + " has reached. CarRideId: " + me.carRideId); // unboard method
                unboard++;
                carRunCondition.signal();
            } catch(InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }
        }
    }

    public void solve() throws InterruptedException {
        List<Thread> passengerThreads = new ArrayList<>();
        Thread carThread = new Thread(new CarRunnable(), "Car-Thread");

        for(int i = 0; i < this.N; i++) {
            passengerThreads.add(new Thread(new PassengerRunnable(i), "Passenger-Thread" + i));
        }
        carThread.setDaemon(true);
        carThread.start();
        for(Thread t : passengerThreads) {
            t.start();
        }
        for(Thread t : passengerThreads) {
            t.join();
        }
        Thread.sleep(5000);
    }
    public static void main(String[] args) throws InterruptedException {
        int N = 16;
        int C = 4;

        new RollerCoaster(N, C).solve();
    }
}
