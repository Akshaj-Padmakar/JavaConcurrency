# The Child Care Problem

> Book ref: _The Little Book of Semaphores_ §7.2. Written by Max Hailperin. This folder covers both
> the base problem (`ChildCare.java`) and its follow-up (`ExtendedChildCare.java`, §7.2.4).

## Setup

- A child care center must maintain **at least 1 adult for every 3 children** present, at all times.
- Children and adults arrive and leave independently, concurrently.
- Enforce the ratio as a critical-section constraint: nobody's presence should ever push the count out
  of bounds, even momentarily.

## The trap (again, the book shows its own broken attempt first)

Hailperin's hint: this is *almost* solvable with a single semaphore `multiplex` — adults `signal(3)`
on entry (minting 3 "child tokens"), children `wait()` once to enter, adults `wait()` three times
before leaving (reclaiming their tokens). The bug:

- An adult leaving does **three separate, non-atomic** `wait()` calls to reclaim its 3 tokens.
- If **two adults** try to leave at the same time with exactly enough tokens between them (e.g. 3
  children, 2 adults, `multiplex == 3`), they can each grab **some** of the 3 available tokens instead
  of one adult cleanly grabbing all 3 — and both end up permanently blocked, each short one token
  that the other is holding. Classic deadlock from an operation that's logically "one action" (leave)
  but isn't executed atomically.

## Key idea

- The three `wait()` calls on leaving need to be atomic **as a group** — one adult must acquire all 3
  tokens or none, never a partial split with another adult.
- Minimal fix needs surprisingly little — no new counters, just enough mutual exclusion around the
  three-token reclaim so it can't be interleaved with another adult's reclaim.

## Ponder

- Where exactly does the non-solution's deadlock require *two* adults leaving concurrently — would
  it still be possible with just one adult ever leaving at a time?
- The book's fix is described as "minimal." What's the smallest addition that makes the 3-token
  reclaim atomic without restructuring the whole solution?
- Does the simple fix have any other cost? (Hint: what does it do to children trying to enter while
  an adult is mid-leave?)

## Part two — the extended problem (avoiding unnecessary waiting)

The straightforward fix has a throughput flaw: an adult who's part-way through leaving (has grabbed
some but not all 3 tokens back) can block **new children from entering**, even when the ratio would
still legally allow it.

- Concretely: 4 children, 2 adults (ratio exactly at the limit). One adult starts to leave, grabs 2 of
  its tokens, blocks waiting for the 3rd (held by a departed child, say). A new child arrives — legal
  for it to enter (3 children : 1 remaining full adult would still be fine, or however the count
  nets out) — but it can't, because the leaving adult is sitting on partially-reclaimed capacity.
- **Puzzle:** design a solution that doesn't make children wait unnecessarily just because an adult
  is *in the process of* leaving, not because the ratio actually forbids it. The book's hint points at
  the "I'll do it for you" pattern from the dancers problem (§3.8) — worth comparing to how batching
  is handled in [P02_UnisexBathroom](../../S03_NotSoClassical/P02_UnisexBathroom/Problem.md) /
  [P03_BaboonCrossing](../../S03_NotSoClassical/P03_BaboonCrossing/Problem.md) in this repo.
- Does a *leaving* adult still "count" toward the ratio while it's stuck waiting to fully exit? What
  has to be true about that for children to keep entering safely in the meantime?
