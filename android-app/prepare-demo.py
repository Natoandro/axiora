"""Build draft packages for Android acceptance without changing the handbook fixture."""

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "examples/prime-and-maximal"
OUTPUT = ROOT / "build/android-demo"
CLI = ROOT / "compiler-cli/build/install/axiora/bin/axiora"


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def build(source, name):
    subprocess.run(
        [CLI, "pack", source, "--output", OUTPUT / f"{name}.axp", "--overwrite"],
        check=True,
    )


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="axiora-demo-") as directory:
        source = Path(directory) / "course"
        shutil.copytree(FIXTURE, source)
        manifest_path = source / "manifest.json"
        manifest = json.loads(manifest_path.read_bytes())
        unit_path = source / "units/prime-vs-maximal.json"
        unit = json.loads(unit_path.read_bytes())
        unit["activities"]["predict"]["transitions"]["incorrect"] = "polynomial-check"
        unit["activities"]["polynomial-check"] = {
            "type": "choice",
            "source_ids": ["original-exposition"],
            "prompt_md": (
                r"Let $k$ be a field. In $k[x,y]$, the quotient by $(x)$ is $k[y]$. "
                "How should you classify the ideal $(x)$?"
            ),
            "options": [
                {"id": "prime-only", "text_md": "Prime, but not maximal."},
                {"id": "both", "text_md": "Both prime and maximal."},
                {"id": "neither", "text_md": "Neither prime nor maximal."},
            ],
            "evaluator": {"type": "exact_choice", "expected": "prime-only"},
            "feedback": {
                "correct_md": "Correct. The polynomial ring is an integral domain, and $y$ is not a unit.",
                "incorrect_md": "The quotient is a domain, but it is not a field since $y$ has no inverse.",
            },
            "transitions": {"correct": "remediation", "incorrect": "remediation"},
            "evidence": {
                "objective_id": "distinguish",
                "assessment_family": "axiora.ca.prime-maximal.polynomial-quotient",
            },
        }
        manifest["title"] = "Prime and maximal ideals — Android demo"
        manifest["version"] = "0.2.0"
        unit["version"] = "0.2.0"
        manifest["review"]["note"] = "Draft examples for Android acceptance."
        for entry in manifest["units"]:
            if entry["id"] == unit["id"]:
                entry["version"] = unit["version"]
        write_json(unit_path, unit)
        payload = unit_path.read_bytes()
        for entry in manifest["files"]:
            if entry["path"] == unit_path.relative_to(source).as_posix():
                entry["bytes"] = len(payload)
                entry["sha256"] = hashlib.sha256(payload).hexdigest()
        write_json(manifest_path, manifest)
        build(source, "axiora-study-demo")
        manifest["version"] = "0.2.1"
        manifest["title"] += " (updated)"
        write_json(manifest_path, manifest)
        build(source, "axiora-study-update")


if __name__ == "__main__":
    main()
