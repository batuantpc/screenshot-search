"""Fuse OCR policy and MobileCLIP without tuning on the final test half.

The 300 native screenshots are deterministically split by id hash. Threshold and
fusion variant are selected on the dev half; only then is the untouched test half
reported. This prevents choosing a flattering confidence threshold on the same
rows used for the final number.
"""

from __future__ import annotations

import csv
import hashlib
import json
from collections import Counter
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DATA_ROOT = REPO_ROOT / ".private-data" / "native-ui-v1"
RULE_PATH = REPO_ROOT / ".private-data" / "eval" / "rule-predictions.tsv"
CLIP_PATH = DATA_ROOT / "mobileclip-zero-shot-report.json"
REPORT_PATH = DATA_ROOT / "multimodal-policy-report.json"


def split_name(case_id: str) -> str:
    return "dev" if hashlib.sha256(case_id.encode("utf-8")).digest()[0] < 128 else "test"


def load_rows() -> list[dict]:
    clip_report = json.loads(CLIP_PATH.read_text(encoding="utf-8"))
    clip = {row["id"]: row for row in clip_report["predictions"]}
    with RULE_PATH.open(encoding="utf-8", newline="") as handle:
        rules = {
            row["id"]: row
            for row in csv.DictReader(handle, delimiter="\t")
            if row["id"].startswith("native-ui-")
        }
    rows = []
    for case_id in sorted(clip):
        if case_id not in rules:
            continue
        rows.append(
            {
                "id": case_id,
                "split": split_name(case_id),
                "expected": clip[case_id]["expected"],
                "rule": rules[case_id]["rule_topic"],
                "text": rules[case_id]["policy_topic"],
                "vision": clip[case_id]["predicted"],
                "vision_confidence": float(clip[case_id]["confidence"]),
            }
        )
    return rows


def predict(row: dict, variant: str, threshold: float) -> str:
    if variant == "vision_only":
        return row["vision"]
    if variant == "text_only":
        return row["text"]
    if variant == "text_then_vision":
        if row["text"] != "OTHER":
            return row["text"]
        return row["vision"] if row["vision_confidence"] >= threshold else "OTHER"
    if variant == "rule_then_vision":
        if row["rule"] != "OTHER":
            return row["rule"]
        return row["vision"] if row["vision_confidence"] >= threshold else "OTHER"
    if variant == "agreement_first":
        if row["text"] == row["vision"]:
            return row["text"]
        if row["text"] == "OTHER" and row["vision_confidence"] >= threshold:
            return row["vision"]
        return row["text"]
    raise ValueError(variant)


def metrics(rows: list[dict], variant: str, threshold: float) -> dict:
    guesses = [predict(row, variant, threshold) for row in rows]
    correct = [row["expected"] == guess for row, guess in zip(rows, guesses)]
    other_indices = [index for index, row in enumerate(rows) if row["expected"] == "OTHER"]
    semantic_indices = [index for index, row in enumerate(rows) if row["expected"] != "OTHER"]
    confusions = Counter(
        (row["expected"], guess)
        for row, guess in zip(rows, guesses)
        if row["expected"] != guess
    )
    return {
        "examples": len(rows),
        "accuracy": sum(correct) / len(rows),
        "semantic_accuracy": (
            sum(correct[index] for index in semantic_indices) / len(semantic_indices)
            if semantic_indices
            else None
        ),
        "other_false_positive_rate": (
            sum(guesses[index] != "OTHER" for index in other_indices) / len(other_indices)
            if other_indices
            else None
        ),
        "top_confusions": [
            {"expected": expected, "predicted": guess, "count": count}
            for (expected, guess), count in confusions.most_common(10)
        ],
    }


def main() -> None:
    rows = load_rows()
    dev = [row for row in rows if row["split"] == "dev"]
    test = [row for row in rows if row["split"] == "test"]
    thresholds = [value / 100 for value in range(50, 100, 5)]
    variants = ("text_then_vision", "rule_then_vision", "agreement_first")
    candidates = []
    for variant in variants:
        for threshold in thresholds:
            score = metrics(dev, variant, threshold)
            candidates.append((score["accuracy"], score["semantic_accuracy"], variant, threshold))
    _, _, best_variant, best_threshold = max(candidates)
    report = {
        "schema": 1,
        "selection": "variant and threshold chosen on id-hash dev split only",
        "dev_examples": len(dev),
        "test_examples": len(test),
        "selected_variant": best_variant,
        "selected_threshold": best_threshold,
        "dev": {
            "text_only": metrics(dev, "text_only", 0.0),
            "vision_only": metrics(dev, "vision_only", 0.0),
            "selected": metrics(dev, best_variant, best_threshold),
        },
        "test": {
            "text_only": metrics(test, "text_only", 0.0),
            "vision_only": metrics(test, "vision_only", 0.0),
            "selected": metrics(test, best_variant, best_threshold),
        },
        "all_rows_reference_only": metrics(rows, best_variant, best_threshold),
    }
    REPORT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"\nreport: {REPORT_PATH}")


if __name__ == "__main__":
    main()
