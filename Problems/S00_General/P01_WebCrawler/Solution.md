# Parallel Web Crawler — Solution

> See [Problem.md](Problem.md). Implementation: `WebCrawler.java`, `Test/WebCrawlerTest.java`.
> Also LeetCode 1242. **Say so if you've seen it** — their doc calls out integrity explicitly.

## 1. The idea

Two independent mechanisms, one per guarantee.

**Exactly-once** is an atomic check-and-mark on the visited set: a page is *claimed* before any task
for it is submitted, and only the winner submits.

**Termination** is reference counting: a counter of in-flight tasks, incremented by the *submitting*
thread before it hands the task off, decremented in a `finally`; whoever drives it to zero trips a
one-shot signal the caller waits on.

Neither can do the other's job. **The visited set says what has been *started*. The counter says what
has *finished*.**

## 2. State model

| Field | Meaning |
|---|---|
| `pageSource` | injected `linksOn()` lookup — the only way the crawl learns the graph |
| `visited` | claimed pages; every access inside `synchronized (visited)` |
| `activeTask` | tasks handed out but not yet reported finished |
| `completionLatch` | tripped once `activeTask` reaches 0 |
| `threadPool` | fixed workers; **tasks submit more tasks into the pool they run on** |

## 3. Mechanism, and the traps

```java
for (Page ch : pageSource.linksOn(node)) {
    if (alreadyVisited(ch)) continue;        // 1. claim  -> only the winner proceeds
    activeTask.incrementAndGet();            // 2. count  -> in the SUBMITTING thread
    threadPool.submit(new dfs(ch, node));    // 3. submit
}
```

**Claim → count → submit. Every time.**

- Claim *after* submitting → two workers queue the same page → fetched twice.
- Count *after* submitting, or inside the child's `run()` → the parent can decrement to zero and trip
  the latch **before its child starts**. `crawl()` returns on an unfinished crawl. Measured, §9.

**Why the counter can't hit zero early.** A thread may only increment while it still holds its own
un-decremented reference. So while any task is alive the count is ≥ 1, and zero is reached exactly
once, by the genuinely last task. Ordinary reference counting: *you can only add a reference while
holding one.*

**The decrement lives in a `finally`.** Without it, one throwing `fetch()` leaks a reference, the
count never reaches zero, and the caller blocks forever — one dead link wedging an entire crawl.

**Why not just block on the children?** If each task submitted its links and waited for them,
termination would be structural and free. It also deadlocks: on a pool of N threads a parked parent
still occupies a thread, so any graph deeper than N parks every worker waiting for children that can
never be scheduled.

> **Never block inside a pool task on work that must run in that same pool.**

## 4. What to ask the interviewer

1. "Same host only, or the whole web? That's the difference between a bounded and an unbounded crawl."
2. "Is there a politeness budget — max concurrent requests per host?"
3. "How do I canonicalise a URL? Two URLs for one page is the whole dedup question."
4. "May I use `java.util.concurrent`, or do you want the pool and latch hand-rolled?"
5. "Does the crawl need to be resumable, or is one shot enough?"

## 5. Answers to Problem.md §7

1. **"Queue empty" returns early.** A worker has already dequeued page P and is mid-fetch; the queue
   is momentarily empty; P's links haven't been discovered yet. You'd return having crawled nothing
   below P.
2. **"All workers idle" fails at step 10** of the trace — both workers have just finished their pages
   and nobody has picked up page #42 yet. There is a real instant where every worker is idle *and*
   work remains.
3. **Increment before submitting, in the submitting thread.** Increment inside the child's `run()` and
   the parent's `finally` can reach zero before the child even starts.
4. **In a `finally`.** A page throwing mid-fetch must still report its reference back, or the caller
   never wakes.
5. **Visited set:** "has this page been *started*?" **Counter:** "how many started pages have not yet
   *finished*?" The set can't tell you about completion; the counter can't tell you about identity.
6. **The check and the mark**, as one indivisible step — a test-and-set. Otherwise it's check-then-act
   and two workers both see "unvisited".
7. **`equals`/`hashCode` key on `pageId`.** Key on the URL string and
   `testAliasUrlAndCycleFetchedExactlyOnce` fails — `/post-42` and `/post-42?ref=archive` get fetched
   twice. Every other test still passes, which is what makes it dangerous.
8. **Safe only if submission never blocks.** With a bounded, blocking queue every worker can end up
   parked inside `execute()` waiting for a slot, leaving nobody at `take()` to free one. That's the
   same rule as §3: don't block a pool task on the pool.
9. **`null` seed → return immediately.** Already-visited seed → return; note the crawl is one-shot, so
   a second `crawl()` silently no-ops (§10).
10. **A per-host semaphore, acquired between claim and fetch.** The hazard is exactly Q8: a worker
    holding a pool thread while blocked on a host permit. Saturate one host and every worker can be
    parked on it while other hosts sit idle. The fix is to not let a blocked host consume a worker —
    per-host queues, or a `tryAcquire` that re-queues the page.
11. **Bloom filter, or a disk-backed set.** The trade is exactness: a Bloom false positive means a
    page is silently *never* fetched. You'd be swapping "exactly once" for "at most once, bounded
    memory" — state that out loud, because it changes the guarantee.

## 6. What the interviewer is checking

| Signal | What it proves |
|---|---|
| Rejecting "queue empty" *with an interleaving* | You've actually hit premature termination |
| Claim → count → submit, in that order | You know both orderings that break it |
| Decrement in a `finally` | You thought about the failure path |
| "Visited says started, counter says finished" | You see them as two mechanisms, not one |
| Naming the pool-reentrancy hazard | You know why the natural design deadlocks |
| Asking about canonical URLs | You spotted that identity *is* the dedup guarantee |

## 7. What fails you

- Terminating on "queue empty" or "workers idle".
- Claiming the page inside the task instead of before submitting it.
- Incrementing the counter in the child rather than the parent.
- Decrement outside a `finally`.
- `equals`/`hashCode` on the URL string.
- A parent that waits for its children inside the pool.
- Not saying you've seen LeetCode 1242 when you have.

## 8. Extensions

**"Be polite — max *k* concurrent fetches per host."** → Per-host semaphore acquired before `fetch()`.
*Trap:* a worker blocked on a host permit still occupies a pool thread (§5.10).

**"The visited set won't fit in memory."** → Bloom filter or disk-backed set. *Trap:* false positives
silently drop pages — you've changed the guarantee from exactly-once to at-most-once.

**"Do it with no `java.util.concurrent`."** → `MyThreadPool` from P06, a plain `int` under a monitor,
and a hand-rolled one-shot latch. Two traps, both real:
- **The counter.** `decrementAndGet() == 0` is atomic for free. With a plain `int`, the decrement
  *and* the zero-test must be in the **same** synchronized block — split them and two threads both
  observe non-zero, nobody signals, the caller hangs.
- **The pool.** `MyThreadPool`'s queue is bounded and `execute()` blocks in `put()` while holding the
  pool lock. Here workers *are* submitters, so all of them can be inside `execute()` with none left
  at `take()`. The wide-page test passes today only because `Executors.newFixedThreadPool` uses an
  **unbounded** queue. Pick a policy and defend it: unbounded queue, caller-runs on full, or a
  separate pool.

**"Now compute a fingerprint per directory / per subtree."** → That's a **fold**, not a map: a node's
value depends on its children's, so traversal and computation run in opposite directions. *Trap:* you
cannot make the parent wait (§3), so you need a per-node pending-children counter and let the last
child compute the parent — the global counter generalises to one per node.

**"Run it on 10 machines."** → Shard the visited set by URL hash; each shard owns claim decisions.
*Trap:* termination detection across machines is the hard part — this is exactly the distributed
termination problem, and the naive "all queues empty" is wrong for the same reason as §5.1.

**"Store what you fetched."** → `fetch()` returns `void` today. A real crawler writes to an index —
another shared structure written from N workers, needing its own synchronisation.

## 9. Bug log

| Bug | Symptom | Lesson |
|---|---|---|
| Counter incremented **inside** the child task instead of before submitting | Measured on this code: `crawl()` returned after 62 ms having reached **1 of 5** pages, with no error anywhere. Premature completion → premature `shutdown()` → every later submission rejected → whole subgraphs silently dropped | The increment must happen in the **submitting** thread, before hand-off. "You can only add a reference while holding one" |

```
>>> crawl() returned after 62 ms
>>> pages reached at return: [0]
>>> pages reached 2s later : [0, 1]   (expected [0, 1, 2, 3, 4])
```

The failure is silent — no exception, no hang, just a short result. That's what makes premature
termination worse than a deadlock: a deadlock announces itself.

## 10. Known limitations — deliberate trades

- **A failed page silently shrinks the result.** When `linksOn()` throws, the `finally` keeps the
  crawl alive — good — but that subgraph is skipped and *nothing records it*. `crawl()` returns
  normally and the caller cannot distinguish a complete crawl from one missing a branch. Real
  crawlers collect per-page failures and surface them alongside the output.
- **`fetch()`'s result goes nowhere** — it returns `void`, so the page content is discarded.
- **One-shot.** The latch can't be reset and the pool is shut down; a second `crawl()` silently
  no-ops because the seed is already in `visited`.
- **`visited` is a `Map<Page, Boolean>` under one lock** — correct, since every access goes through
  `alreadyVisited`, but it serialises every claim across all workers. `Set` + `return visited.add(p)`
  collapses the method to one line; a striped set removes the contention point.
- **`System.out.println` in the hot path** serialises workers on the stdout lock.
- The `new Page(-1, "-1")` sentinel parent exists only to feed that log line, and `-1` is a value a
  real page id could hold.
- **Uses `java.util.concurrent`** — `ExecutorService`, `AtomicInteger`, `CountDownLatch`. Defensible
  (the subject is claim-once and termination, not the pool), but §8 is the insurance.

## 11. Verified

Suite passes 3/3 runs, 8 tests, no flakes.

**Covered:** a 5-deep chain proves `crawl()` blocks until the *last* page finishes, not the first ·
alias URL (two URLs, one `pageId`) plus a cycle back to the seed, each fetched exactly once · a
200-link hub page · the same link listed three times · a self-linking page terminates · a page that
throws mid-fetch still releases the caller and the rest of the crawl completes · `null` seed is a
no-op · **10 random graphs of 60 pages** with cycles and shared links, checked against an
independently computed single-threaded BFS reachable set — every reachable page fetched exactly once,
nothing unreachable fetched.

**Not covered:** the primitives-only variant (§8) — the bounded-queue deadlock is reasoned, not
measured · politeness / per-host limits · memory bounds on `visited` · interrupting a crawl mid-flight
· `fetch()` is a 50 ms sleep, so nothing exercises real I/O latency or failure modes.

## 12. 30-second recall

> **Parallel map over a directed graph with claim-once.** Two mechanisms, two guarantees: a **visited
> set** (atomic check-and-mark) for exactly-once, an **in-flight counter** for termination. *The set
> says what **started**; the counter says what **finished*** — neither substitutes for the other.
> Order is **claim → count → submit**, and the count happens in the **submitting** thread: increment
> inside the child and the parent's `finally` trips the latch before the child runs, so `crawl()`
> returns early and **silently**. Decrement in a `finally` or one dead link wedges the crawl. Zero is
> reached exactly once because *you can only add a reference while holding one*. **Never block a pool
> task on work that must run in that pool** — that's why the parent doesn't wait for its children, and
> why a bounded blocking queue deadlocks when workers are also submitters. Identity is the canonical
> **page**, never the URL string. Same shape as parallel GC mark, dependency-closure download, and
> reachability. Ask about politeness and canonicalisation in the first two minutes.
