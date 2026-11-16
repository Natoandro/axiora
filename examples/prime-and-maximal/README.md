# Prime and maximal ideals: contract fixture

This is an **unpacked illustrative package**, written for Axiora's design baseline. It is not a complete course and has not received independent mathematical or pedagogical review. Its manifest correctly labels it `draft` and `UNLICENSED`.

Read the [content contract](../../docs/markdown/content.md) for field and execution semantics, and the [teaching design](../../docs/markdown/pedagogy.md) for the larger intended course slice.

## Files

- [manifest.json](manifest.json): identity, capabilities, conventions, payload inventory and hashes.
- [concepts/core.json](concepts/core.json): locally defined prime and maximal ideal concepts.
- [units/prime-vs-maximal.json](units/prime-vs-maximal.json): branching diagnostic, counterexample, proof workspace content, rubric, reflection, and terminal activity.

Only those three JSON files belong in a future `.axp` archive. This README is not a payload. No compiler or runnable study engine is included. The documentation checker reads the unpacked files and checks a subset of the proposed contract; it does not assess mathematical truth.

## Walkthrough

The opening question asks whether every prime ideal is maximal. A correct first submission leads to a concise boundary example. An incorrect first submission leads to a more explicit explanation. Both paths reach a proof task for the valid reverse direction, then a reflection and an end activity.

The sole checked objective is recognition. The proof objective uses self-review and cannot receive checked correctness credit. Only one recognition assessment family is present, so this fixture cannot produce the two-family `demonstrated` state by itself.

Assumed prior concepts are declared as external learning prerequisites. They do not make the package structurally incomplete: the unit can be browsed while the app identifies those gaps. This miniature contains no prerequisite teaching units.

## Editing

Keep identities stable when revising prose. Recompute the manifest's `bytes` and SHA-256 values over the exact raw bytes of each payload after editing. The manifest itself is not in its own inventory. Run `uv run --locked python scripts/check_docs.py` from the repository root after changes.
