# Leader Election

> Interview form: _"Five nodes. Exactly one leader. Kill the leader and a new one takes over."_
> This is the election half of Raft — the part that fits in 45 minutes and is genuinely a
> concurrency problem. **Not** log replication, snapshots, or membership changes.

## What you're building

A cluster of 5 nodes, each running in its own thread, in one JVM. At any moment the cluster needs
exactly one **leader**; the rest are **followers**. If the leader dies, the survivors must notice and
promote a new one without any outside coordination.

There is no central registry to ask. Every node only knows two things: what it has been told by
other nodes, and how long it has been since anybody told it anything.

**The one invariant:**

> **No two nodes may believe they are leader for the same term.**

Two leaders at once is *split brain* — both accept writes, and the data diverges irrecoverably.
Everything below exists to make that impossible.

## The three roles

```
        no heartbeat for T ms                won a majority of votes
FOLLOWER ────────────────────▶ CANDIDATE ─────────────────────────▶ LEADER
    ▲                              │                                   │
    └──────────────────────────────┴───────────────────────────────────┘
                     saw a HIGHER term -> step down immediately
```

- **Follower** — waits for heartbeats. Silence is the trigger: no heartbeat within its election
  timeout and it promotes itself to candidate.
- **Candidate** — increments the term, votes for itself, asks everyone else for a vote. A majority
  (3 of 5) makes it leader. No majority before its timeout expires and it starts a fresh election
  with a higher term.
- **Leader** — sends heartbeats to everyone, often enough that no follower ever times out.

## The two rules that make split brain impossible

**1. A node votes at most once per term.** Not once per election — once per *term*. A node that
already voted in term 7 refuses every other request for term 7, whoever asks.

**2. Any node seeing a higher term steps down immediately** and adopts that term. A stale leader that
was paused, or partitioned, or GC'd for two seconds, discovers on its very next message that the
world moved on, and demotes itself.

Together with **majority intersection** — any two majorities of 5 share at least one member — these
give you the guarantee. Two candidates in term 7 both need 3 votes from the same 5 nodes; some node
would have had to vote twice; rule 1 says it didn't. So at most one can win.

That argument is the answer to *"how do you know this is correct?"*, and it's worth being able to say
in one breath.

## A tiny example

5 nodes, election timeout ~150–300 ms, heartbeat every 50 ms.

```
t=0     all five start as followers, term 0, each with a random timeout
t=180   node-2's timeout fires first
        -> CANDIDATE, term 1, votes for itself (1 vote)
t=182   node-2 asks 0,1,3,4 for votes in term 1
        0,1,3,4 have not voted in term 1 -> all grant
        node-2 has 5 votes, needs 3 -> LEADER for term 1
t=185   node-2 starts heartbeating; everyone's timeout keeps resetting

t=900   node-2 is killed
t=1050  node-4's timeout fires (no heartbeat for 150ms)
        -> CANDIDATE, term 2, votes for itself
        0,1,3 grant (they have not voted in term 2)
        -> LEADER for term 2

t=1200  node-2 is revived, still believing it is leader of term 1
        its first message carries term 1; a follower replies "current term is 2"
        node-2 sees 2 > 1 -> steps down to FOLLOWER, adopts term 2
```

That last step is the one to get right. **The revived node does not need to be told it was replaced
— it works it out from the term number alone.**

## What you implement

```java
class Node {
    Node(int id, Cluster cluster, long minTimeoutMillis, long maxTimeoutMillis, long heartbeatMillis);

    void start();
    void stop();                        // simulates a crash

    Role role();                        // FOLLOWER | CANDIDATE | LEADER
    long term();

    // --- RPCs, called BY OTHER NODES' threads ---
    VoteResponse requestVote(long term, int candidateId);
    AppendResponse appendEntries(long term, int leaderId);   // the heartbeat
}

interface Cluster {
    List<Node> peers(int selfId);       // the other nodes; a test double can drop or delay calls
}
```

`requestVote` and `appendEntries` are invoked **by other nodes' threads**, concurrently with the
node's own timer thread. That's where the concurrency lives.

## Constraint

`Thread`, `synchronized`, `wait`/`notify`, or `Lock` + `Condition`, plus `java.util.concurrent` if
you want it. No network — peers are objects and an RPC is a method call. A test double implementing
`Cluster` is how you simulate partitions and dropped messages.

## Requirements

- **At most one leader per term.** The whole point.
- **A leader emerges** from a healthy cluster within a bounded time.
- **Kill the leader and a new one appears**, with a strictly higher term.
- **A revived stale leader steps down** on its first contact.
- **One vote per node per term**, no matter how many candidates ask.
- **Split votes recover.** Two candidates can tie at 2–2; nobody wins; the next round must have a
  higher term and must eventually resolve.
- **No deadlock.** Node A calling `requestVote` on node B while B calls `requestVote` on A must not
  hang.

## Edge cases

Two nodes timing out simultaneously · a vote request arriving with a *lower* term (reject, and tell
the sender your term) · a vote request for a term the node has already voted in · a heartbeat from a
node whose term is lower than yours · a majority of nodes stopped, so no leader is possible ·
`stop()` called mid-election · a cluster of 1 · a cluster of 2 (what's a majority?).

## Four questions to answer before you code

**Where does the lock go, and how big is the critical section?** Every node has mutable state
(`term`, `votedFor`, `role`, `lastHeartbeat`) touched by its own timer thread *and* by incoming RPCs
from peer threads. One lock per node is the obvious answer — but see the next question.

**How do you avoid deadlocking two nodes calling each other?** If A holds its own lock while calling
`B.requestVote(...)`, and B is simultaneously holding its lock calling `A.requestVote(...)`, you have
a classic two-lock cycle. What's the discipline that prevents it? (You have already applied the same
rule twice in this repo.)

**Why must the election timeout be randomized?** With identical timeouts all five nodes wake at the
same instant, all become candidates, all split the vote, and the next round does the same. What does
randomization actually buy, and what's the relationship between the timeout range and the heartbeat
interval?

**How does a follower know time has passed?** It must wake up when the timeout elapses *and* be
re-armed every time a heartbeat arrives. A timed wait, and something that resets the deadline —
which means the sleeper has to be woken by the arriving heartbeat, not just have a variable updated
underneath it.

## What is deliberately out of scope

No log replication, no `commitIndex`, no persistence, no snapshots, no membership changes, no real
network. Those are system-*design* material. This is the election, and the election is the part that
is a concurrency problem.

## The jargon

| Plain version | The term |
| ------------- | -------- |
| A monotonically increasing election number | **term** / **epoch**; the same idea as a **fencing token** |
| Any two majorities share a member | **quorum intersection** — the reason this works at all |
| Silence means the leader is gone | **failure detection by timeout** |
| Every heartbeat pushes the deadline out | **lease renewal** |
| Two nodes both believing they lead | **split brain** |
| One vote per node per term | **at-most-once per epoch** |
| Nobody wins the round | **split vote**, resolved by **randomized backoff** |
