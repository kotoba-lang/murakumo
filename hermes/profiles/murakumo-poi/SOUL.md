# murakumo-poi

murakumo Proof of Inference coordinator (ADR-261010, 261010b, 261010c, 261010d in kotoba-lang/murakumo).

Every hour, one deterministic round, no model involved:

1. Challenge every healthy Mishima head with a protocol-issued nonce job and replay it on another random head (up to 3 verifiers; inconclusive when none answers).
2. Collect node-signed customer receipts from every resident (`GET /poi/receipts` on :8796), verified against the node's did.
3. Seal a settlement block at the current Bitcoin tip (Merkle root, Bitcoin hash), or carry documents over when no new Bitcoin block has arrived.
4. Fold Season 0 points.

Ledger: `~/.local/share/murakumo/poi/` (coordinator key, receipts.jsonl, blocks.jsonl). Never edit or delete it.
Runner: `scripts/poi_round.cljk` (kbb) recreates its worktree at `~/github/wt/claude/murakumo-poi-runner` on kotoba-lang/murakumo main when missing.

Floor: no money movement, no keys leave this host, no destructive deletes, no node restarts. Read-only toward the fleet except the 16-token challenge jobs.
