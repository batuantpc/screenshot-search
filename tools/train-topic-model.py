"""Memento konu modelini kaynak sizintisi olmadan egitir ve olcer.

Temel kurallar:
  * Egitim dosyalari acik allowlist'tir; adinda holdout/test gecen hicbir dosya modele girmez.
  * Ayni OCR metni farkli case_id ile gelse bile hash ile tekillestirilir.
  * Capraz dogrulama satir bazli degil kaynak/domain grubu bazlidir.
  * Native holdout ayri dosyadir ve en az user_group + source_group metadata'si ister.

Kullanim:
    python tools/train-topic-model.py
    python tools/train-topic-model.py --export
    python tools/train-topic-model.py --native-holdout .private-data/eval/native-holdout-v1.eval
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import struct
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

REPO_ROOT = Path(__file__).resolve().parent.parent
EVAL_DIR = REPO_ROOT / ".private-data" / "eval"
MODEL_DIR = REPO_ROOT / "android" / "app" / "src" / "test" / "resources" / "models"
MODEL_NAME = "topic-model-v1.bin"
MODEL_META_NAME = "topic-model-v1.meta.json"

# Dosya adlari (stem) bilincli ve gozden gecirilebilir bir allowlist.
# Holdout/test dosyalari bu listeye eklenmemeli; script ayrica isimden de engeller.
TRAIN_DATASETS = {
    "en-v1-dev",
    "feed-v1",
    "feed-v2",
    "real-v1",
    "user-corrections",
    "weak-v1",
    "web-corpus-dev",
    "wiki-v1",
}
FIXED_EVAL_DATASETS = {
    "device-verified",  # gercek cihaz, fakat gecmiste tuning gordu: legacy benchmark
    "en-v1-holdout",
    "web-corpus-test",
    "web-holdout-v1",
}
FORBIDDEN_TRAIN_TOKENS = ("holdout", "test")
CORRECTION_WEIGHT = 8.0
DOMAIN_PATTERN = re.compile(
    r"\b(?:https?://)?(?:www\.)?([a-z0-9-]+(?:\.[a-z0-9-]+)+)\b",
    re.I,
)


@dataclass(frozen=True)
class Example:
    case_id: str
    topic: str
    text: str
    dataset: str
    source_group: str
    user_group: str | None = None

    @property
    def text_hash(self) -> str:
        normalized = re.sub(r"\s+", " ", self.text.casefold()).strip()
        return hashlib.sha256(normalized.encode("utf-8")).hexdigest()

    @property
    def cv_group(self) -> str:
        # Native veride ayni kullanicinin ekran dili/uygulama seti ayirt edici olabilir.
        # Kullanici varsa user bazli ayir; web veride domain/template bazli ayir.
        return f"user:{self.user_group}" if self.user_group else self.source_group


def parse_eval(path: Path, manifest_groups: dict[str, str]) -> list[Example]:
    examples: list[Example] = []
    content = path.read_text(encoding="utf-8")
    for block in re.split(r"^=== case ", content, flags=re.M)[1:]:
        lines = block.splitlines()
        case_id = lines[0].strip()
        metadata: dict[str, str] = {}
        for line in lines[1:]:
            if line.startswith("--- "):
                break
            if ":" in line:
                key, value = line.split(":", 1)
                metadata[key.strip()] = value.strip()
        ocr = re.search(r"^--- ocr\n(.*?)(?=^--- |^=== end)", block, re.S | re.M)
        text = (ocr.group(1) if ocr else "").strip()
        topic = metadata.get("topic", "")
        if not topic or not text:
            continue
        explicit_source = metadata.get("source_group") or metadata.get("group")
        source_group = (
            explicit_source
            or manifest_groups.get(case_id)
            or infer_domain_group(text)
            or f"dataset:{path.stem}"
        )
        examples.append(
            Example(
                case_id=case_id,
                topic=topic,
                text=text,
                dataset=path.stem,
                source_group=source_group,
                user_group=metadata.get("user_group") or None,
            )
        )
    return examples


def infer_domain_group(text: str) -> str | None:
    match = DOMAIN_PATTERN.search(text[:1_000])
    if not match:
        return None
    return f"domain:{match.group(1).lower().removeprefix('www.')}"


def load_manifest_groups() -> dict[str, str]:
    """Korpus case_id -> domain/template grubu."""
    groups: dict[str, str] = {}
    for path in sorted((REPO_ROOT / "tools").glob("corpus-*.tsv")):
        rows = [
            line for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        ]
        if not rows:
            continue
        reader = csv.DictReader(rows, delimiter="\t")
        if not reader.fieldnames or "id" not in reader.fieldnames or "url" not in reader.fieldnames:
            continue
        for row in reader:
            case_id = (row.get("id") or "").strip()
            host = urlparse((row.get("url") or "").strip()).netloc.lower().removeprefix("www.")
            if case_id and host:
                groups.setdefault(case_id, f"domain:{host}")
    return groups


def dedupe(examples: list[Example], label: str) -> list[Example]:
    by_id: set[str] = set()
    by_text: set[str] = set()
    unique: list[Example] = []
    duplicate_ids = duplicate_texts = 0
    for example in examples:
        if example.case_id in by_id:
            duplicate_ids += 1
            continue
        if example.text_hash in by_text:
            duplicate_texts += 1
            continue
        by_id.add(example.case_id)
        by_text.add(example.text_hash)
        unique.append(example)
    if duplicate_ids or duplicate_texts:
        print(
            f"{label}: {duplicate_ids} tekrar id, {duplicate_texts} tekrar OCR metni dislandi",
            file=sys.stderr,
        )
    return unique


def load_datasets(native_holdout: Path | None) -> tuple[list[Example], dict[str, list[Example]]]:
    manifest_groups = load_manifest_groups()
    train_raw: list[Example] = []
    eval_sets: dict[str, list[Example]] = {}

    for path in sorted(EVAL_DIR.glob("*.eval")):
        dataset = path.stem
        if dataset in TRAIN_DATASETS:
            if any(token in dataset.casefold() for token in FORBIDDEN_TRAIN_TOKENS):
                sys.exit(f"Guvenlik: holdout/test isimli dataset egitime giremez: {dataset}")
            train_raw.extend(parse_eval(path, manifest_groups))
        elif dataset in FIXED_EVAL_DATASETS:
            eval_sets[dataset] = dedupe(parse_eval(path, manifest_groups), dataset)
        else:
            print(f"bilgi: rolü tanimsiz eval dosyasi atlandi: {path.name}", file=sys.stderr)

    train = dedupe(train_raw, "train")
    if native_holdout:
        native = dedupe(parse_eval(native_holdout, manifest_groups), native_holdout.stem)
        validate_native_holdout(native)
        eval_sets["native-holdout"] = native
    return train, eval_sets


def validate_native_holdout(examples: list[Example]) -> None:
    missing_user = [e.case_id for e in examples if not e.user_group]
    missing_source = [e.case_id for e in examples if e.source_group.startswith("dataset:")]
    if missing_user or missing_source:
        details = []
        if missing_user:
            details.append(f"user_group eksik: {', '.join(missing_user[:5])}")
        if missing_source:
            details.append(f"source_group eksik: {', '.join(missing_source[:5])}")
        sys.exit("Native holdout metadata gecersiz; " + "; ".join(details))


def build_pipeline():
    from sklearn.feature_extraction.text import TfidfVectorizer
    from sklearn.linear_model import LogisticRegression
    from sklearn.pipeline import Pipeline

    return Pipeline([
        ("tfidf", TfidfVectorizer(
            analyzer="char_wb",
            ngram_range=(3, 5),
            min_df=2,
            max_features=60_000,
            sublinear_tf=True,
            lowercase=True,
        )),
        ("clf", LogisticRegression(
            C=4.0,
            max_iter=3000,
            class_weight="balanced",
        )),
    ])


def weights_for(examples: list[Example]):
    import numpy as np
    return np.array([
        CORRECTION_WEIGHT if e.case_id.startswith("corr-") else 1.0
        for e in examples
    ])


def score_predictions(gold, predicted) -> tuple[int, int, Counter, Counter, Counter]:
    hit = int((predicted == gold).sum())
    per_hit: Counter = Counter()
    per_total: Counter = Counter()
    confusion: Counter = Counter()
    for expected, guess in zip(gold, predicted):
        per_total[expected] += 1
        if expected == guess:
            per_hit[expected] += 1
        else:
            confusion[(expected, guess)] += 1
    return hit, len(gold), per_hit, per_total, confusion


def print_score(label: str, gold, predicted) -> None:
    hit, total, per_hit, per_total, confusion = score_predictions(gold, predicted)
    macro = sum(per_hit[t] / per_total[t] for t in per_total) / max(1, len(per_total))
    print(f"{label}: {hit}/{total} = {100.0 * hit / max(1, total):.1f}% | macro={100.0 * macro:.1f}%")
    weakest = sorted(per_total, key=lambda t: per_hit[t] / per_total[t])[:5]
    print("  zayif siniflar: " + ", ".join(
        f"{topic} {per_hit[topic]}/{per_total[topic]}" for topic in weakest
    ))
    if confusion:
        print("  karisanlar: " + ", ".join(
            f"{gold_topic}->{guess}:{count}"
            for (gold_topic, guess), count in confusion.most_common(6)
        ))


def grouped_cross_validate(examples: list[Example]) -> None:
    import numpy as np
    from sklearn.model_selection import StratifiedGroupKFold

    groups = np.array([e.cv_group for e in examples], dtype=object)
    unique_groups = sorted(set(groups))
    folds = min(5, len(unique_groups))
    if folds < 2:
        sys.exit("Kaynak gruplu capraz dogrulama icin en az 2 grup gerekli")

    x = np.array([e.text for e in examples], dtype=object)
    y = np.array([e.topic for e in examples], dtype=object)
    weights = weights_for(examples)
    splitter = StratifiedGroupKFold(n_splits=folds, shuffle=True, random_state=42)
    all_gold: list[str] = []
    all_pred: list[str] = []

    print(f"\nKAYNAK GRUPLU CAPRAZ DOGRULAMA: {len(examples)} ornek, {len(unique_groups)} grup")
    for fold, (train_indices, test_indices) in enumerate(splitter.split(x, y, groups), start=1):
        train_groups = set(groups[train_indices])
        test_groups = set(groups[test_indices])
        overlap = train_groups & test_groups
        if overlap:
            sys.exit(f"Grup sizintisi bulundu: {sorted(overlap)[:3]}")
        model = build_pipeline()
        model.fit(x[train_indices], y[train_indices], clf__sample_weight=weights[train_indices])
        predicted = model.predict(x[test_indices])
        hit = int((predicted == y[test_indices]).sum())
        print(
            f"  kat {fold}: {hit}/{len(test_indices)} = "
            f"{100.0 * hit / len(test_indices):.1f}% ({len(test_groups)} tamamen ayrik grup)"
        )
        all_gold.extend(y[test_indices].tolist())
        all_pred.extend(predicted.tolist())

    print_score(
        "MODEL (source-group CV; ayni domain/user iki tarafta yok)",
        np.array(all_gold, dtype=object),
        np.array(all_pred, dtype=object),
    )


def evaluate_fixed_sets(train: list[Example], eval_sets: dict[str, list[Example]]) -> None:
    import numpy as np

    if not eval_sets:
        return
    model = build_pipeline()
    x_train = np.array([e.text for e in train], dtype=object)
    y_train = np.array([e.topic for e in train], dtype=object)
    model.fit(x_train, y_train, clf__sample_weight=weights_for(train))
    train_ids = {e.case_id for e in train}
    train_hashes = {e.text_hash for e in train}

    print("\nSABIT DEGERLENDIRME SETLERI (egitime dahil degil):")
    for name, raw_examples in sorted(eval_sets.items()):
        overlaps = [
            e for e in raw_examples
            if e.case_id in train_ids or e.text_hash in train_hashes
        ]
        examples = [e for e in raw_examples if e not in overlaps]
        if overlaps:
            print(f"  {name}: {len(overlaps)} id/metin sizintisi skordan dislandi")
        if not examples:
            print(f"  {name}: skorlanabilir ornek yok")
            continue
        predicted = model.predict(np.array([e.text for e in examples], dtype=object))
        print_score(
            f"  {name}",
            np.array([e.topic for e in examples], dtype=object),
            predicted,
        )
        if name == "native-holdout":
            users = {e.user_group for e in examples}
            sources = {e.source_group for e in examples}
            readiness = "DONDURULMUS" if len(examples) >= 200 and len(users) >= 5 else "PROVIZYONEL"
            print(
                f"    native durum={readiness}; user={len(users)}, "
                f"source={len(sources)}, n={len(examples)}"
            )


def export(examples: list[Example]) -> None:
    """Modeli egitip cihaz icin kompakt ikili dosyaya ve provenance meta'sina yazar."""
    import numpy as np

    model = build_pipeline()
    x = np.array([e.text for e in examples], dtype=object)
    y = np.array([e.topic for e in examples], dtype=object)
    model.fit(x, y, clf__sample_weight=weights_for(examples))
    tfidf = model.named_steps["tfidf"]
    clf = model.named_steps["clf"]

    vocab = tfidf.vocabulary_
    idf = tfidf.idf_
    classes = list(clf.classes_)
    coef = clf.coef_.astype(np.float32)
    intercept = clf.intercept_.astype(np.float32)
    terms = [None] * len(vocab)
    for term, index in vocab.items():
        terms[index] = term

    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    out = MODEL_DIR / MODEL_NAME
    with out.open("wb") as fh:
        fh.write(b"MTM1")
        fh.write(struct.pack("<III", len(classes), len(terms), tfidf.ngram_range[1]))
        fh.write(struct.pack("<I", tfidf.ngram_range[0]))
        for name in classes:
            raw = name.encode("utf-8")
            fh.write(struct.pack("<H", len(raw)))
            fh.write(raw)
        for term, weight in zip(terms, idf):
            raw = term.encode("utf-8")
            fh.write(struct.pack("<H", len(raw)))
            fh.write(raw)
            fh.write(struct.pack("<f", float(weight)))
        fh.write(coef.tobytes(order="C"))
        fh.write(intercept.tobytes(order="C"))

    fingerprint = hashlib.sha256(
        "\n".join(sorted(e.text_hash for e in examples)).encode("ascii")
    ).hexdigest()
    meta = {
        "schema": 1,
        "classes": classes,
        "features": len(terms),
        "ngram_range": list(tfidf.ngram_range),
        "bytes": out.stat().st_size,
        "training_examples": len(examples),
        "training_groups": len({e.cv_group for e in examples}),
        "training_datasets": dict(sorted(Counter(e.dataset for e in examples).items())),
        "training_fingerprint_sha256": fingerprint,
        "excluded_eval_datasets": sorted(FIXED_EVAL_DATASETS),
        "evaluation_policy": "source-group CV; fixed holdouts never train",
    }
    (MODEL_DIR / MODEL_META_NAME).write_text(
        json.dumps(meta, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(meta, ensure_ascii=False, indent=2))
    print(f"model -> {out} ({out.stat().st_size / 1024:.0f} KB)")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--export", action="store_true")
    parser.add_argument("--native-holdout", type=Path)
    args = parser.parse_args()

    train, eval_sets = load_datasets(args.native_holdout)
    if not train:
        sys.exit(f"Egitim verisi bulunamadi: {EVAL_DIR}")
    print(
        f"EGITIM: {len(train)} etiketli ornek, {len({e.topic for e in train})} sinif, "
        f"{len({e.cv_group for e in train})} kaynak/user grubu"
    )
    print("dataset: " + ", ".join(
        f"{name}={count}" for name, count in sorted(Counter(e.dataset for e in train).items())
    ))
    print("dagilim: " + ", ".join(
        f"{topic}={count}" for topic, count in Counter(e.topic for e in train).most_common()
    ))
    corrections = sum(e.case_id.startswith("corr-") for e in train)
    if corrections:
        print(f"kullanici duzeltmesi: {corrections} ornek (x{CORRECTION_WEIGHT:g} agirlik)")

    grouped_cross_validate(train)
    evaluate_fixed_sets(train, eval_sets)
    if args.export:
        print()
        export(train)


if __name__ == "__main__":
    main()
