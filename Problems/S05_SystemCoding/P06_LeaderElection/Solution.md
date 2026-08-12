# Leader Election — Revision Sheet

> See [Problem.md](Problem.md). Implementation: `LeaderElection.java`,
> `Test/LeaderElectionTest.java`.
>
> This is **Raft's leader election** with the log-freshness restriction omitted, because there is no
> log. Not log replication, not snapshots, not membership changes.

## One-line idea

Every node has a randomized **election deadline**. Silence past it means the leader is gone, so the
node bumps its **term**, votes for itself, and asks everyone else. A node grants **at most one vote
per term**; a majority wins. Anyone who sees a higher term steps down immediately.

## State model

| Field | Meaning |
| ----- | ------- |
| `currentTerm` | monotonically increasing epoch. The fencing token |
| `votedFor` | who we voted for **in this term**; cleared on every term change |
| `role` | FOLLOWER / CANDIDATE / LEADER |
| `electionDeadlineNanos` | when to give up waiting and start an election |
| `running` | simulates crash / restart |
| `lock` + `deadlineChanged` | one lock per node; the condition is how a heartbeat re-arms the sleeper |

Every one of those is touched by **two kinds of thread**: the node's own timer thread, and peer
threads calling `requestVote` / `appendEntries`. In this in-process simulation an "RPC" is a plain
method call, so **`node0.requestVote(...)` executes on node-2's thread** while node-0's own thread
sleeps. That's why everything is locked, and why the RPC must `signalAll()`.

## The safety argument, in three lines

1. A candidate needs a **majority** — 3 of 5.
2. Any two 3-subsets of 5 nodes **share at least one member**.
3. That shared node's `votedFor` holds exactly one value for the term.

So two candidates cannot both reach 3 in the same term. **Quorum intersection + at-most-one-vote-per-
term.** That is the whole proof, and it's the answer to *"how do you know this is correct?"*

## Voting is passive — there is no "cast vote" call

`votedFor` is written in exactly two places:

```java
votedFor = id;             // startElection()  -- voting for MYSELF
votedFor = candidateId;    // requestVote()    -- voting for SOMEONE ELSE
```

A node never decides to go vote for anyone. **The candidate pulls votes; voters answer.** That's the
structural bit people miss when they read the roles and expect a follower to send something.

## `requestVote`, branch by branch

```java
if (!running) return null;                                    // 0 -- a crashed node just doesn't answer
if (term > currentTerm) stepDownLocked(term);                 // 1 -- we're behind: adopt, demote, CLEAR the vote
if (term < currentTerm) return new VoteResponse(currentTerm, false);  // 2 -- they're stale: reject AND report our term
if (votedFor == NO_VOTE || votedFor == candidateId) {         // 3 -- free, or the same asker again (idempotent)
    votedFor = candidateId;
    resetDeadline(); deadlineChanged.signalAll();             // 4 -- granting delays our OWN election
    return new VoteResponse(currentTerm, true);
}
return new VoteResponse(currentTerm, false);                  // 5 -- already voted for someone else: THE safety line
```

**Branch order matters.** Adopting the higher term must come *before* the `votedFor` check, or you'd
refuse a legitimate candidate because of a vote cast in an older term. Clearing `votedFor` on every
term change is what makes "one vote per term" mean per *term* rather than "one vote ever."

**Branch 2 is the fencing mechanism.** Returning `currentTerm` in the rejection is how a stale
candidate — or a stale *leader* — discovers it's stale, with nobody tracking it.

**Branch 4 is easy to miss.** If you vote for someone, give them a chance to win before you start
competing. Without it, every voter's own deadline fires mid-election and the cluster thrashes through
terms without settling.

## Never hold your own lock across an RPC

```java
lock.lock();
try { /* snapshot term + peers */ } finally { lock.unlock(); }

for (int peer : peers) {
    cluster.requestVote(id, peer, electionTerm, id);   // NO lock held
}

lock.lock();
try { /* re-validate, then apply */ } finally { lock.unlock(); }
```

A holding its lock while calling `B.requestVote` while B does the reverse is a two-node cycle. Same
rule that broke `BoundedByteBuffer` (a `synchronized` method plus a `Lock`) and still wedges
`MyThreadPool` (lock held across a blocking `put`) — here in its most classic form.

**And the re-validation is not optional:**

```java
if (running && role == Role.CANDIDATE && currentTerm == electionTerm) role = Role.LEADER;
```

You were outside the lock while collecting votes, so you may have stepped down meanwhile. Without
`currentTerm == electionTerm`, a candidate that already demoted still promotes itself — **that is
split brain**. Check-then-act, spanning network calls.

## Timing: the deadline, and re-arming it

```java
while (running && role != Role.LEADER) {
    long remaining = electionDeadlineNanos - System.nanoTime();
    if (remaining <= 0) return true;          // timed out -> start an election
    deadlineChanged.awaitNanos(remaining);
}
```

Every heartbeat and every granted vote pushes `electionDeadlineNanos` out **and signals**. Updating
the field without signalling would leave the thread asleep on the old deadline — the same "state
changed, nobody told anyone" rule that has now caused a bug in five of the seven problems here.

**Why randomize.** With identical timeouts all five nodes fire together, split the vote, and repeat
forever. Randomization isn't fairness, it's **symmetry breaking** — one node reliably gets a head
start.

**The timing constraint:** `heartbeatMillis` must be comfortably below `minTimeoutMillis`, or
followers time out during normal operation and you get permanent elections. `40 / 150–300` works.

## Message complexity

| Regime | Messages |
| --- | --- |
| One election, one candidate | `2⌊N/2⌋` to `2(N−1)` — **4 to 8 for N=5** |
| k candidates colliding | `O(kN)`, worst case `O(N²)` |
| **Steady state** | `2(N−1)` per heartbeat interval, forever — **this dominates** |

At N=5 with a 40 ms heartbeat that's ~200 messages/second on a completely idle cluster, versus 4–8
for an election that happens once. **Heartbeats are the entire message budget**; elections are a
rounding error.

That fan-out is why production clusters are 3, 5 or 7 nodes — not fault tolerance (5 already
tolerates 2 failures), but that the leader's per-beat cost and the quorum latency both grow with N
while the tolerance gain flattens. Larger deployments add non-voting observers.

**The latency flaw in this implementation:** vote requests are sent **sequentially**, so election
latency is `O(N × RTT)` instead of `O(RTT)`. Invisible in-process; over a real network a 5-node
election takes 4 round trips instead of 1, and one dead peer costs a full network timeout before you
move on. The fix is parallel fan-out resolving on the first majority — the *quorum collector*
pattern. Say this out loud when presenting; it's the obvious critique.

## Bugs found while building this

| Bug | Symptom |
| --- | --- |
| `appendEntries` called `stepDown(currentTerm)` instead of `stepDown(term)` | the guard `newTerm > currentTerm` made it a **silent no-op** — followers never adopted the leader's term. Nodes 3 and 4 sat at term 1 while the cluster ran term 2, because the candidate **breaks early at majority** and never asked them to vote, leaving `appendEntries` as their only term source |
| `timeout * 1_000_0000` | 10⁷ not 10⁶ — every timeout **10× too long**. Elections took 1641 ms with a 150–300 ms setting; failover 2301 ms. Underscores are only visual, so the compiler is happy |
| `start()` never called `resetDeadline()` | `electionDeadlineNanos` stayed 0, so every node fired an election **immediately at t=0, simultaneously** — the exact split-vote storm randomization exists to prevent |
| `start()` never assigned `this.thread` | `stop()` read null, skipped the interrupt **and the join**, and returned while the thread was still running. Any test that crashes a node and immediately asserts is racing it |
| `Node` / `State` non-static inner classes | callers needed `new LeaderElection().new Node(...)` — third time this has come up in this repo |

Two of those are worth generalising:

- **A guard that makes a call a no-op is invisible.** `stepDown(currentTerm)` compiled, ran, and did
  nothing. Nothing threw; the cluster still elected leaders. Only a test asserting *"all nodes agree
  on the term"* catches it.
- **`this.thread = t` must come before `t.start()`**, or there's a window where a running thread has
  a null handle.

## Two subtleties my own tests got wrong first

**"A minority cannot elect a leader" ≠ "no minority node reports LEADER."** If the incumbent lands in
the minority side of a partition, it keeps believing it leads — nothing can tell it otherwise.
Safety holds because the **terms differ**.

Which is the sharpest idea in the problem:

> **The safety property is "at most one leader per TERM", not "at most one leader."** Two leaders can
> coexist during a partition. That's fine, because only one of them can ever assemble a quorum.

**A crashed node's state is frozen.** `stop()` doesn't clear `role`, so a crashed leader still *says*
`LEADER@1`. Observers must exclude crashed nodes. The alternative — having `stop()` reset the role —
is friendlier but models a graceful step-down rather than a crash. Name the choice either way.

## Known limitations

- **No log**, so no `commitIndex`, no `matchIndex[]`, and no election restriction. Real Raft adds one
  clause to `requestVote`: only vote for a candidate whose log is at least as up-to-date as yours.
  That's what guarantees a new leader holds every committed entry.
- **No persistence.** Real Raft persists `currentTerm` and `votedFor` before responding; a restart
  here forgets both, which can violate one-vote-per-term across a crash.
- **Sequential vote requests** — see message complexity.
- **`stop()` leaves `role` and `currentTerm` frozen** rather than clearing them.
- **No pre-vote phase.** A partitioned node's term climbs indefinitely; when it rejoins it forces a
  needless election by virtue of its inflated term. Raft's optional pre-vote fixes this.

## Verified

`Test/LeaderElectionTest.java` — plain `main()`-based, no JUnit. 9 cases, 3/3 clean runs.

The one that matters is **`testChaosNeverProducesTwoLeadersInOneTerm`**: 12 seconds of random
crashes, revivals, partitions and heals, with a detector sampling `(role, term)` continuously via an
**atomic `snapshot()`** — reading `role()` and `term()` separately would let it observe combinations
that never existed. One safety assertion, plus a liveness check once the chaos stops.

Also covered: a leader emerging within a few timeouts (which would have caught the 10× bug), **the
whole cluster converging on one term** (the `appendEntries` regression), failover to a different node
in a higher term, a partitioned leader stepping down on rejoin, one-vote-per-term tested directly
including the idempotent re-ask, lower-term requests rejected *with our term reported*, a 2-of-5
minority never electing while its term climbs, and `stop()` actually joining the thread.

## 30-second recall

> Raft's election, minus the log restriction. **Term = fencing token**; anyone seeing a higher term
> steps down and clears `votedFor`. **One vote per term + majority + quorum intersection** ⇒ at most
> one leader per term — that's the whole proof. Voting is **passive**: the candidate pulls, voters
> answer inside `requestVote`, and the rejection carries **our term** so a stale node learns it's
> stale with nobody tracking it. **Never hold your lock across an RPC** (two-node cycle) and
> **re-validate `role == CANDIDATE && currentTerm == electionTerm`** before promoting, or a candidate
> that already stepped down crowns itself. Deadlines are **randomized to break symmetry**, re-armed by
> heartbeats via `signalAll` on a condition — updating the field without signalling leaves the
> sleeper on the stale deadline. **`heartbeat << minTimeout`** or you elect forever. Heartbeats are
> `O(N)` per interval and dominate everything, which is why clusters stay at 3/5/7. Safety is **"one
> leader per TERM"** — two leaders in different terms during a partition is expected and harmless.
