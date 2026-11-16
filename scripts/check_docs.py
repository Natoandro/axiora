#!/usr/bin/env python3
"""Check documentation links and the illustrative unpacked package (not a production validator)."""
from __future__ import annotations

import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
ERRORS = []


def require(condition, message):
    if not condition:
        ERRORS.append(message)


class Page(HTMLParser):
    def __init__(self, text):
        super().__init__()
        self.ids = set()
        self.links = []
        self.feed(text)

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if "id" in attrs:
            require(attrs["id"] not in self.ids, f"Duplicate HTML ID: {attrs['id']}")
            self.ids.add(attrs["id"])
        for key in ("href", "src"):
            if key in attrs:
                self.links.append(attrs[key])


def markdown_data(text):
    # The repository uses inline Markdown links, not reference-style links.
    text = re.sub(r"^```[^\n]*\n.*?^```\s*$", "", text, flags=re.M | re.S)
    anchors = set()
    used = {}
    for heading in re.findall(r"^#{1,6}\s+(.+)$", text, flags=re.M):
        base = re.sub(r"[^\w-]+", "-", heading.lower()).strip("-") or "section"
        used[base] = used.get(base, 0) + 1
        anchors.add(base if used[base] == 1 else f"{base}-{used[base]}")
    return anchors, re.findall(r"\[[^\]]+\]\(([^\s)]+)\)", text)


def check_links():
    paths = [ROOT / n for n in ("README.md", "AGENTS.md", "PRODUCT.md", "DESIGN.md")]
    paths += sorted((ROOT / "docs").rglob("*.md"))
    paths += sorted((ROOT / "docs").glob("*.html"))
    paths += sorted((ROOT / "examples").rglob("*.md"))
    cache = {}
    for path in paths:
        text = path.read_text()
        if path.suffix == ".html":
            page = Page(text)
            cache[path] = (page.ids, page.links)
        else:
            cache[path] = markdown_data(text)
    count = 0
    for path, (_, links) in cache.items():
        for href in links:
            parts = urlsplit(href)
            if parts.scheme or parts.netloc:
                continue
            target = (path.parent / unquote(parts.path)).resolve() if parts.path else path
            count += 1
            require(target.is_relative_to(ROOT), f"Link escapes repository: {path.relative_to(ROOT)} -> {href}")
            require(target.exists(), f"Broken link: {path.relative_to(ROOT)} -> {href}")
            if parts.fragment and target in cache:
                require(unquote(parts.fragment) in cache[target][0], f"Missing fragment: {path.relative_to(ROOT)} -> {href}")
    return len(paths), count


def no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def load_json(path):
    return json.loads(path.read_text(), object_pairs_hook=no_duplicate_keys)


def check_fixture():
    root = ROOT / "examples" / "prime-and-maximal"
    manifest = load_json(root / "manifest.json")
    require(manifest["schema_version"] == "0.1.0", "Fixture schema must match the design baseline")
    require(manifest["review"]["status"] == "draft", "Fixture must not claim unrecorded independent review")
    inventory = {}
    for item in manifest["files"]:
        name = item["path"]
        parts = name.split("/")
        safe = not name.startswith("/") and "\\" not in name and all(p not in ("", ".", "..") for p in parts)
        require(safe, f"Unsafe fixture path: {name}")
        require(name.casefold() not in {p.casefold() for p in inventory}, f"Duplicate fixture path: {name}")
        inventory[name] = item
        if not safe:
            continue
        path = root / name
        require(path.is_file(), f"Missing fixture payload: {name}")
        if path.is_file():
            raw = path.read_bytes()
            require(len(raw) == item["bytes"], f"Stale byte count: {name}")
            require(hashlib.sha256(raw).hexdigest() == item["sha256"], f"Stale hash: {name}")
    actual = {str(p.relative_to(root)) for p in root.rglob("*.json") if p.name != "manifest.json"}
    require(actual == set(inventory), "Fixture JSON inventory does not match payload files")
    concepts = {}
    for name in manifest["concept_files"]:
        require(name in inventory, f"Concept file absent from inventory: {name}")
        document = load_json(root / name)
        require(document["schema_version"] == manifest["schema_version"], "Concept schema mismatch")
        for concept in document["concepts"]:
            require(concept["id"] not in concepts, f"Duplicate concept: {concept['id']}")
            concepts[concept["id"]] = concept
    for concept in concepts.values():
        for relation in concept["relations"]:
            require(relation["target"] in concepts, f"Missing relation target: {relation['target']}")
    expected = {
        "exposition": {"type", "source_ids", "content_md", "next"},
        "choice": {"type", "source_ids", "prompt_md", "options", "evaluator", "feedback", "transitions"},
        "proof": {"type", "source_ids", "prompt_md", "hints", "model_solution_md", "rubric", "evaluator", "objective_id", "next"},
        "reflection": {"type", "source_ids", "prompt_md", "next"},
        "end": {"type", "source_ids", "summary_md"},
    }
    unit_ids = set()
    required_capabilities = set()
    activity_count = 0
    for record in manifest["units"]:
        require(record["path"] in inventory, f"Unit absent from inventory: {record['path']}")
        unit = load_json(root / record["path"])
        require(unit["id"] == record["id"] and unit["version"] == record["version"], "Manifest/unit identity mismatch")
        require(unit["schema_version"] == manifest["schema_version"], "Unit schema mismatch")
        require(unit["id"] not in unit_ids, f"Duplicate unit: {unit['id']}")
        unit_ids.add(unit["id"])
        require(set(unit["concept_ids"]) <= set(concepts), "Unit refers to missing taught concepts")
        objectives = {o["id"]: o for o in unit["objectives"]}
        require(len(objectives) == len(unit["objectives"]), "Duplicate objective IDs")
        require(all(o["concept_id"] in unit["concept_ids"] for o in objectives.values()), "Objective refers to untaught concept")
        sources = {s["id"] for s in unit["sources"]}
        activities = unit["activities"]
        edges = {}
        for aid, activity in activities.items():
            activity_count += 1
            kind = activity["type"]
            require(kind in expected, f"Unsupported fixture activity: {kind}")
            if kind not in expected:
                continue
            allowed = expected[kind] | ({"evidence"} if kind == "choice" else set())
            require(expected[kind] <= set(activity) <= allowed, f"Invalid activity fields: {aid}")
            require(bool(activity["source_ids"]) and set(activity["source_ids"]) <= sources, f"Invalid provenance: {aid}")
            required_capabilities.add(f"activity.{kind}.v1")
            if kind == "choice":
                options = [o["id"] for o in activity["options"]]
                require(len(options) >= 2 and len(options) == len(set(options)), f"Invalid options: {aid}")
                require(activity["evaluator"]["type"] == "exact_choice", f"Wrong choice evaluator: {aid}")
                require(activity["evaluator"]["expected"] in options, f"Missing correct option: {aid}")
                require(set(activity["transitions"]) == {"correct", "incorrect"}, f"Missing outcome transition: {aid}")
                require(set(activity["feedback"]) == {"correct_md", "incorrect_md"}, f"Missing feedback: {aid}")
                required_capabilities.add("evaluator.exact-choice.v1")
                edges[aid] = list(activity["transitions"].values())
                if "evidence" in activity:
                    obj = objectives.get(activity["evidence"]["objective_id"])
                    require(obj is not None and obj["skill"] in ("recall", "recognition"), f"Invalid choice evidence: {aid}")
            elif kind == "proof":
                require(activity["evaluator"] == {"type": "self_review"}, f"Wrong proof evaluator: {aid}")
                require(bool(activity["rubric"]), f"Missing proof rubric: {aid}")
                obj = objectives.get(activity["objective_id"])
                require(obj is not None and obj["skill"] in ("proof_construction", "proof_comprehension"), f"Invalid proof objective: {aid}")
                required_capabilities.add("evaluator.self-review.v1")
                edges[aid] = [activity["next"]]
            else:
                edges[aid] = [] if kind == "end" else [activity["next"]]
        require(unit["entry"] in activities, "Missing entry activity")
        require(all(target in activities for targets in edges.values() for target in targets), "Unresolved activity transition")
        visited, active = set(), set()

        def visit(node):
            if node in active:
                require(False, f"Activity graph cycle at {node}")
                return
            if node in visited or node not in activities:
                return
            visited.add(node)
            active.add(node)
            require(bool(edges.get(node)) or activities[node]["type"] == "end", f"Nonterminal dead end: {node}")
            for target in edges.get(node, []):
                visit(target)
            active.remove(node)

        visit(unit["entry"])
        require(visited == set(activities), "Unreachable activity in fixture")
    require(set(manifest["entry_units"]) <= unit_ids, "Missing entry unit")
    require(required_capabilities <= set(manifest["requires_capabilities"]), "Manifest omits required capabilities")
    id_pattern = re.compile(r"[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*\Z")
    require(all(len(i) <= 120 and id_pattern.fullmatch(i) for i in concepts.keys() | unit_ids | {manifest["package_id"]}), "Invalid global fixture ID")
    return len(inventory), activity_count


def main():
    pages, links = check_links()
    try:
        payloads, activities = check_fixture()
    except (KeyError, ValueError, OSError, TypeError) as exc:
        ERRORS.append(f"Fixture could not be checked: {exc}")
        payloads, activities = 0, 0
    if ERRORS:
        print("\n".join(f"ERROR: {error}" for error in ERRORS))
        return 1
    print(f"Checked {pages} documents, {links} local links, {payloads} fixture payloads, and {activities} activities.")
    print("Documentation checks passed. Mathematical truth and the full production import contract are not checked.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
