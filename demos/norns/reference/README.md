# C2 reference art

Reference screenshots from **Creatures 2** (Albia), kept so we can refine the look/feel of the
Norns demo against the real game later.

- **c2-albia-the-incubator.jpeg** — a full in-game screenshot of Albia ("The Incubator" region).
  Shows the side-scrolling multi-floor cave, flora, sky/surface, and (bottom-right) a lift.
  Source: shared by Stu (reddit `i.redd.it/trt0woqjobte1.jpeg`).
- **c2-lift-closeup.png** — a crop of the lift from that screenshot: a planked **wooden crate**
  slung on **ropes** from a peaked hoist frame (cable up the shaft), an **X-braced front gate**,
  and a separate **lamp-post call button** beside it. This is what the in-engine lift
  (`NornsImageRenderer.drawLiftBody` / `drawLiftGate`, geometry in `LiftLayout`) is modelled on.

When tuning lift visuals/feel, compare a rendered frame against `c2-lift-closeup.png`.
