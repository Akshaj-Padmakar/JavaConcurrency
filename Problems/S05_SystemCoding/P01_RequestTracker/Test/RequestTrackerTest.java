package Problems.S05_SystemCoding.P01_RequestTracker.Test;

import Problems.S05_SystemCoding.P01_RequestTracker.RequestTracker;
import Problems.S05_SystemCoding.P01_RequestTracker.RequestTracker.Attribute;
import Problems.S05_SystemCoding.P01_RequestTracker.RequestTracker.Request;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Plain main()-based tests, matching this repo's style (no JUnit).
 *
 * The tracker reads System.currentTimeMillis() directly, so anything time-dependent has to be
 * tested with a real Thread.sleep. Two consequences shape this suite:
 *
 *  - Windows are SHORT (hundreds of milliseconds) so the whole suite runs in a few seconds.
 *  - Margins are GENEROUS -- never "sleep to just past the boundary and check". A stalled machine
 *    would turn that into a flaky failure, so every timing assertion has several hundred
 *    milliseconds of slack on either side. Bucket-boundary precision is therefore NOT covered here;
 *    an injectable clock would be required to test it deterministically.
 */
public class RequestTrackerTest {

    public static void main(String[] args) throws Exception {
        testCountsRecordedRequests();
        testOneRecordUpdatesEveryGrouping();
        testUnknownValueReturnsZero();
        testUnconfiguredAttributeIsRejected();
        testNullAttributeValueIsSkippedNotFatal();
        testRequestsExpireOnceTheWindowPasses();
        testWindowSlidesRatherThanResetting();
        testCountIsZeroAfterALongIdleGap();
        testNewGroupingNeedsNoChangeToTheClass();
        testConcurrentRecordersLoseNoIncrements();
        testConcurrentReadersDoNotDisturbRecorders();
        testInvalidConstructorArgumentsRejected();
        testNullRequestRejected();

        System.out.println("All RequestTracker tests passed.");
    }

    private static void testCountsRecordedRequests() {
        RequestTracker tracker = new RequestTracker(60_000, ipAndAgent());
        for (int i = 0; i < 7; i++) {
            tracker.record(new Request("10.0.0.4", "curl/8.1", "/api"));
        }
        assertEquals("seven records for one IP", 7, tracker.count(Attribute.IP, "10.0.0.4"));
    }

    /** The point of groupBy: one record() call updates a window per configured attribute. */
    private static void testOneRecordUpdatesEveryGrouping() {
        RequestTracker tracker = new RequestTracker(60_000, ipAndAgent());
        tracker.record(new Request("10.0.0.4", "curl/8.1", "/api"));
        tracker.record(new Request("10.0.0.4", "Chrome", "/api"));
        tracker.record(new Request("10.0.0.9", "curl/8.1", "/api"));

        assertEquals("IP 10.0.0.4", 2, tracker.count(Attribute.IP, "10.0.0.4"));
        assertEquals("IP 10.0.0.9", 1, tracker.count(Attribute.IP, "10.0.0.9"));
        assertEquals("agent curl/8.1 -- same three requests, different slice",
                2, tracker.count(Attribute.BROWSER_AGENT, "curl/8.1"));
        assertEquals("agent Chrome", 1, tracker.count(Attribute.BROWSER_AGENT, "Chrome"));
    }

    /** A value never seen has a count of zero -- that is an answer, not an error. */
    private static void testUnknownValueReturnsZero() {
        RequestTracker tracker = new RequestTracker(60_000, ipAndAgent());
        tracker.record(new Request("10.0.0.4", "curl/8.1", "/api"));
        assertEquals("an IP that never appeared", 0, tracker.count(Attribute.IP, "10.0.0.7"));
    }

    /**
     * An attribute this tracker does not group by is a programming error, not "no traffic".
     * Returning 0 would make a misconfiguration look like a healthy service.
     */
    private static void testUnconfiguredAttributeIsRejected() {
        RequestTracker tracker = new RequestTracker(60_000, List.of(Attribute.IP));
        boolean rejected = false;
        try {
            tracker.count(Attribute.BROWSER_AGENT, "curl/8.1");
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue("querying an attribute that is not configured must throw", rejected);
    }

    /** A request with no User-Agent still counts under IP; it just is not tracked under agent. */
    private static void testNullAttributeValueIsSkippedNotFatal() {
        RequestTracker tracker = new RequestTracker(60_000, ipAndAgent());
        tracker.record(new Request("10.0.0.4", null, "/api"));

        assertEquals("the IP grouping still saw it", 1, tracker.count(Attribute.IP, "10.0.0.4"));
        assertEquals("the agent grouping did not", 0, tracker.count(Attribute.BROWSER_AGENT, "null"));
    }

    /** 400ms window, checked well past it: everything must have aged out. */
    private static void testRequestsExpireOnceTheWindowPasses() throws Exception {
        RequestTracker tracker = new RequestTracker(400, ipAndAgent());
        tracker.record(new Request("10.0.0.4", "curl/8.1", "/api"));
        assertEquals("just recorded", 1, tracker.count(Attribute.IP, "10.0.0.4"));

        Thread.sleep(900); // 400ms window + 500ms slack
        assertEquals("well past the window", 0, tracker.count(Attribute.IP, "10.0.0.4"));
    }

    /**
     * The test that separates a SLIDING window from a fixed window that resets.
     *
     * 1000ms window. Ten requests at t=0, five at t~700. Checked at t~1400: the first batch is
     * ~1400ms old (gone) and the second is ~700ms old (alive). A fixed window resetting on a
     * boundary would answer 0 or 15 here -- only a sliding window answers 5.
     */
    private static void testWindowSlidesRatherThanResetting() throws Exception {
        RequestTracker tracker = new RequestTracker(1_000, ipAndAgent());
        Request request = new Request("10.0.0.4", "curl/8.1", "/api");

        for (int i = 0; i < 10; i++) {
            tracker.record(request);
        }
        Thread.sleep(700);
        for (int i = 0; i < 5; i++) {
            tracker.record(request);
        }
        assertEquals("both batches are inside the window", 15,
                tracker.count(Attribute.IP, "10.0.0.4"));

        Thread.sleep(700); // t ~ 1400ms
        assertEquals("only the second batch survives", 5, tracker.count(Attribute.IP, "10.0.0.4"));
    }

    /**
     * A long silence must read as zero with no cleanup having run. Nothing deletes the old counts --
     * every bucket simply holds a period that is now too old to qualify.
     */
    private static void testCountIsZeroAfterALongIdleGap() throws Exception {
        RequestTracker tracker = new RequestTracker(300, ipAndAgent());
        tracker.record(new Request("10.0.0.4", "curl/8.1", "/api"));

        Thread.sleep(1_500); // five windows of silence
        assertEquals("after a long idle gap", 0, tracker.count(Attribute.IP, "10.0.0.4"));

        tracker.record(new Request("10.0.0.4", "curl/8.1", "/api"));
        assertEquals("recording after the gap starts from one", 1,
                tracker.count(Attribute.IP, "10.0.0.4"));
    }

    /**
     * The graded requirement: "add support for grouping by other attributes". A new grouping is
     * built at the CALL SITE. Nothing in RequestTracker changes.
     */
    private static void testNewGroupingNeedsNoChangeToTheClass() {
        Attribute endpoint = Attribute.of("Endpoint", Request::getEndpoint);
        RequestTracker tracker = new RequestTracker(60_000, List.of(Attribute.IP, endpoint));

        tracker.record(new Request("10.0.0.4", "curl/8.1", "/login"));
        tracker.record(new Request("10.0.0.9", "Chrome", "/login"));
        tracker.record(new Request("10.0.0.9", "Chrome", "/logout"));

        assertEquals("grouped by an attribute invented at the call site", 2,
                tracker.count(endpoint, "/login"));
        assertEquals("and the other endpoint", 1, tracker.count(endpoint, "/logout"));
    }

    /** No lost increments: every record must land, from every thread, under every grouping. */
    private static void testConcurrentRecordersLoseNoIncrements() throws Exception {
        RequestTracker tracker = new RequestTracker(60_000, ipAndAgent());
        Request request = new Request("10.0.0.4", "curl/8.1", "/api");
        int threads = 16;
        int perThread = 20_000;

        runConcurrently(threads, () -> {
            for (int i = 0; i < perThread; i++) {
                tracker.record(request);
            }
        });

        assertEquals("every increment landed under IP", (long) threads * perThread,
                tracker.count(Attribute.IP, "10.0.0.4"));
        assertEquals("and under the other grouping", (long) threads * perThread,
                tracker.count(Attribute.BROWSER_AGENT, "curl/8.1"));
    }

    /**
     * count() takes the same per-key monitor as record(), so a monitoring thread hammering count()
     * must not lose or duplicate anything, and must never observe a torn bucket.
     */
    private static void testConcurrentReadersDoNotDisturbRecorders() throws Exception {
        RequestTracker tracker = new RequestTracker(60_000, ipAndAgent());
        Request request = new Request("10.0.0.4", "curl/8.1", "/api");
        int recorders = 8;
        int perThread = 20_000;

        List<Thread> threads = new ArrayList<>();
        boolean[] stop = { false };
        long[] worstSeen = { Long.MAX_VALUE };

        Thread reader = new Thread(() -> {
            while (!stop[0]) {
                long seen = tracker.count(Attribute.IP, "10.0.0.4");
                synchronized (worstSeen) {
                    if (seen < 0) worstSeen[0] = seen;   // a negative count would mean a torn read
                }
            }
        }, "reader");
        reader.setDaemon(true);
        reader.start();

        for (int t = 0; t < recorders; t++) {
            Thread recorder = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    tracker.record(request);
                }
            }, "recorder-" + t);
            recorder.setDaemon(true);
            recorder.start();
            threads.add(recorder);
        }
        for (Thread thread : threads) {
            joinOrFail(thread);
        }
        stop[0] = true;
        joinOrFail(reader);

        assertEquals("recording is unaffected by concurrent counting",
                (long) recorders * perThread, tracker.count(Attribute.IP, "10.0.0.4"));
        assertTrue("count() never returned a negative (torn) value", worstSeen[0] == Long.MAX_VALUE);
    }

    private static void testInvalidConstructorArgumentsRejected() {
        assertTrue("windowMillis of 0 must be rejected",
                constructorRejects(() -> new RequestTracker(0, ipAndAgent())));
        assertTrue("a negative window must be rejected",
                constructorRejects(() -> new RequestTracker(-1, ipAndAgent())));
        assertTrue("bucketCount of 0 must be rejected",
                constructorRejects(() -> new RequestTracker(1_000, 0, ipAndAgent())));
        assertTrue("an empty groupBy must be rejected",
                constructorRejects(() -> new RequestTracker(1_000, List.of())));
    }

    private static void testNullRequestRejected() {
        RequestTracker tracker = new RequestTracker(60_000, ipAndAgent());
        boolean rejected = false;
        try {
            tracker.record(null);
        } catch (NullPointerException expected) {
            rejected = true;
        }
        assertTrue("record(null) must be rejected", rejected);
    }

    // ---------- harness ----------

    private static List<Attribute> ipAndAgent() {
        return Arrays.asList(Attribute.IP, Attribute.BROWSER_AGENT);
    }

    private static void runConcurrently(int threads, Runnable body) {
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(body, "worker-" + t);
            worker.setDaemon(true);
            worker.start();
            workers.add(worker);
        }
        for (Thread worker : workers) {
            joinOrFail(worker);
        }
    }

    private static void joinOrFail(Thread thread) {
        try {
            thread.join(30_000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            throw new AssertionError("FAILED: thread '" + thread.getName() + "' did not finish");
        }
    }

    private static boolean constructorRejects(Runnable construction) {
        try {
            construction.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static void assertTrue(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError("FAILED: " + message);
        }
    }

    private static void assertEquals(String message, long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError("FAILED: " + message + " (expected " + expected + ", got " + actual + ")");
        }
    }
}
