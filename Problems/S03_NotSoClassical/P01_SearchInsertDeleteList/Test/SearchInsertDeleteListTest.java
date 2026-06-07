package Problems.S03_NotSoClassical.P01_SearchInsertDeleteList.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import Problems.S03_NotSoClassical.P01_SearchInsertDeleteList.SearchInsertDeleteList;
import Problems.S03_NotSoClassical.P01_SearchInsertDeleteList.SearchInsertDeleteLock;

public class SearchInsertDeleteListTest {

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        int passed = 0;
        int failed = 0;

        if (runTest("emptySearchReturnsFalse", SearchInsertDeleteListTest::emptySearchReturnsFalse))
            passed++;
        else
            failed++;
        if (runTest("insertThenSearchReturnsTrue", SearchInsertDeleteListTest::insertThenSearchReturnsTrue))
            passed++;
        else
            failed++;
        if (runTest("deleteHead", SearchInsertDeleteListTest::deleteHead))
            passed++;
        else
            failed++;
        if (runTest("deleteMiddle", SearchInsertDeleteListTest::deleteMiddle))
            passed++;
        else
            failed++;
        if (runTest("deleteTail", SearchInsertDeleteListTest::deleteTail))
            passed++;
        else
            failed++;
        if (runTest("deleteMissingTerminates", SearchInsertDeleteListTest::deleteMissingTerminates))
            passed++;
        else
            failed++;
        if (runTest("manyInsertersAndSearchersComplete", SearchInsertDeleteListTest::manyInsertersAndSearchersComplete))
            passed++;
        else
            failed++;
        if (runTest("mixedConcurrencyCompletes", SearchInsertDeleteListTest::mixedConcurrencyCompletes))
            passed++;
        else
            failed++;

        System.out.println();
        System.out.println("====================================");
        System.out.println("PASSED: " + passed);
        System.out.println("FAILED: " + failed);
        System.out.println("====================================");

        if (failed > 0) {
            System.exit(1);
        }
    }

    private static boolean runTest(String name, ThrowingRunnable test) {
        try {
            test.run();
            System.out.println("[PASS] " + name);
            return true;
        } catch (Throwable t) {
            System.out.println("[FAIL] " + name);
            t.printStackTrace(System.out);
            return false;
        }
    }

    private static SearchInsertDeleteList<Integer> newList() {
        return new SearchInsertDeleteList<>(new SearchInsertDeleteLock());
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertCompletes(String name, Callable<Void> task, long timeoutMs) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> future = executor.submit(task);
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AssertionError(name + " timed out after " + timeoutMs + " ms", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void emptySearchReturnsFalse() throws Exception {
        SearchInsertDeleteList<Integer> list = newList();
        assertFalse(list.search(10), "search on empty list should return false");
    }

    private static void insertThenSearchReturnsTrue() throws Exception {
        SearchInsertDeleteList<Integer> list = newList();

        list.insert(1);
        list.insert(2);
        list.insert(3);

        assertTrue(list.search(1), "1 should be present");
        assertTrue(list.search(2), "2 should be present");
        assertTrue(list.search(3), "3 should be present");
        assertFalse(list.search(4), "4 should not be present");
    }

    private static void deleteHead() throws Exception {
        SearchInsertDeleteList<Integer> list = newList();

        list.insert(1);
        list.insert(2);
        list.insert(3);

        assertTrue(list.delete(1), "delete head should return true");
        assertFalse(list.search(1), "head should be removed");
        assertTrue(list.search(2), "2 should still be present");
        assertTrue(list.search(3), "3 should still be present");
    }

    private static void deleteMiddle() throws Exception {
        SearchInsertDeleteList<Integer> list = newList();

        list.insert(1);
        list.insert(2);
        list.insert(3);

        assertTrue(list.delete(2), "delete middle should return true");
        assertTrue(list.search(1), "1 should still be present");
        assertFalse(list.search(2), "2 should be removed");
        assertTrue(list.search(3), "3 should still be present");
    }

    private static void deleteTail() throws Exception {
        SearchInsertDeleteList<Integer> list = newList();

        list.insert(1);
        list.insert(2);
        list.insert(3);

        assertTrue(list.delete(3), "delete tail should return true");
        assertTrue(list.search(1), "1 should still be present");
        assertTrue(list.search(2), "2 should still be present");
        assertFalse(list.search(3), "3 should be removed");
    }

    private static void deleteMissingTerminates() throws Exception {
        SearchInsertDeleteList<Integer> list = newList();

        list.insert(1);
        list.insert(2);
        list.insert(3);

        assertCompletes("deleteMissingTerminates", () -> {
            boolean removed = list.delete(99);
            assertFalse(removed, "delete of missing element should return false");
            return null;
        }, 1000);
    }

    private static void manyInsertersAndSearchersComplete() throws InterruptedException, ExecutionException {
        final SearchInsertDeleteList<Integer> list = newList();

        for (int i = 0; i < 200; i++) {
            list.insert(i);
        }

        int searcherThreads = 20;
        int inserterThreads = 5;

        ExecutorService pool = Executors.newFixedThreadPool(searcherThreads + inserterThreads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(searcherThreads + inserterThreads);

        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < searcherThreads; i++) {
            final int id = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < 200; k++) {
                        int value = (id + k) % 200;
                        list.search(value);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            }));
        }

        for (int i = 0; i < inserterThreads; i++) {
            final int base = 1000 * (i + 1);
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < 100; k++) {
                        list.insert(base + k);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            }));
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "searchers and inserters should complete");
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdownNow();
    }

    private static void mixedConcurrencyCompletes() throws Exception {
        final SearchInsertDeleteList<Integer> list = newList();

        for (int i = 0; i < 100; i++) {
            list.insert(i);
        }

        int threads = 30;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int id = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < 50; k++) {
                        int value = (id * 31 + k) % 150;
                        int op = (id + k) % 3;

                        if (op == 0) {
                            list.search(value);
                        } else if (op == 1) {
                            list.insert(1000 + value);
                        } else {
                            list.delete(value);
                        }
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            }));
        }

        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "mixed operations should complete");
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdownNow();
    }
}
