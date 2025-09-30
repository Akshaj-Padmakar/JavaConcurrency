package Problems.S00_General.PlaygroundProblem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class TeamPlayground {
    
    private Lock lock = new ReentrantLock();
    private Map<Integer, Condition> teamCondition = new HashMap<>();
    private Map<Integer, Condition> playerFullCondition = new HashMap<>();
    private Map<Integer, Integer> teamCount = new HashMap<>();
    private Deque<Integer> waitingList = new ArrayDeque<>();
    private int playgroundTeamId = -1;
    private int playerCount = 0;
    private final int maxPlayerCount = 10;


    public class PlayerRunnable implements Runnable {
        private int teamId;
        private int playerId;
        public PlayerRunnable(int teamId, int playerId) {
            this.teamId = teamId;
            this.playerId = playerId;
        }

        @Override
        public void run() {
            try{
                enterPlayground();
                Thread.sleep(100); // play()
                exitPlayground();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        private void enterPlayground() throws InterruptedException {
            lock.lockInterruptibly();
            try {
                while(playgroundTeamId == this.teamId ) {
                    if(maxPlayerCount > playerCount) {
                        playerCount++;
                        teamCount.put(this.teamId, teamCount.get(this.teamId) - 1);
                        System.out.println("TeamId: " + playgroundTeamId + " PlayerId: " + this.playerId + " has entered the playground.");
                        System.out.println("PlayerCount: " + playerCount + " are in the playground.");
                        return;
                    } else {
                        while(playerCount >= maxPlayerCount){
                            System.out.println("TeamId: " + this.teamId + " PlayerId: " + this.playerId + " is waiting to enter the playground.");
                            playerFullCondition.get(this.teamId).await();
                        }
                    }
                }

                // this team doesn't occupy the playground right now, we enque it.

                if(playgroundTeamId == -1 && (waitingList.isEmpty() || waitingList.peekFirst() == this.teamId)){ //
                    playgroundTeamId = this.teamId;
                    playerCount = 1;
                    if(waitingList.size() > 0){
                        waitingList.remove(teamId);
                    }
                    teamCount.put(this.teamId, teamCount.get(this.teamId) - 1);
                    System.out.println("TeamId: " + playgroundTeamId + " PlayerId: " + this.playerId + " has claimed the empty playground.");
                    System.out.println("PlayerCount: " + playerCount + " are in the playground.");
                    return;
                }

                if(!waitingList.contains(this.teamId)) {
                    waitingList.add(teamId);
                    System.out.println("TeamId: " + this.teamId + " PlayerId: " + this.playerId + " is added in the waitingQueue");
                    System.out.println("WaitingList: " + waitingList.toString());
                }
                while(true) {
                    while(playgroundTeamId == this.teamId ) {
                        if(maxPlayerCount > playerCount) {
                            playerCount++;
                            teamCount.put(this.teamId, teamCount.get(this.teamId) - 1);
                            System.out.println("TeamId: " + playgroundTeamId + " PlayerId: " + this.playerId + " has entered the playground.");
                            System.out.println("PlayerCount: " + playerCount + " are in the playground.");
                            return;
                        } else {
                            while(playerCount >= maxPlayerCount){
                                System.out.println("TeamId: " + this.teamId + " PlayerId: " + this.playerId + " is waiting to enter the playground.");
                                playerFullCondition.get(this.teamId).await();
                            }
                        }
                    }
                    if(playgroundTeamId == -1 && (waitingList.isEmpty() || waitingList.peekFirst() == this.teamId)){ //
                        playgroundTeamId = this.teamId;
                        playerCount = 1;
                        if(waitingList.size() > 0){
                            waitingList.remove(teamId);
                        }
                        teamCount.put(this.teamId, teamCount.get(this.teamId) - 1);
                        System.out.println("TeamId: " + playgroundTeamId + " PlayerId: " + this.playerId + " has claimed the empty playground.");
                        System.out.println("PlayerCount: " + playerCount + " are in the playground.");
                        return;
                    } else {
                        teamCondition.get(this.teamId).await();
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        private void exitPlayground() throws InterruptedException {
            lock.lockInterruptibly();
            try {
                if(playgroundTeamId != this.teamId) {
                    throw new IllegalStateException("This player has never entered, cannot exit !");
                }

                playerCount--;
                if(playerCount == 0) {
                    if(teamCount.get(this.teamId) == 0) {
                        playgroundTeamId = -1;
                        playerCount = 0;
                        if(waitingList.size() > 0) {
                            teamCondition.get(waitingList.peekFirst()).signalAll();
                        }
                    } else {
                        playerFullCondition.get(this.teamId).signalAll();
                    }
                } else {
                    playerFullCondition.get(this.teamId).signalAll();
                }
                System.out.println("TeamId: " + this.teamId + " PlayerId: " + this.playerId + " has left the playground. "+ playgroundTeamId);
                System.out.println("PlayerCount: " + playerCount + " are in the playground. " + waitingList.toString() + teamCount.toString());
            } finally {
                lock.unlock();
            }
        }
    }
    public void solve(int teams, int playersPerTeam) throws InterruptedException {
        List<Thread> threads = new ArrayList<>();

        for(int i = 0; i < teams; i++) {
            teamCondition.put(i, lock.newCondition());
            playerFullCondition.put(i, lock.newCondition());
            teamCount.put(i, playersPerTeam);
            for(int j = 0; j < playersPerTeam; j++) {
                threads.add(new Thread(new PlayerRunnable(i, j), "Player-Thread-" + i + "-" + j));
            }
        }

        for(Thread t : threads) {
            t.start();
        }

        for(Thread t : threads) {
            t.join();
        }

        ExecutorService ex = new ThreadPoolExecutor(playersPerTeam, teams, playersPerTeam, null, null);
    }
    
    public static void main(String[] arg) throws InterruptedException {
        int teams = 5;
        int playersPerTeam = 12;
        new TeamPlayground().solve(teams, playersPerTeam);
    }
}
