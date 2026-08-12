package Problems.S00_General.P01_WebCrawler.Test;

import Problems.S00_General.P01_WebCrawler.WebCrawler;
import Problems.S00_General.P01_WebCrawler.WebCrawler.Page;
import Problems.S00_General.P01_WebCrawler.WebCrawler.PageSource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * Plain main()-based tests, matching this repo's style (no JUnit).
 *
 * How "fetched exactly once" is measured: the crawler creates exactly one task per claimed page,
 * and every task calls pageSource.linksOn(page) exactly once. So counting linksOn() calls per pageId
 * gives the number of times that page was crawled -- every reachable page must show up with a
 * count of exactly 1, and nothing unreachable may show up at all.
 *
 * Counting through the PageSource keeps the tests independent of Page. Subclassing Page and
 * counting fetch() calls directly is the other option, and overriding it to a no-op would also drop
 * the suite's runtime from seconds to milliseconds -- every page currently pays fetch()'s 50 ms.
 */
public class WebCrawlerTest {

    public static void main(String[] args) {
        testChainFullyCrawledBeforeCrawlReturns();
        testAliasUrlAndCycleFetchedExactlyOnce();
        testHubPageFullyCrawled();
        testDuplicateLinkListedTwiceFetchedOnce();
        testSelfLinkingPageTerminates();
        testDeadLinkDoesNotWedgeTheCrawl();
        testNullSeedDoesNothing();
        testRandomGraphExactlyOnceAndComplete();

        System.out.println("All WebCrawler tests passed.");
    }

    /**
     * crawl() must block until the LAST page has finished -- not return once the seed is done.
     * A chain forces every page to be discovered by the one before it.
     */
    private static void testChainFullyCrawledBeforeCrawlReturns() {
        Page[] n = new Page[5];
        for (int i = 0; i < n.length; i++) {
            n[i] = new Page(i, "http://site/depth" + i);
        }
        Map<Page, List<Page>> web = new HashMap<>();
        for (int i = 0; i < n.length - 1; i++) {
            web.put(n[i], Arrays.asList(n[i + 1]));
        }

        RecordingPageSource source = new RecordingPageSource(web);
        new WebCrawler(source, 4).crawl(n[0]);

        assertEquals("every page in the chain must be crawled before crawl() returns",
                5, source.fetchedCount());
    }

    /** Two URLs, one page (alias/query-string) plus a page that links back to its ancestor (cycle). */
    private static void testAliasUrlAndCycleFetchedExactlyOnce() {
        Page home = new Page(1, "http://site/");
        Page blog = new Page(2, "http://site/blog");
        Page archive = new Page(3, "http://site/archive");
        Page postViaBlog = new Page(42, "http://site/post-42");
        Page postViaArchive = new Page(42, "http://site/post-42?ref=archive"); // same page

        Map<Page, List<Page>> web = new HashMap<>();
        web.put(home, Arrays.asList(blog, archive, home)); // last entry = cycle back to the seed
        web.put(blog, Arrays.asList(postViaBlog));
        web.put(archive, Arrays.asList(postViaArchive));

        RecordingPageSource source = new RecordingPageSource(web);
        new WebCrawler(source, 4).crawl(home);

        assertEquals("4 distinct pages reachable (the two post-42 URLs are one page)",
                4, source.fetchedCount());
        assertTrue("page 42 must be fetched exactly once, not once per URL",
                source.timesFetched(42) == 1);
        assertTrue("the cycle back to the seed must not refetch it",
                source.timesFetched(1) == 1);
    }

    /** A hub page linking to many others -- exercises the pool's queue and submission path. */
    private static void testHubPageFullyCrawled() {
        Page hub = new Page(0, "http://site/hub");
        List<Page> links = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            links.add(new Page(i, "http://site/page" + i));
        }
        Map<Page, List<Page>> web = new HashMap<>();
        web.put(hub, links);

        RecordingPageSource source = new RecordingPageSource(web);
        new WebCrawler(source, 4).crawl(hub);

        assertEquals("hub plus all 200 linked pages must be crawled", 201, source.fetchedCount());
        assertTrue("no page may be fetched twice", source.allFetchedExactlyOnce());
    }

    /** A page listing the same link three times must not queue it three times. */
    private static void testDuplicateLinkListedTwiceFetchedOnce() {
        Page seed = new Page(0, "http://site/");
        Page target = new Page(1, "http://site/dup");
        Map<Page, List<Page>> web = new HashMap<>();
        web.put(seed, Arrays.asList(target, target, target));

        RecordingPageSource source = new RecordingPageSource(web);
        new WebCrawler(source, 4).crawl(seed);

        assertEquals("seed + one target", 2, source.fetchedCount());
        assertTrue("a link listed three times is still one page",
                source.timesFetched(1) == 1);
    }

    /** A page that links to itself: the claim check is what stops the loop. */
    private static void testSelfLinkingPageTerminates() {
        Page seed = new Page(0, "http://site/");
        Map<Page, List<Page>> web = new HashMap<>();
        web.put(seed, Arrays.asList(seed));

        RecordingPageSource source = new RecordingPageSource(web);
        Thread caller = runCrawlOnDaemonThread(source, seed);

        assertTrue("a self-linking page must not loop forever", !caller.isAlive());
        assertEquals("only the seed exists", 1, source.fetchedCount());
    }

    /**
     * A dead link throws out of linksOn(). The decrement lives in a finally, so the counter still
     * reaches zero and the caller is released -- one 500 must not wedge the whole crawl.
     */
    private static void testDeadLinkDoesNotWedgeTheCrawl() {
        Page seed = new Page(0, "http://site/");
        Page broken = new Page(1, "http://site/500");
        Page fine = new Page(2, "http://site/ok");

        Map<Page, List<Page>> web = new HashMap<>();
        web.put(seed, Arrays.asList(broken, fine));

        RecordingPageSource source = new RecordingPageSource(web) {
            @Override
            public List<Page> linksOn(Page page) {
                List<Page> links = super.linksOn(page);
                if (page.getPageId() == 1) {
                    throw new RuntimeException("simulated HTTP 500 -- page could not be fetched");
                }
                return links;
            }
        };

        Thread caller = runCrawlOnDaemonThread(source, seed);

        assertTrue("crawl() must still return when a page throws", !caller.isAlive());
        assertEquals("the other pages must still have been crawled", 3, source.fetchedCount());
    }

    private static void testNullSeedDoesNothing() {
        RecordingPageSource source = new RecordingPageSource(new HashMap<>());
        new WebCrawler(source, 2).crawl(null);

        assertEquals("a null seed must crawl nothing and must not hang", 0, source.fetchedCount());
    }

    /**
     * The real test. Random link graphs with cycles and shared pages, checked against an
     * independently computed reachable set.
     */
    private static void testRandomGraphExactlyOnceAndComplete() {
        Random random = new Random(20260808L); // fixed seed -> reproducible failures
        int trials = 10;
        int pageCount = 60;

        for (int trial = 0; trial < trials; trial++) {
            Page[] pages = new Page[pageCount];
            for (int i = 0; i < pageCount; i++) {
                pages[i] = new Page(i, "http://site/n" + i);
            }

            Map<Page, List<Page>> web = new HashMap<>();
            for (int i = 0; i < pageCount; i++) {
                List<Page> links = new ArrayList<>();
                int linkCount = random.nextInt(4); // 0..3, cycles and repeats allowed
                for (int k = 0; k < linkCount; k++) {
                    links.add(pages[random.nextInt(pageCount)]);
                }
                web.put(pages[i], links);
            }

            Set<Long> reachable = reachableFrom(web, pages[0]);

            RecordingPageSource source = new RecordingPageSource(web);
            new WebCrawler(source, 8).crawl(pages[0]);

            assertEquals("trial " + trial + ": every reachable page must be crawled",
                    reachable.size(), source.fetchedCount());
            assertTrue("trial " + trial + ": no page may be fetched twice",
                    source.allFetchedExactlyOnce());
            assertTrue("trial " + trial + ": nothing unreachable may be fetched",
                    reachable.containsAll(source.fetchedPageIds()));
        }
    }

    // ---------- helpers ----------

    /** Single-threaded BFS, computed independently of the crawler, as the expected answer. */
    private static Set<Long> reachableFrom(Map<Page, List<Page>> web, Page seed) {
        Set<Long> seen = new HashSet<>();
        Deque<Page> queue = new ArrayDeque<>();
        seen.add(seed.getPageId());
        queue.add(seed);
        while (!queue.isEmpty()) {
            Page current = queue.poll();
            for (Page link : web.getOrDefault(current, Collections.emptyList())) {
                if (seen.add(link.getPageId())) {
                    queue.add(link);
                }
            }
        }
        return seen;
    }

    /** Runs crawl() on a daemon thread so a hang fails the test instead of blocking the suite. */
    private static Thread runCrawlOnDaemonThread(PageSource source, Page seed) {
        Thread caller = new Thread(() -> new WebCrawler(source, 4).crawl(seed));
        caller.setDaemon(true);
        caller.start();
        try {
            caller.join(5_000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return caller;
    }

    /** In-memory PageSource that records how many times each page was fetched. */
    private static class RecordingPageSource implements PageSource {
        private final Map<Page, List<Page>> web;
        private final Map<Long, Integer> fetched = new TreeMap<>();

        RecordingPageSource(Map<Page, List<Page>> web) {
            this.web = web;
        }

        @Override
        public List<Page> linksOn(Page page) {
            synchronized (fetched) {
                fetched.merge(page.getPageId(), 1, Integer::sum);
            }
            return web.getOrDefault(page, Collections.emptyList());
        }

        int fetchedCount() {
            synchronized (fetched) {
                return fetched.size();
            }
        }

        int timesFetched(long pageId) {
            synchronized (fetched) {
                return fetched.getOrDefault(pageId, 0);
            }
        }

        boolean allFetchedExactlyOnce() {
            synchronized (fetched) {
                return fetched.values().stream().allMatch(count -> count == 1);
            }
        }

        Set<Long> fetchedPageIds() {
            synchronized (fetched) {
                return new HashSet<>(fetched.keySet());
            }
        }
    }

    private static void assertTrue(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError("FAILED: " + message);
        }
    }

    private static void assertEquals(String message, int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("FAILED: " + message + " (expected " + expected + ", got " + actual + ")");
        }
    }
}
