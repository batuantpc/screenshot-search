"""Build a deterministic, leakage-free native mobile screenshot benchmark.

Source data is Enrico: 1,460 human-curated Android UI screenshots derived from
RICO. Enrico's labels describe *screen layout* (login, profile, news, ...), not
Screenshot Search's content topics. This tool therefore creates gold labels only where the
combination of layout and Google Play category is unambiguous:

* login/settings/terms/tutorial/forms and similar app chrome -> OTHER
* Maps UI -> PLACE; chat UI -> SOCIAL
* otherwise a conservative Play category mapping (SPORTS -> SPORT, etc.)

Ambiguous categories such as ENTERTAINMENT are skipped unless the screen layout
adds enough evidence. The benchmark is never added to the training allowlist.

Expected private input:
  .private-data/native-ui-v1/
    design_topics.csv
    screenshots/screenshots/*.jpg
    metadata/metadata/*.json

Output (also private / gitignored):
  .private-data/native-ui-v1/manifest.tsv
  .private-data/eval/native-ui-v1.eval
  .private-data/native-ui-v1/benchmark-summary.json

Usage:
  python tools/build-native-ui-benchmark.py --limit 300
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import random
import re
import shutil
import subprocess
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DATA_ROOT = REPO_ROOT / ".private-data" / "native-ui-v1"
EVAL_ROOT = REPO_ROOT / ".private-data" / "eval"
SEED = 20260728

GENERIC_OTHER_LAYOUTS = {
    "bare",
    "calculator",
    "camera",
    "editor",
    "form",
    "login",
    "menu",
    "modal",
    "settings",
    "terms",
    "tutorial",
}

CATEGORY_TOPICS = {
    "BOOKS_AND_REFERENCE": "BOOK",
    "COMICS": "BOOK",
    "FINANCE": "FINANCE",
    "FOOD_AND_DRINK": "RECIPE",
    "GAME": "GAME",
    "HEALTH_AND_FITNESS": "SPORT",
    "MAPS_AND_NAVIGATION": "PLACE",
    "MUSIC_AND_AUDIO": "MUSIC",
    "NEWS_AND_MAGAZINES": "NEWS",
    "SHOPPING": "PRODUCT",
    "SOCIAL": "SOCIAL",
    "SPORTS": "SPORT",
    "TRAVEL_AND_LOCAL": "PLACE",
    "VIDEO_PLAYERS": "VIDEO",
    "VIDEO_PLAYERS_AND_EDITORS": "VIDEO",
}


@dataclass(frozen=True)
class Candidate:
    screen_id: str
    image: Path
    metadata: Path | None
    design_topic: str
    app_id: str
    app_category: str
    topic: str
    reason: str


def locate_child(root: Path, suffix: str) -> dict[str, Path]:
    return {path.stem: path for path in root.rglob(f"*{suffix}")}


def load_design_topics(path: Path) -> dict[str, str]:
    with path.open(encoding="utf-8", newline="") as handle:
        return {
            row["screen_id"].strip(): row["topic"].strip().lower()
            for row in csv.DictReader(handle)
        }


def mapped_category(categories: list[str]) -> tuple[str | None, str]:
    normalized = [str(value).strip().upper() for value in categories if str(value).strip()]
    for category in normalized:
        if category.startswith("GAME_"):
            return "GAME", category
        if category in CATEGORY_TOPICS:
            return CATEGORY_TOPICS[category], category
    return None, normalized[0] if normalized else ""


def gold_topic(design_topic: str, metadata: dict) -> tuple[str | None, str, str]:
    categories = metadata.get("category") or []
    if isinstance(categories, str):
        categories = [categories]
    app_topic, category = mapped_category(categories)

    if design_topic in GENERIC_OTHER_LAYOUTS:
        return "OTHER", f"generic-ui:{design_topic}", category
    if design_topic == "maps":
        return "PLACE", "layout:maps", category
    if design_topic == "chat":
        return "SOCIAL", "layout:chat", category
    if design_topic == "mediaplayer":
        if app_topic in {"MUSIC", "VIDEO"}:
            return app_topic, f"layout:mediaplayer+category:{category}", category
        if category == "ENTERTAINMENT":
            return "VIDEO", "layout:mediaplayer+category:ENTERTAINMENT", category
        return None, "ambiguous-mediaplayer", category
    if app_topic:
        return app_topic, f"category:{category}", category
    return None, "ambiguous-app-category", category


def candidates(root: Path) -> list[Candidate]:
    topics = load_design_topics(root / "design_topics.csv")
    images = locate_child(root / "screenshots", ".jpg")
    metadata_paths = locate_child(root / "metadata", ".json")
    result: list[Candidate] = []
    for screen_id, design_topic in topics.items():
        image = images.get(screen_id)
        if image is None:
            continue
        metadata_path = metadata_paths.get(screen_id)
        metadata = {}
        if metadata_path:
            try:
                metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                metadata = {}
        topic, reason, app_category = gold_topic(design_topic, metadata)
        if topic is None:
            continue
        result.append(
            Candidate(
                screen_id=screen_id,
                image=image,
                metadata=metadata_path,
                design_topic=design_topic,
                app_id=str(metadata.get("app_id") or f"unknown:{screen_id}"),
                app_category=app_category,
                topic=topic,
                reason=reason,
            )
        )
    return result


def select_balanced(rows: list[Candidate], limit: int) -> list[Candidate]:
    rng = random.Random(SEED)
    # Split by a stable hash of app_id *before* choosing screens. The visual model
    # excludes all apps represented here, so sibling screens from the same app can
    # never leak into training. Grow the holdout app bucket only as far as needed.
    shuffled: list[Candidate] = []
    for threshold in (64, 80, 96, 112, 128, 160, 192):
        shuffled = [
            row
            for row in rows
            if hashlib.sha256(row.app_id.encode("utf-8")).digest()[0] < threshold
        ]
        semantic_count = sum(row.topic != "OTHER" for row in shuffled)
        if len(shuffled) >= limit * 1.15 and semantic_count >= limit * 0.60:
            break
    rng.shuffle(shuffled)

    # One app can contribute several highly similar screens. Cap this so accuracy
    # reflects diversity rather than memorizing one app's chrome.
    app_counts: Counter[str] = Counter()
    diverse: list[Candidate] = []
    for row in shuffled:
        if app_counts[row.app_id] >= 3:
            continue
        app_counts[row.app_id] += 1
        diverse.append(row)

    by_topic: dict[str, list[Candidate]] = defaultdict(list)
    for row in diverse:
        by_topic[row.topic].append(row)

    selected: list[Candidate] = []
    topic_counts: Counter[str] = Counter()
    soft_caps = {topic: 35 for topic in by_topic}
    soft_caps["OTHER"] = min(100, max(70, limit // 3))

    # Round robin preserves rare semantic topics before OTHER consumes the sample.
    active = sorted(by_topic)
    offsets: Counter[str] = Counter()
    while active and len(selected) < limit:
        next_active: list[str] = []
        for topic in active:
            index = offsets[topic]
            if index >= len(by_topic[topic]) or topic_counts[topic] >= soft_caps[topic]:
                continue
            selected.append(by_topic[topic][index])
            offsets[topic] += 1
            topic_counts[topic] += 1
            if len(selected) >= limit:
                break
            if offsets[topic] < len(by_topic[topic]):
                next_active.append(topic)
        active = next_active

    # If a rare class could not fill its cap, fill from the remaining diverse pool.
    selected_ids = {row.screen_id for row in selected}
    for row in diverse:
        if len(selected) >= limit:
            break
        if row.screen_id not in selected_ids:
            selected.append(row)
            selected_ids.add(row.screen_id)

    return sorted(selected, key=lambda row: int(row.screen_id))


def find_tesseract() -> str:
    candidates = (
        os.environ.get("TESSERACT"),
        str(Path.home() / "scoop" / "shims" / "tesseract.exe"),
        shutil.which("tesseract"),
    )
    for candidate in candidates:
        if candidate and Path(candidate).exists():
            return candidate
    raise SystemExit("Tesseract bulunamadi. TESSERACT ortam degiskenini ayarlayin.")


def ocr_one(tesseract: str, cache_dir: Path, row: Candidate) -> str:
    cache = cache_dir / f"{row.screen_id}.txt"
    if cache.exists():
        return cache.read_text(encoding="utf-8")
    result = subprocess.run(
        [tesseract, str(row.image), "stdout", "-l", "eng"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    text = (result.stdout or "").strip()
    cache.write_text(text, encoding="utf-8")
    return text


def write_outputs(root: Path, selected: list[Candidate], workers: int) -> None:
    tesseract = find_tesseract()
    cache_dir = root / "ocr"
    cache_dir.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        ocr_texts = list(pool.map(lambda row: ocr_one(tesseract, cache_dir, row), selected))

    manifest_path = root / "manifest.tsv"
    with manifest_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(
            (
                "screen_id",
                "file",
                "topic",
                "design_topic",
                "app_category",
                "app_id",
                "gold_reason",
                "ocr_chars",
            )
        )
        for row, text in zip(selected, ocr_texts):
            writer.writerow(
                (
                    row.screen_id,
                    row.image.name,
                    row.topic,
                    row.design_topic,
                    row.app_category,
                    row.app_id,
                    row.reason,
                    len(text),
                )
            )

    EVAL_ROOT.mkdir(parents=True, exist_ok=True)
    eval_path = EVAL_ROOT / "native-ui-v1.eval"
    with eval_path.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(
            "# Enrico/RICO native mobile UI blind benchmark. Evaluation only; NEVER train.\n"
            "# Gold labels use conservative layout + Play category mapping.\n\n"
        )
        for row, text in zip(selected, ocr_texts):
            handle.write(f"=== case native-ui-{row.screen_id}\n")
            handle.write(f"display: {row.image.name}\n")
            handle.write(f"topic: {row.topic}\n")
            handle.write(f"source_group: app:{row.app_id}\n")
            handle.write("user_group: public-enrico\n")
            handle.write(f"design_topic: {row.design_topic}\n")
            handle.write(f"gold_reason: {row.reason}\n")
            handle.write("--- ocr\n")
            handle.write(text)
            handle.write("\n=== end\n\n")

    summary = {
        "schema": 1,
        "source": "Enrico (derived from RICO)",
        "source_url": "https://userinterfaces.aalto.fi/enrico/",
        "seed": SEED,
        "examples": len(selected),
        "topics": dict(sorted(Counter(row.topic for row in selected).items())),
        "design_topics": dict(sorted(Counter(row.design_topic for row in selected).items())),
        "app_categories": dict(sorted(Counter(row.app_category or "-" for row in selected).items())),
        "empty_ocr": sum(not text.strip() for text in ocr_texts),
        "median_ocr_chars": sorted(map(len, ocr_texts))[len(ocr_texts) // 2] if ocr_texts else 0,
        "evaluation_only": True,
    }
    (root / "benchmark-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"\nmanifest: {manifest_path}")
    print(f"eval:     {eval_path}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=DATA_ROOT)
    parser.add_argument("--limit", type=int, default=300)
    parser.add_argument("--workers", type=int, default=min(8, os.cpu_count() or 4))
    args = parser.parse_args()
    rows = candidates(args.root)
    if not rows:
        raise SystemExit(f"Etiketlenebilir ekran bulunamadi: {args.root}")
    selected = select_balanced(rows, args.limit)
    if len(selected) < args.limit:
        raise SystemExit(f"Yalniz {len(selected)} guvenilir ekran bulundu (hedef {args.limit}).")
    write_outputs(args.root, selected, args.workers)


if __name__ == "__main__":
    main()
