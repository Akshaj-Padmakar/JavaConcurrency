# Parallel Web Crawler

> Interview form: *"Crawl a site with a fixed pool of workers. Don't fetch the same page twice."*
> Also **LeetCode 1242 — Web Crawler Multithreaded**. Say so if you've seen it.
> The crawl is easy. Knowing when the crawl is **finished** is the problem.

> Start from a seed URL. Fetch the page, pull out its links, and follow each one you haven't seen
> before — using a fixed set of worker threads. Return only when the whole reachable site has been
> fetched. Never fetch the same page twice, and don't loop forever on links that point backwards.

## What you're building

The engine underneath a crawler, a link checker, or a site indexer. Fetching is slow — it's a network
round trip — so a fixed pool of workers fetches many pages at once, and each page a worker fetches
produces *more* work for the same pool.

That last part is what makes it interesting: **the work discovers itself**. You cannot know how many
pages there are until you've fetched them all, so you can never say "we're done when N jobs finish."

The same shape shows up as parallel GC mark, dependency-closure download, and reachability over a
permission graph. All of them are **parallel map over a directed graph with claim-once**, and all of
them share the one genuinely hard requirement below.

## A tiny example

```
http://site/
├── /blog
│   └── /post-42                  ← this is page #42
├── /archive
│   └── /post-42?ref=archive      ← ALSO page #42. Same page, two URLs.
└── a link back to /              ← points at the seed
```

Two workers. The right-hand column is "jobs handed out but not yet reported finished":

| Step | What happens | Outstanding |
| ---- | ------------ | ----------- |
| 1  | Main hands the seed `/` to the pool                                 | 1 |
| 2  | Worker A fetches `/`, finds 3 links                                 | 1 |
| 3  | A claims `/blog`, hands it off                                      | 2 |
| 4  | A claims `/archive`, hands it off                                   | 3 |
| 5  | A looks at the third link → it **is** `/`, already claimed → skip    | 3 |
| 6  | A finishes with `/`                                                 | 2 |
| 7  | A takes `/blog`, B takes `/archive` — at the same time               | 2 |
| 8  | A sees `/post-42`, claims it, hands it off                          | 3 |
| 9  | B sees `/post-42?ref=archive` — **already claimed by A** → skip      | 3 |
| 10 | A and B both finish their pages                                      | 1 |
| 11 | Someone fetches page #42 — slow, a real HTTP request                 | 1 |
| 12 | Finished → counter hits **0** → the caller is released               | 0 |

## The three hard parts

**1. The same page under two URLs.** Steps 8–9. `?ref=archive` is a different string but the same
page. Fetch it twice and you've wasted a request *and* double-counted it. So before anyone starts on
a page it has to be **claimed**, and exactly one claimer must win — even when two workers try at the
same instant.

**2. Links pointing backwards.** Step 5. `/` links to itself via the third link, which leads to `/`,
forever. The claim check is what stops it: `/` was claimed back at step 1, so the second look at it
goes nowhere.

**3. Knowing when you are finished.** This is the real one. Look at step 10 — the counter is at 1 and
**nobody is doing anything** for a split second. The last worker just finished a page and hasn't
picked up page #42 yet. Ask "are all the workers idle? then we're done" and you would return having
missed a page.

So you cannot use "is the queue empty" or "is anybody busy" as your finish signal. You need a count
of *jobs handed out but not yet reported back*, and it must be impossible for that count to touch
zero while work still remains.

## The API

```java
interface PageSource {
    List<Page> linksOn(Page page);        // links found on a page; empty for a leaf
}

class Page {
    long   pageId;                        // identity — the canonical page, not the URL string
    String url;                           // for logging only
    void   fetch();                       // the HTTP request. Slow. Exactly once per page.
}

class WebCrawler {
    WebCrawler(PageSource source, int workerCount);
    void crawl(Page seed);                // blocks; returns only when the crawl is complete
}
```

```java
new WebCrawler(source, 8).crawl(new Page(1, "http://site/"));
```

Two things to notice about this shape:

- **`PageSource` is a lookup call, not a prebuilt map.** You're handed a way to *ask* what a page
  links to — that's the fetch. Nobody hands you the whole graph up front, because building it would
  already be the job. This is why the total work is unknowable in advance. For tests, back it with an
  in-memory `Map<Page, List<Page>>`.
- **`Page` identity is `pageId`, never `url`.** `equals`/`hashCode` must use the canonical page id.
  That one choice is what collapses `/post-42` and `/post-42?ref=archive` into a single claim. Key on
  the URL string instead and every requirement below still passes except the one that matters.

## Constraint: build the primitives yourself

`Thread`, `synchronized`, `wait`/`notify` — or `Lock` + `Condition` if you prefer them.

**No** `ExecutorService`, `CountDownLatch`, `ConcurrentHashMap`, or `AtomicInteger`.
`MyThreadPool` from [P06](../P06_ThreadPoolWithShutdown/Problem.md) is yours to reuse.

## Requirements

- **Exactly once per page**, however many URLs lead to it.
- **Cycles terminate.** A link back to the seed must not restart the crawl.
- **Fixed pool.** Workers submit more work into the same bounded pool they run on.
- **`crawl()` blocks until truly done** — the last fetch *finished*, not merely handed out.
- **One dead link doesn't wedge the crawl.** A page throwing on fetch must not hang the caller or
  kill a worker.
- **Clean exit.** Pool shut down, no threads left running.

## Edge cases

`null` or empty source · `null` seed · a page with no links · a page linking to itself · the same
link listed twice · a seed that isn't in the source at all · a page that throws mid-fetch.

## Questions to answer before you code

1. Why can't "the work queue is empty" be your termination condition? Write the interleaving where it
   returns too early.
2. "All workers are idle" fails too — point at the step in the trace above where it would fire
   wrongly.
3. Where exactly must the counter be incremented relative to handing off the task? What breaks if you
   increment *inside* the task instead of before submitting it?
4. Where must the decrement live so that a page throwing mid-fetch still releases the caller?
5. The visited set and the counter answer two different questions. State both in one sentence each,
   and say why neither can substitute for the other.
6. Two workers see the same link at the same instant. Which two operations must be one indivisible
   step, and what is that pattern called?
7. Two URLs, one page. What do `equals`/`hashCode` key on — and which single test fails if you key on
   the URL string?
8. A worker submits more work into the pool it is itself running on. When is that safe, and what
   would make it deadlock?
9. What should `crawl()` do if the seed is `null`, or has already been visited?
10. **Politeness:** at most *k* concurrent fetches per host. Where would that constraint live, and
    what new hazard does it introduce given your answer to Q8?
11. The visited set grows to millions of URLs. What would you do about memory, and what correctness
    property would you be trading away?

## The jargon, once it makes sense

| Plain version | The term |
| ------------- | -------- |
| Link graph where two URLs reach one page and links loop back | a **directed graph with cycles**, not a tree |
| Fan out into linked pages, following each as far as it goes | **parallel reachability** — order doesn't matter here |
| "Claim it before you start" | the **visited set**, updated with an **atomic check-and-mark** (test-and-set) |
| Two workers claiming the same page at once | a **race condition** on a **check-then-act** sequence |
| The counter of jobs handed out but not reported back | **in-flight counting** for **termination detection** |
| Fetch the page | `fetch()` — the per-node payload |
| "What does this page link to?" | `linksOn()` — the **adjacency** lookup |
| Blocking until the count hits zero | a **latch** — which you now have to build by hand |
| At most *k* requests per host | **politeness / rate limiting**, a per-key concurrency cap |

**Watch out for the trap:** the visited set tells you what has been *started*. The counter tells you
what has *finished*. They answer different questions and neither can substitute for the other.
