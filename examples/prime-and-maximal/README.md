# Prime and maximal ideals: contract fixture

This is an **unpacked illustrative package**, written for Axiora's design baseline. It is not a complete course and has not received independent mathematical or pedagogical review. Its manifest correctly labels it `draft` and `UNLICENSED`.

Read the [content contract](../../docs/markdown/content.md) for field and execution semantics, and the [teaching design](../../docs/markdown/pedagogy.md) for the larger intended course slice.

## Files

- [manifest.json](manifest.json): identity, capabilities, conventions, payload inventory and hashes.
- [concepts/core.json](concepts/core.json): locally defined prime and maximal ideal concepts.
- [units/prerequisite-recognition.json](units/prerequisite-recognition.json): a prerequisite check for ideal closure.
- [units/prime-vs-maximal.json](units/prime-vs-maximal.json): a branching diagnostic and counterexample.
- [units/proof-and-fresh-check.json](units/proof-and-fresh-check.json): proof construction followed by a fresh quotient characterization check.

Only the manifest and declared JSON payloads belong in a future `.axp` archive. This README is not a payload. No compiler or runnable study engine is included. The documentation checker reads the unpacked files and checks a subset of the proposed contract; it does not assess mathematical truth.

## Walkthrough

The prerequisite check distinguishes ideal closure from primality. The central unit asks whether every prime ideal is maximal. A correct first submission leads to a concise boundary example; an incorrect first submission leads to a more explicit explanation. The proof unit practices the valid reverse direction and follows self-review with a separate quotient characterization check.

The checked objectives use recognition, while proof construction uses self-review and cannot receive checked correctness credit. The package remains illustrative and its draft content has not received independent mathematical or pedagogical review.

Assumed prior concepts are declared as external learning prerequisites. They do not make the package structurally incomplete: units can be browsed while the app identifies those gaps. The prerequisite unit is a limited recognition check; it does not teach a full ring or ideal unit.

## Editing

Keep identities stable when revising prose. Recompute the manifest's `bytes` and SHA-256 values over the exact raw bytes of each payload after editing. The manifest itself is not in its own inventory. Run `uv run --locked python scripts/check_docs.py` from the repository root after changes.
