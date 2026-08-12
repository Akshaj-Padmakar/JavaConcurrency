# Windowed Request Tracker

> **This is Rubrik's published sample question for the system coding round.** Verbatim:
> _"Implement a thread-safe data structure which can keep track of the number of incoming requests
> grouped by IP Address over a time window. Add support for grouping by other attributes such as
> BrowserAgent."_

## The system

Rubrik Security Cloud continuously watches request patterns to spot trouble: a client hammering the
API, a credential replaying from a new address, a burst of file-modification calls that looks like
ransomware encrypting a share. All of it rests on one primitive — **how many requests has this
client made in the last N seconds?**

It sits on the hot path. Every inbound request calls `record()` from whichever thread served it, and
policy checks call `count()` concurrently. It runs for months without a restart, so memory must be
bounded no matter how much traffic or how many distinct clients turn up.

## Problem

```java
interface Attribute {                        // a key extractor
    String name();                           // "IP", "BrowserAgent"
    String valueOf(Request req);
}

class RequestTracker {
    RequestTracker(long windowMillis, int bucketCount, List<Attribute> groupBy);

    void record(Request req);                // hot path; many threads
    long count(Attribute attr, String value);// requests seen in the last window
}
```

`count(IP, "10.0.0.4")` answers *"how many requests from 10.0.0.4 in the last `windowMillis`?"*

## The part that is actually being tested

> _"Add support for grouping by other attributes such as BrowserAgent."_

That sentence is an **extensibility** requirement hidden inside a concurrency question. If your
design has `Map<String /* ip */, Counter>` in it, adding BrowserAgent means editing the class —
and you've failed the follow-up even with flawless locking.

Supporting a new attribute should mean *passing another `Attribute`*, nothing more. Assume the
interviewer asks for it, so build for it from the first line.

## Example

```
window = 60s, buckets = 60 (1s each), groupBy = [IP, BrowserAgent]

t=0s    record(ip=10.0.0.4, agent=curl/8.1)
t=0s    record(ip=10.0.0.4, agent=Chrome)
t=30s   record(ip=10.0.0.9, agent=curl/8.1)

t=31s   count(IP, "10.0.0.4")           -> 2
        count(IP, "10.0.0.9")           -> 1
        count(BrowserAgent, "curl/8.1") -> 2      same requests, different grouping
        count(IP, "10.0.0.7")           -> 0      never seen

t=61s   count(IP, "10.0.0.4")           -> 0      both fell out of the window
        count(BrowserAgent, "curl/8.1") -> 1      the t=30s one is still live
```

One `record()` updates every configured grouping. The counts are different views of the same traffic.

## Constraint: no concurrency libraries

`Thread`, `synchronized`, `wait`/`notify` — or `Lock` + `Condition` if you prefer. **No**
`ConcurrentHashMap`, `AtomicLong`, `ExecutorService`, or anything from `java.util.concurrent` doing
the real work for you.

## Requirements

- **Thread-safe.** Concurrent `record()` from many threads, concurrent `count()` alongside them. No
  lost increments, no torn reads.
- **Correct at the boundary.** A request at `t=0` must not be counted at `t=windowMillis+1`.
- **Bounded memory.** Constant per tracked key regardless of request volume. Storing a timestamp per
  request is a memory leak wearing a disguise.
- **Extensible grouping.** New attribute = new `Attribute`, no edits to `RequestTracker`.
- **Scales across keys.** Two threads recording for *different* IPs should not block each other. Get
  it correct with one lock first, then say how you'd narrow it.
- **Testable without sleeping.** A test must be able to advance time by 30 seconds instantly.

## Edge cases to handle out loud

Key never seen · all buckets stale after a long idle gap · a burst inside one bucket · `count()`
racing a `record()` that's mid-update · `windowMillis` not divisible by `bucketCount` · null or
missing attribute value on a request · the same request recorded under several attributes.

## Extensions to expect

1. **Rate limiting** — `boolean allow(Request)` that rejects past a threshold. What changes?
2. **Idle key eviction** — millions of distinct IPs over a month. Where does the memory go, and who
   reclaims it? Background reaper thread versus reclaiming lazily on access — argue the trade.
3. **Per-attribute windows** — 60s for IP, 5m for BrowserAgent.
4. **Multiple machines** — each node sees part of the traffic. What breaks, and what does exactness
   cost you?

## Two things to say before you're asked

- **Which clock.** Wall-clock time can jump backward on an NTP correction mid-window. Say why you're
  using a monotonic source, and make it injectable so tests don't sleep.
- **Lock granularity.** State that one global lock is your correct-first version and that you'd move
  to per-key locking next. Their prep doc says they grade on responding to hints — leaving yourself
  a visible next step is better than opening at maximum complexity.
