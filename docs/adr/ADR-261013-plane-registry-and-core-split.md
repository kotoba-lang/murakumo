# ADR-261013: Plane registry and the core split (root ADR-2610101900 P2/P3)

- Status: accepted
- Date: 2026-10-13
- Implements: root ADR-2610101900 P3 (the shape of a plane) and the first step of P2
  (core split)

## Context

Root ADR-2610101900 makes murakumo the resource layer and puts inference, generation,
storage, web, jobs, lease and app on it as planes (one plane = one mon). P3 asked for
the shape of a plane registration; P2 asked to separate the core from inference
without changing anything a caller sees. This repo has 220+ namespaces, most of them
under `murakumo.infer`, and other agents land changes in it daily, so a file move
would be a large, conflict-prone diff with no behaviour to show for it.

## Decision

1. **A plane is a data descriptor** in `resources/murakumo/planes.edn`: id, name,
   status (`:live` `:partial` `:planned`), public gate host and token scope, what it
   promises, what it meters, its settlement unit (`:kumo` `:yata` `:en`), its
   verification rule, the node capabilities it requires (`:accelerator` `:disk`
   `:egress` `:wasm` `:strong-isolation`), whether a node's operator must opt in, the
   namespaces it owns, its repos and ADRs. `murakumo.plane` validates it (unique ids,
   unique gate hosts: one plane, one subdomain).
2. **The core split is an enforced map, not a move.** Every namespace under `src/`
   belongs to exactly one owner by longest prefix (`:core` or a plane), and a prefix
   that matches no namespace is stale; `test/murakumo/plane_test.cljk` checks both.
   A new namespace lands in `:core` unless a plane claims it, and the owner is visible
   in one file. Moving code into per-plane directories is not part of this decision.
3. **Which planes a node runs** is `murakumo.plane/eligible-planes`: not `:planned`,
   every required capability present, and the operator's opt-in where the plane asks
   for one (today: web, lease).
4. **Registered today:** inference, generation, storage, web (live); jobs (partial:
   distributed CI/CD and WASM components exist, no public gate); lease and app
   (planned).

## Consequences

- The ownership of the 220+ namespaces is readable and tested: `:inference` holds
  `murakumo.infer` except the media/image namespaces (`:generation`), `:jobs` holds
  CI/CD, artifacts and component authority, `:web` holds `murakumo.web`.
- Not done here: wiring `eligible-planes` into enrollment/heartbeat (that changes the
  wire to api.murakumo.cloud and needs its own change there), the murakumo.cloud
  discovery document that lists planes (the apex Worker's deploy is lease-locked and
  belongs to its own change), and P5/P6.
