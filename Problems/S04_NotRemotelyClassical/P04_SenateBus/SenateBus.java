package Problems.S04_NotRemotelyClassical.P04_SenateBus;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SenateBus {
    private final int passengerCnt;
    private final Lock lock = new ReentrantLock();

    private final Condition passengerWaitingBusCondition = lock.newCondition();
    private final Condition busStartCondition = lock.newCondition();
    private final Condition peopleOnBoardCondition = lock.newCondition();
    private final Condition busFreeCondition = lock.newCondition();
    private final Condition boardingStartedCondition = lock.newCondition();

    private boolean busReachedStop = false;
    private boolean busReachedDestination = false;

    private final int MAX_CAPACITY = 50;
    private int onBoard = 0;
    private int waitingPassengerCnt = 0;
    private int waitingBoardingPassengerCnt = 0;

    public SenateBus(int passengerCnt) {
        this.passengerCnt = passengerCnt;
    }

    private class Bus implements Runnable {
        @Override
        public void run() {
            reachBusStop();

            startBus();

            driveBus();

            reachedDestination();
        }

        private void reachBusStop() {
            lock.lock();
            try {
                busReachedStop = true;
                logBusReached();
                passengerWaitingBusCondition.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private void startBus() {
            lock.lock();
            try {
                while (!(onBoard == MAX_CAPACITY || waitingBoardingPassengerCnt == 0)) {
                    busStartCondition.await();
                }
                logStartBus();
                busReachedStop = false;
                boardingStartedCondition.signalAll();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }
        }

        private void driveBus() {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        }

        private void reachedDestination() {
            lock.lock();
            try {
                busReachedDestination = true;
                peopleOnBoardCondition.signalAll();
                logBusReachedDestination();
                while (onBoard > 0) {
                    busFreeCondition.await();
                }
                logBusFree();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                busReachedDestination = false;
                lock.unlock();
            }
        }

        private void logBusReached() {
            System.out.println("Bus has reached the stop, START BOARDING !!!");
        }

        private void logStartBus() {
            System.out.println("Bus is done boarding, starting the JOURNEY now !!!");
        }

        private void logBusReachedDestination() {
            System.out.println("Bus has reached destination, waiting for onboarding people to exit.");
        }

        private void logBusFree() {
            System.out.println("Bus is now free and no one is onboard.");
        }
    }

    private class Passenger implements Runnable {
        private final int id;

        public Passenger(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            reachBusStop();

            boardBus();

            unBoardBus();
        }

        private void reachBusStop() {
            lock.lock();
            try {
                waitingPassengerCnt++;
                logPassengerWaitingForBus();
                while (busReachedStop) {
                    boardingStartedCondition.await();
                }

                waitingPassengerCnt--;
                waitingBoardingPassengerCnt++;
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }
        }

        private void boardBus() {
            lock.lock();
            try {

                while (!busReachedStop || onBoard == MAX_CAPACITY) {
                    passengerWaitingBusCondition.await();
                }
                waitingBoardingPassengerCnt--;
                onBoard++;
                logPassengerOnBoard();

                if (onBoard == MAX_CAPACITY || waitingBoardingPassengerCnt == 0) {
                    busStartCondition.signal();
                }
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }
        }

        private void unBoardBus() {
            lock.lock();
            try {
                while (!busReachedDestination) {
                    peopleOnBoardCondition.await();
                }
                logPassengerUnBoarding();
                onBoard--;
                if (onBoard == 0) {
                    busFreeCondition.signal();
                }
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }

        }

        private void logPassengerWaitingForBus() {
            System.out.println("Passenger-" + this.id + " has reached the bus stop, and waiting for bus.");
        }

        private void logPassengerOnBoard() {
            System.out.println("Passenger-" + this.id + " has boarded the bus.");
        }

        private void logPassengerUnBoarding() {
            System.out.println("Passenger-" + this.id + " has un-boarded the bus.");
        }
    }
}
