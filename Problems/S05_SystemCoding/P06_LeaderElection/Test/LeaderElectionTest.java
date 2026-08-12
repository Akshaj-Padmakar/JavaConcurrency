package Problems.S05_SystemCoding.P06_LeaderElection.Test;

import Problems.S05_SystemCoding.P06_LeaderElection.LeaderElection.AppendResponse;
import Problems.S05_SystemCoding.P06_LeaderElection.LeaderElection.Cluster;
import Problems.S05_SystemCoding.P06_LeaderElection.LeaderElection.Node;
import Problems.S05_SystemCoding.P06_LeaderElection.LeaderElection.Role;
import Problems.S05_SystemCoding.P06_LeaderElection.LeaderElection.State;
import Problems.S05_SystemCoding.P06_LeaderElection.LeaderElection.VoteResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Plain main()-based tests, matching this repo's style (no JUnit).
 *
 * THE invariant is one line: no two nodes may be LEADER for the SAME term. Two leaders in
 * DIFFERENT terms is legal and expected -- a partitioned incumbent keeps believing it leads until
 * it hears a higher term. Confusing those two is the most common way to write a wrong test here.
 *
 * The chaos test is the one that matters: 12 seconds of crashes, revivals, partitions and heals
 * with a detector sampling continuously. Every other test checks a specific behaviour; that one
 * checks the property.
 */
public class LeaderElectionTest {

    private static final long MIN_TIMEOUT_MILLIS = 150;
    private static final long MAX_TIMEOUT_MILLIS = 300;
    private static final long HEARTBEAT_MILLIS = 40;

    public static void main(String[] args) {
        testALeaderEmergesFromAHealthyCluster();
        testTheClusterConvergesOnASingleTerm();
        testKillingTheLeaderElectsANewOneWithAHigherTerm();
        testAPartitionedLeaderStepsDownWhenItRejoins();
        testANodeVotesAtMostOncePerTerm();
        testALowerTermRequestIsRejectedAndReportsOurTerm();
        testAMinorityCannotElectALeader();
        testStopActuallyStopsTheThread();
        testChaosNeverProducesTwoLeadersInOneTerm();

        System.out.println("All LeaderElection tests passed.");
    }

    private static void testALeaderEmergesFromAHealthyCluster() {
        Network network = newCluster(5);
        try {
            long startNanos = System.nanoTime();
            Node leader = awaitSingleLeader(network, 5_000);
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

            assertTrue("a leader must emerge from a healthy cluster", leader != null);
            assertTrue("and within a few election timeouts, not seconds (took " + elapsedMillis
                    + "ms, timeout is " + MIN_TIMEOUT_MILLIS + "-" + MAX_TIMEOUT_MILLIS + "ms)",
                    elapsedMillis < 10 * MAX_TIMEOUT_MILLIS);

            sleep(500);
            assertEquals("and it must be stable", 1, liveLeaders(network).size());
        } finally {
            network.stopAll();
        }
    }

    /**
     * Regression. A candidate stops asking once it has a majority, so some nodes are never sent a
     * vote request -- their ONLY source of term updates is appendEntries. When that path failed to
     * adopt the leader's term, those nodes sat a term behind forever while everything still "worked".
     */
    private static void testTheClusterConvergesOnASingleTerm() {
        Network network = newCluster(5);
        try {
            assertTrue("a leader must emerge", awaitSingleLeader(network, 5_000) != null);
            sleep(1_000);   // many heartbeat rounds

            Set<Long> terms = new HashSet<>();
            for (Node node : network.all()) {
                terms.add(node.snapshot().getCurrentTerm());
            }
            assertEquals("every node must be on the same term after steady heartbeats"
                    + " (saw " + terms + ")", 1, terms.size());
        } finally {
            network.stopAll();
        }
    }

    private static void testKillingTheLeaderElectsANewOneWithAHigherTerm() {
        Network network = newCluster(5);
        try {
            Node first = awaitSingleLeader(network, 5_000);
            assertTrue("a leader must emerge", first != null);
            long firstTerm = first.snapshot().getCurrentTerm();

            network.crash(first.getId());

            Node second = awaitSingleLeader(network, 8_000);
            assertTrue("a new leader must take over", second != null);
            assertTrue("and it must be a different node", second.getId() != first.getId());
            assertTrue("in a strictly higher term (" + second.snapshot().getCurrentTerm()
                    + " > " + firstTerm + ")", second.snapshot().getCurrentTerm() > firstTerm);
        } finally {
            network.stopAll();
        }
    }

    /**
     * The fencing case. A partitioned leader keeps believing it leads -- nothing tells it otherwise
     * and nothing can. It works out that it was replaced from the TERM NUMBER alone, on its first
     * contact after the heal.
     */
    private static void testAPartitionedLeaderStepsDownWhenItRejoins() {
        Network network = newCluster(5);
        try {
            Node stale = awaitSingleLeader(network, 5_000);
            assertTrue("a leader must emerge", stale != null);
            long staleTerm = stale.snapshot().getCurrentTerm();

            List<Integer> rest = new ArrayList<>();
            for (Node node : network.all()) {
                if (node.getId() != stale.getId()) rest.add(node.getId());
            }
            network.partition(List.of(stale.getId()), rest);

            sleep(2_000);   // the majority side elects a new leader
            assertTrue("while partitioned, the old leader still believes it leads",
                    stale.snapshot().getRole() == Role.LEADER);

            network.healAll();
            sleep(1_500);

            State after = stale.snapshot();
            assertTrue("on rejoining it must step down (still " + after.getRole() + ")",
                    after.getRole() != Role.LEADER);
            assertTrue("and adopt the newer term (" + after.getCurrentTerm() + " > " + staleTerm + ")",
                    after.getCurrentTerm() > staleTerm);
        } finally {
            network.stopAll();
        }
    }

    /** The safety property, tested directly rather than inferred. */
    private static void testANodeVotesAtMostOncePerTerm() {
        // Timeouts long enough that these nodes never start elections of their own.
        Network network = newCluster(3, 100_000, 100_000);
        try {
            Node voter = network.node(0);

            VoteResponse first = voter.requestVote(5, 1);
            VoteResponse second = voter.requestVote(5, 2);   // DIFFERENT candidate, SAME term
            VoteResponse repeat = voter.requestVote(5, 1);   // same candidate again

            assertTrue("the first candidate in a term gets the vote", first.isGranted());
            assertTrue("a second candidate in the same term must be refused", !second.isGranted());
            assertTrue("re-asking with the same candidate is idempotent", repeat.isGranted());
        } finally {
            network.stopAll();
        }
    }

    /** How a stale candidate or leader discovers it is stale: the response carries our term. */
    private static void testALowerTermRequestIsRejectedAndReportsOurTerm() {
        Network network = newCluster(3, 100_000, 100_000);
        try {
            Node voter = network.node(0);
            voter.requestVote(7, 1);                          // advance the voter to term 7

            VoteResponse stale = voter.requestVote(4, 2);
            assertTrue("a lower-term vote request must be refused", !stale.isGranted());
            assertEquals("and must report our term so the sender can step down", 7, stale.getTerm());

            AppendResponse staleHeartbeat = voter.appendEntries(4, 2);
            assertTrue("a lower-term heartbeat must be refused", !staleHeartbeat.isSuccess());
            assertEquals("and must report our term", 7, staleHeartbeat.getTerm());
        } finally {
            network.stopAll();
        }
    }

    /**
     * A minority cannot ELECT a leader -- which is NOT the same as "no minority node reports
     * LEADER". Put the incumbent in the majority side so the distinction does not muddy the test.
     */
    private static void testAMinorityCannotElectALeader() {
        Network network = newCluster(5);
        try {
            Node leader = awaitSingleLeader(network, 5_000);
            assertTrue("a leader must emerge", leader != null);
            long leaderTerm = leader.snapshot().getCurrentTerm();

            List<Integer> others = new ArrayList<>();
            for (Node node : network.all()) {
                if (node.getId() != leader.getId()) others.add(node.getId());
            }
            List<Integer> majority = List.of(leader.getId(), others.get(0), others.get(1));
            List<Integer> minority = List.of(others.get(2), others.get(3));

            network.partition(majority, minority);
            sleep(2_500);   // many failed election rounds on the minority side

            long minorityLeaders = minority.stream()
                    .filter(id -> network.node(id).snapshot().getRole() == Role.LEADER).count();
            long majorityLeaders = majority.stream()
                    .filter(id -> network.node(id).snapshot().getRole() == Role.LEADER).count();
            long highestMinorityTerm = minority.stream()
                    .mapToLong(id -> network.node(id).snapshot().getCurrentTerm()).max().orElse(0);

            assertEquals("a 2-of-5 minority must never elect a leader", 0, minorityLeaders);
            assertTrue("but it keeps trying -- its term climbs past the leader's ("
                    + highestMinorityTerm + " > " + leaderTerm + ")", highestMinorityTerm > leaderTerm);
            assertEquals("the 3-of-5 majority keeps exactly one leader", 1, majorityLeaders);
        } finally {
            network.stopAll();
        }
    }

    /** Regression: stop() once returned before the thread had actually finished. */
    private static void testStopActuallyStopsTheThread() {
        Network network = newCluster(5);
        try {
            sleep(600);
            network.stopAll();

            long stillAlive = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> thread.getName().startsWith("node-") && thread.isAlive())
                    .count();
            assertEquals("stop() must not return until the node thread has finished", 0, stillAlive);
        } finally {
            network.stopAll();
        }
    }

    /**
     * The test that matters. Constant crashes, revivals, partitions and heals for 12 seconds, with
     * a detector sampling the cluster the whole time. Exactly one assertion about safety, plus a
     * liveness check once the chaos stops.
     */
    private static void testChaosNeverProducesTwoLeadersInOneTerm() {
        Network network = newCluster(5);
        SplitBrainDetector detector = new SplitBrainDetector(network);
        try {
            detector.start();

            ThreadLocalRandom random = ThreadLocalRandom.current();
            long endNanos = System.nanoTime() + 12_000L * 1_000_000L;
            while (System.nanoTime() < endNanos) {
                switch (random.nextInt(4)) {
                    case 0:
                        if (network.crashedCount() < 2) network.crash(random.nextInt(5));
                        break;
                    case 1:
                        network.reviveAny();
                        break;
                    case 2:
                        int a = random.nextInt(5), b = random.nextInt(5);
                        if (a != b) network.sever(a, b);
                        break;
                    default:
                        network.healAll();
                        break;
                }
                sleep(random.nextInt(50, 200));
            }

            network.healAll();
            network.reviveAll();
            sleep(2_000);   // let it settle

            String violation = detector.stopAndGetViolation();
            assertTrue("SPLIT BRAIN -- " + violation, violation == null);
            assertEquals("the cluster must recover to a single leader once chaos stops",
                    1, liveLeaders(network).size());
        } finally {
            detector.stop();
            network.stopAll();
        }
    }

    // ---------- harness ----------

    /** Peers are objects; an RPC is a method call. Crashes and partitions are dropped calls. */
    private static final class Network implements Cluster {
        private final Map<Integer, Node> nodes = new HashMap<>();
        private final Set<Integer> crashed = Collections.synchronizedSet(new HashSet<>());
        private final Set<String> severed = Collections.synchronizedSet(new HashSet<>());

        void register(Node node) { nodes.put(node.getId(), node); }
        List<Node> all() { return new ArrayList<>(nodes.values()); }
        Node node(int id) { return nodes.get(id); }
        boolean isUp(int id) { return !crashed.contains(id); }
        int crashedCount() { return crashed.size(); }

        void crash(int id) {
            if (crashed.add(id)) nodes.get(id).stop();
        }

        void reviveAny() {
            List<Integer> down = new ArrayList<>(crashed);
            if (!down.isEmpty()) revive(down.get(0));
        }

        void reviveAll() {
            for (Integer id : new ArrayList<>(crashed)) revive(id);
        }

        private void revive(int id) {
            crashed.remove(id);
            nodes.get(id).start();
        }

        void sever(int a, int b) { severed.add(link(a, b)); }
        void healAll() { severed.clear(); }

        void partition(List<Integer> groupA, List<Integer> groupB) {
            for (int a : groupA) for (int b : groupB) sever(a, b);
        }

        void stopAll() { for (Node node : nodes.values()) node.stop(); }

        @Override public List<Integer> peers(int selfId) {
            List<Integer> out = new ArrayList<>();
            for (int id : nodes.keySet()) if (id != selfId) out.add(id);
            return out;
        }

        @Override public int size() { return nodes.size(); }

        @Override public VoteResponse requestVote(int from, int to, long term, int candidateId) {
            return reachable(from, to) ? nodes.get(to).requestVote(term, candidateId) : null;
        }

        @Override public AppendResponse appendEntries(int from, int to, long term, int leaderId) {
            return reachable(from, to) ? nodes.get(to).appendEntries(term, leaderId) : null;
        }

        private boolean reachable(int from, int to) {
            return isUp(from) && isUp(to) && !severed.contains(link(from, to));
        }

        private static String link(int a, int b) {
            return Math.min(a, b) + "-" + Math.max(a, b);
        }
    }

    /** Samples continuously and records the first moment two nodes lead the SAME term. */
    private static final class SplitBrainDetector {
        private final Network network;
        private volatile boolean stopped = false;
        private volatile String violation = null;
        private Thread thread;

        SplitBrainDetector(Network network) { this.network = network; }

        void start() {
            thread = new Thread(() -> {
                while (!stopped) {
                    Map<Long, Integer> leaderByTerm = new HashMap<>();
                    for (Node node : network.all()) {
                        if (!network.isUp(node.getId())) continue;   // a crashed node's state is frozen
                        State state = node.snapshot();               // ONE atomic read of (role, term)
                        if (state.getRole() == Role.LEADER) {
                            Integer other = leaderByTerm.put(state.getCurrentTerm(), node.getId());
                            if (other != null && violation == null) {
                                violation = "nodes " + other + " and " + node.getId()
                                        + " were both LEADER in term " + state.getCurrentTerm();
                            }
                        }
                    }
                }
            }, "split-brain-detector");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() { stopped = true; }

        String stopAndGetViolation() {
            stopped = true;
            try {
                thread.join(2_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return violation;
        }
    }

    private static Network newCluster(int size) {
        return newCluster(size, MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS);
    }

    private static Network newCluster(int size, long minTimeout, long maxTimeout) {
        Network network = new Network();
        for (int id = 0; id < size; id++) {
            network.register(new Node(id, network, minTimeout, maxTimeout, HEARTBEAT_MILLIS));
        }
        for (Node node : network.all()) node.start();
        return network;
    }

    /** Crashed nodes excluded -- their state is frozen and they are not participating. */
    private static List<Node> liveLeaders(Network network) {
        List<Node> leaders = new ArrayList<>();
        for (Node node : network.all()) {
            if (network.isUp(node.getId()) && node.snapshot().getRole() == Role.LEADER) {
                leaders.add(node);
            }
        }
        return leaders;
    }

    private static Node awaitSingleLeader(Network network, long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            List<Node> leaders = liveLeaders(network);
            if (leaders.size() == 1) return leaders.get(0);
            sleep(10);
        }
        return null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void assertTrue(String message, boolean condition) {
        if (!condition) throw new AssertionError("FAILED: " + message);
    }

    private static void assertEquals(String message, long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError("FAILED: " + message + " (expected " + expected + ", got " + actual + ")");
        }
    }
}
