# Windowed Request Tracker — Revision Sheet

> **Rubrik's published sample question.** See [Problem.md](Problem.md).
> Implementation: `RequestTracker.java`, `Test/RequestTrackerTest.java`.

## One-line idea

Per `(attribute, value)` key, keep a **ring of counters**, one per sub-interval of the window, each
tagged with the period it currently holds. `record()` bumps the slot for *now*, reclaiming it first
if it holds an older period. `count()` sums the slots whose period is still inside the window.
Old data is never deleted — it just stops qualifying.

The grouping is an **extractor**, not a field, which is what makes "also group by BrowserAgent" a
call-site change rather than an edit.

## State model

| Field | Meaning |
| ----- | ------- |
| `bucketMillis` | width of one sub-interval, derived from `windowMillis / bucketCount`, **rounded up** |
| `bucketCount` | how many sub-intervals; the accuracy/memory knob. Internal, defaulted to 60 |
| `groupBy` | the configured `Attribute`s — defensively copied |
| `indexes` | `Attribute -> ValueIndex`. Built in the constructor, **never** structurally modified |
| `ValueIndex` | `value -> Window` for one attribute; guarded by its own monitor |
| `Window` | `long[] counts` + `long[] periods` for one key; guarded by its own monitor |

## The mechanism, in three numbers

```java
period = floorDiv(now, bucketMillis)     // absolute, ever-increasing "which second is it"
slot   = floorMod(period, bucketCount)   // where that second lives in the ring
oldest = currentPeriod - bucketCount + 1 // the earliest period still inside the window
```

`period` is absolute; `slot` is `period % bucketCount`. That single conversion is the whole ring.

**`record`** — reclaim, then increment:

```java
if (periods[slot] != period) {   // the ring wrapped past this slot; its data is a full window old
    periods[slot] = period;
    counts[slot]  = 0;
}
counts[slot]++;
```

**`count`** — sum what still qualifies:

```java
for (slot : all) if (periods[slot] >= oldest) total += counts[slot];
```

### Why `periods[]` exists at all

`counts[]` alone is ambiguous: "12 requests" could be from this second or from a full window ago,
and the array cannot tell you which. The `(period, count)` pair can. Losing this is how people
accidentally build a counter that never forgets.

### `oldest = current - bucketCount + 1`, not `- bucketCount`

The range is inclusive at both ends, so periods `36…95` is exactly 60 values. Dropping the `+ 1`
gives you 61 buckets — one sub-interval too much window.

### `Long.MIN_VALUE` fill removes a branch

Untouched slots fail `periods[slot] >= oldest` for the same reason stale ones do. No emptiness flag,
no `if (neverWritten)` case.

### `floorDiv` / `floorMod`, not `/` and `%`

`System.nanoTime()` has an arbitrary origin and is routinely **negative**. `-5 % 60` is `-5` in Java,
which is an `ArrayIndexOutOfBoundsException` on the next line. This never shows up with a test clock
starting at zero — only on a real machine.

## The property worth quoting

> **"How do you expire old data?" — "I don't. I just stop counting it."**

Nothing deletes anything. No reaper thread, no timer, no cleanup pass. After ten minutes of silence
every slot still holds its old numbers; they simply fail a comparison. Memory is already bounded by
`bucketCount`, so there is nothing to reclaim.

## Bucket count is internal — and that is a design decision, not laziness

The caller's requirement is *"counts over the last N milliseconds."* `bucketCount` is the
accuracy/memory trade **for this particular implementation** — it would be meaningless if the
internals became a timing wheel — and a caller has no basis to choose it. So the front-door
constructor is `(windowMillis, groupBy)` and the knob is demoted to an overload.

Fixing the *count* rather than the *size* gives a clean invariant:

> **Memory per tracked key is constant regardless of window length.** A 60-second window and a
> 24-hour window both cost 60 counters per key.

Fixing the size instead (say, always 1-second buckets) would make a 24-hour window cost 86,400
counters per key.

### Round the bucket size up

`windowMillis / bucketCount` truncates, so the covered window comes out **shorter** than requested
and you silently under-count. `(windowMillis + bucketCount - 1) / bucketCount` covers slightly more
than asked instead.

Pick the direction deliberately: for rate limiting, over-covering rejects a hair early (safe);
under-covering lets extra traffic through — the thing you were trying to prevent.

### Accuracy: ±1 bucket, and where it comes from

At `t = 95,400 ms` the true window is `[35,400, 95,400]`. Period 35 covers `[35,000, 36,000)` — it
straddles the boundary, and a bucket is indivisible. Excluding it drops requests from
35,400–36,000 ms that are genuinely inside. That's the ±1 bucket error, and `bucketCount` is how you
size it. For a 10,000/minute abuse threshold it's irrelevant; for a strict billing quota it may not
be.

## The extensibility clause is the graded part

> *"Add support for grouping by other attributes such as BrowserAgent."*

An **enum** fails this — it's a closed set, so a new grouping means editing the file. So does a
`Request` that carries a single `(attribute, value)` pair, because then the caller has to fan out by
hand and `groupBy` becomes decoration.

The shape that passes:

```java
public interface Attribute {
    String name();
    String valueOf(Request request);
    static Attribute of(String name, Function<Request, String> extractor) { ... }
}
```

`Request` is a passive bag of fields; `Attribute` knows which field is the key; `record()` loops
`groupBy` and updates one `Window` per grouping. A new attribute is then a *call site*:

```java
Attribute endpoint = Attribute.of("Endpoint", Request::getEndpoint);
new RequestTracker(60_000, List.of(Attribute.IP, endpoint));
```

`testNewGroupingNeedsNoChangeToTheClass` pins exactly this.

## Locking: three levels, the outermost has none

```java
indexes.get(attribute)            // NO lock  -- built in the constructor, never modified after
       .getOrCreate(value, ...)   // lock per ATTRIBUTE
       .record(period, ...)       // lock per KEY
```

- `indexes` is a `final` field fully populated before the constructor returns, so reads need no
  synchronization.
- One lock per attribute means recording an IP never blocks recording a BrowserAgent.
- One lock per key means `10.0.0.4` and `10.0.0.9` contend **nowhere** during the actual counting.

`count()` takes the same per-key monitor, which is what stops it observing a torn bucket — without
it, a concurrent `record()` could reset `periods[slot]` after you'd already added `counts[slot]`, and
you'd sum two different seconds into one.

**What still serializes:** two threads recording under the *same attribute* briefly collide on the
map lookup, even for different values. That's the honest remaining bottleneck, and the fix is to
stripe `ValueIndex` into N sub-maps by `value.hashCode()` — hand-rolling what `ConcurrentHashMap`
does, which is why that's the natural thing to reach for when the library is allowed.

## Bugs found while building this

| Bug | Symptom |
| --- | ------- |
| Constructor took `bucketMillis` instead of `windowMillis` | `new RequestTracker(60_000, …)` gave a **one-hour** window, not 60 seconds. Same numbers, 60× different meaning, no compiler error |
| `Request` was a non-static inner class | callers couldn't write `new Request(...)` at all — it needed `tracker.new Request(...)`, i.e. a tracker to build the object you hand *to* the tracker |
| `Attribute` modelled as an `enum` | closed set; a new grouping required editing the class — failing the graded requirement |
| `Request` carried one `(attribute, value)` pair | inverted the responsibility: callers fan out by hand, `groupBy` becomes decoration, and `allow(request)` becomes impossible |
| `groupBy` not defensively copied | a caller mutating the list afterwards produced an **NPE on the hot path** inside `record()` |
| No argument validation | `windowMillis = 0` constructed fine, then threw `ArithmeticException: / by zero` from `currentPeriod()` — a stack trace pointing nowhere near the mistake |

The two constructor-shape bugs are worth remembering as a pair: **when two parameters are in the
same units and either could plausibly be meant, the compiler cannot help you.** Name the one the
caller thinks in (`windowMillis`) and derive the other.

## Deliberate trade: no injectable clock

`currentPeriod()` calls `System.currentTimeMillis()` directly. Consequences, accepted knowingly:

- **Every time-based test needs a real `Thread.sleep`.** The suite uses short windows (300–1000 ms)
  and generous margins so it runs in a few seconds and doesn't flake.
- **Bucket-boundary precision is untestable.** Asserting behaviour a few milliseconds either side of
  a boundary would be flaky by construction, so it isn't covered.
- **Wall-clock, not monotonic.** An NTP correction moving the clock backwards makes `currentPeriod()`
  decrease, and slots already tagged with a higher period drop out of counts until time catches up.
  `System.nanoTime()` has no such problem.

A `Clock` interface with a `SYSTEM` default is a two-line fix if any of those start mattering.

## Extensions to expect

1. **Rate limiting.** Do **not** let callers compose `record()` then `count()` — those are two
   atomic operations, and two threads at the boundary can both pass. Add a single
   `boolean allow(Request, long limit)` that records and checks inside one critical section. This is
   the same check-then-act trap as everywhere else.
2. **Idle key eviction.** A month of traffic means millions of dead `Window` objects, each 60
   counters. The window mechanism bounds memory *per key*, not the number of keys. Options: an LRU
   cap, or a lazy sweep that drops a `Window` whose slots are all stale when it's next touched —
   preferable, since it needs no extra thread.
3. **Per-attribute windows** — 60s for IP, 5m for BrowserAgent. `bucketMillis` moves from the tracker
   into the `ValueIndex`.
4. **Multiple machines.** Each node sees part of the traffic, so exact global counts require either
   a shared store on the hot path or a merge protocol. Usually you accept approximation.

## Known limitations

- Same-attribute map lookups serialize (see locking above).
- No key eviction — `Window` objects for dead IPs live forever.
- No injectable clock (see above).
- `count()` is O(bucketCount) — trivial at 60, worth noting if someone raises it to 60,000.
- Error message on the `bucketCount` guard says "must be less than 0" when it means the opposite,
  and `record(null)` says "request must be null". Both will send a debugger the wrong way.
- The redundant `(int)` cast on `Math.floorMod(long, int)` — `javac -Xlint` flags it.

## Verified

`Test/RequestTrackerTest.java` — plain `main()`-based, no JUnit. 13 cases, 5/5 clean runs.

- counts, multiple groupings from one `record()`, unknown value → 0
- unconfigured attribute → throws (not 0)
- null attribute value → skipped for that grouping, still counted for the others
- **expiry** past the window, and a long idle gap reading 0 with no cleanup having run
- **sliding, not resetting** — 10 requests, 700 ms, 5 more, 700 ms → expect **5**. A fixed window
  would answer 0 or 15. This is the test that catches the single most likely wrong implementation
- **new grouping built at the call site** — the graded requirement
- **16 threads × 20,000 records** with none lost, under *both* groupings
- **8 recorders racing a polling reader** — recording unaffected, no torn reads
- invalid constructor arguments and `record(null)` rejected

## 30-second recall

> Per `(attribute, value)`: a ring of `bucketCount` counters, each tagged with its **period**.
> `record` reclaims the slot if `periods[slot] != period`, then increments; `count` sums slots with
> `periods[slot] >= currentPeriod - bucketCount + 1`. **Old data is never deleted — it just stops
> qualifying**, so there is no reaper thread. `period` is absolute, `slot = period % bucketCount`;
> use `floorDiv`/`floorMod` because `nanoTime` is negative. Constructor takes **windowMillis**, not
> bucket size — deriving it the wrong way round gives a 60× window with no compile error — and
> rounds up so it never covers less than asked. Bucket count is **internal**: fixing the count (not
> the size) makes memory per key constant regardless of window length. The graded half is
> extensibility: `Attribute` is an **extractor interface**, never an enum, and `Request` is a passive
> bag — a new grouping is a call site, not an edit. Three lock levels: `indexes` unlocked (immutable
> after construction), per-attribute for the map, per-key for the counters. For rate limiting add
> `allow()` — never let callers compose `record` + `count`, that reintroduces check-then-act.
