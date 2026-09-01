# One-tick causality

Status: **scoped, nothing built** (2026-09-01).

One rule, applied everywhere:

> Nothing in a tick reads *work*. Every pass reads the state emitted by last tick, and writes to
> work. A thing that happened this tick is not observable until next tick.

The signal network already works this way and is the template — `state.signals` is read, `nextSignals`
is written, and they swap at the end of the tick. The one-tick lag between a sensor firing and the
machine it drives noticing is not an artifact of the wire; it is this rule, showing through in the
one place it is currently enforced.

Expected consequences, all of them wanted:

- A storage that receives a packet holds it for a tick instead of emitting it from its output port
  in the same tick.
- A rail that receives a packet from a machine does not move it on in the same step.
- A bridge that takes a packet into its third slot keeps it there for a step.

## The machinery is already there

`Work(state)` deep-copies `deck`, `buffers`, `rail` and `tracks` on entry (`StuffLayer.copyOf`
copies every array), and `state` stays alive for the whole tick. **Both buffers already exist.** The
change is not an allocation or a new data structure — it is redirecting reads from `w.buffers` to
`state.buffers` at the points where a pass is *deciding* something, while writes go on landing in
`w`. That makes this far cheaper than it sounds, and it means the work can be done a layer at a time
with the two halves coexisting.

Sizing: ~22 buffer writes, ~25 deck writes, ~13 rail writes in a 3,753-line `OutofspaceSim.kt`.

## Already conformant

More than expected:

- **Signals** — the reference implementation, described above.
- **Staggered subsystems** — pump, heat, chemistry, pressure and fluid fire on *different ticks*
  (`PUMP_OFFSET`…`FLUID_OFFSET`), so they already communicate through state across a tick boundary.
  The cross-subsystem case is largely solved; what is left is *within* a pass.
- **`advanceSegments`** — hand-rolls this rule locally with `arrived: BooleanArray`, and its own doc
  already states the principle: "A packet moves one tile per advance, and that has to be true of the
  *packet* rather than of the walk." It even notes that such a tile "does not offer the new arrival
  to its own port until next pass".
- **Bridges** — `depositFromBridge` drains before `advanceBridges` shuffles the slots, so a packet
  moved into a slot is not deposited until the next step. The suspicion that the bridge is already
  fine looks correct; what is seen is likely the storage case below.
- **Heat** — `w.heatAdded[tile]` accumulates through the tick and is applied in a single pass at the
  end. This is the correct pattern for anything with multiple writers, and is already in place.

## Actual violations

1. **A storage emits what it just received.** `advanceRails`'s absorb callback writes into the
   buffer; `pushOut` runs immediately after, in the same block, and reads that buffer through
   `bufferFor`. This is the named case, confirmed.
2. **`structure` is re-derived mid-tick** — `if (w.solidityChanged) structure = StructureMap.derive(…)`.
   ⚠️ **Deliberate, and deferring it re-opens a bug already hit**: the comment says the fluid step
   would otherwise pour air back into the tile a new casing has just emptied. This one needs a
   decision, not a mechanical change.
3. **Deconstruction mutates the graph it is walking** — `scrapDeconstructing`/`scrapMachines` change
   `rails` mid-pass, and the flow graph is rebuilt in the same step when `railCount()` changed.
4. **Machine dispatch** — `w[tile] = …` writes machine state that later passes in the same tick read
   back through `deck[…]`.
5. **`leech`** reads the product buffer to decide whether to bite and writes the same buffer.

## The two real hazards

### Conservation is the sharp one

"Take from A, give to B" becomes: read A from state, write `A − x`, write `B + y`. If two passes both
draw on one source while reading last-tick state, **both see the full amount and both take it, and
mass is created**. `Mixture.take` being the only exact draw makes this precise rather than
approximate, and the ledger has four terms that will disagree loudly.

Every contended source needs a single owner per tick, or an explicit reservation. The mass-balance
harness is the safety net, and it is right if it disagrees.

### Accumulators must stay additive

Anything with multiple writers must accumulate into work, never last-writer-wins on a shared cell,
or deposits are silently dropped. Heat already does this; `extractedMass`, the ledgers and `motion`
are the same shape and need the same treatment.

## Throughput: latency, not rate

The concern is right, and the arithmetic is worse than it first looks because **`RAIL_PERIOD = 32`**.

- Rails already move one tile per advance, so the **hop rate does not change at all**.
- What changes is each *handoff* at a boundary — machine→rail, rail→machine, bridge→rail. Each gains
  one full rail step, which is **32 ticks, not one tick**. A line with N boundary crossings gains
  32N ticks.
- ⚠️ That is **latency, not throughput**. The line is pipelined, so steady-state rate is unchanged;
  what grows is the time for the first gram to arrive. Worth keeping the two words apart when
  judging whether it feels wrong in play.

⛔ **The emit hazard is real and specific.** `pushOut` already runs only inside the rail gate, so
machines emit onto track once per 32 ticks *today* — "every tick" is not the current behaviour. The
danger is that the new rule pushes the port's read one tick behind the machine's production write,
so a machine emits every *other* rail step — 64 ticks, halving output. Avoiding it means the
production write and the port's emit read stay one tick apart, not two. Sequencing within the tick
still matters under the new rule; the rule says what may be *read*, not that ordering stops counting.

## Suggested order

Hard shape first — the case that decides whether the idea survives, not the easy plumbing.

1. **Buffers.** The storage case, and where conservation bites. If double-buffered buffers cannot be
   made to conserve, the whole idea is in question and every later step is wasted work.
2. **Rail layer.** The handoff boundaries. Measure the latency change against a known line before and
   after, so the 32-tick steps are a number rather than a feeling.
3. **Deck / machine state.** Mostly mechanical once 1 and 2 hold.
4. **Derived state.** Decide the `solidityChanged` question explicitly.

## Open questions

- **`pushOut` runs only on rail ticks.** "Machines emit every tick" is not true today. Is that
  something to change, or did it mean every rail *step*?
- **The `structure` mid-tick re-derive fixes a real bug.** Defer it and accept the bug back, or
  exempt derived state from the rule?
- **Is derived state in scope at all?** `structure`, `occupancy`, `apertures` and the flow graph are
  recomputed from scratch each tick, so they are arguably neither state nor work. Treating them as
  work would forbid several things that currently exist for good reasons; treating them as
  derivations exempts them cleanly. Leaning to the latter.
