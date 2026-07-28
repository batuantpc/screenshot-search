"""Train and evaluate a native screenshot visual topic model.

This is deliberately separate from the OCR model. A frozen ImageNet-pretrained
MobileNetV3-Small converts the full screenshot to a visual embedding; a balanced
linear head learns Memento topics from conservatively labeled Enrico screens.

Leakage rule: every app_id present in native-ui-v1 (the 300-screen blind set) is
excluded wholesale from training. Screens from the same app can therefore never
appear on both sides of the reported metric.

The exported ONNX model accepts a normalized float tensor [1, 3, 224, 224] and
returns logits in the class order stored in visual-topic-model-v1.meta.json.

Usage:
  <workspace-python> tools/train-visual-topic-model.py
  <workspace-python> tools/train-visual-topic-model.py --export
"""

from __future__ import annotations

import argparse
import csv
import importlib.util
import json
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

import numpy as np
import torch
from PIL import Image
from torch import nn
from torch.utils.data import DataLoader, Dataset
from torchvision.models import MobileNet_V3_Small_Weights, mobilenet_v3_small

REPO_ROOT = Path(__file__).resolve().parent.parent
DATA_ROOT = REPO_ROOT / ".private-data" / "native-ui-v1"
MODEL_ROOT = REPO_ROOT / "android" / "app" / "src" / "main" / "assets"
MODEL_PATH = MODEL_ROOT / "visual-topic-model-v1.onnx"
META_PATH = MODEL_ROOT / "visual-topic-model-v1.meta.json"
CACHE_PATH = DATA_ROOT / "mobilenet-v3-small-embeddings.npz"
REPORT_PATH = DATA_ROOT / "visual-model-report.json"
WEB_TRAIN_DIRS = (
    "corpus",
    "en-v1",
    "feed-v1",
    "feed-v2",
    "real-v1",
    "weak-v1",
    "wiki-v1",
)


@dataclass(frozen=True)
class Row:
    screen_id: str
    image: Path
    topic: str
    app_id: str
    source: str


class ScreenDataset(Dataset):
    def __init__(self, rows: list[Row], transform) -> None:
        self.rows = rows
        self.transform = transform

    def __len__(self) -> int:
        return len(self.rows)

    def __getitem__(self, index: int):
        row = self.rows[index]
        with Image.open(row.image) as image:
            tensor = self.transform(image.convert("RGB"))
        return row.screen_id, tensor


class ExportModel(nn.Module):
    def __init__(self, backbone: nn.Module, coefficients: np.ndarray, intercept: np.ndarray) -> None:
        super().__init__()
        self.features = backbone.features
        self.avgpool = backbone.avgpool
        self.flatten = nn.Flatten(1)
        self.classifier = nn.Linear(coefficients.shape[1], coefficients.shape[0])
        with torch.no_grad():
            self.classifier.weight.copy_(torch.from_numpy(coefficients.astype(np.float32)))
            self.classifier.bias.copy_(torch.from_numpy(intercept.astype(np.float32)))

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        value = self.features(image)
        value = self.avgpool(value)
        value = self.flatten(value)
        return self.classifier(value)


def load_benchmark_module():
    path = REPO_ROOT / "tools" / "build-native-ui-benchmark.py"
    spec = importlib.util.spec_from_file_location("memento_native_benchmark", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Benchmark araci yuklenemedi: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def load_holdout() -> tuple[set[str], set[str]]:
    path = DATA_ROOT / "manifest.tsv"
    with path.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    return {row["screen_id"] for row in rows}, {row["app_id"] for row in rows}


def load_rows() -> tuple[list[Row], list[Row]]:
    benchmark = load_benchmark_module()
    all_candidates = benchmark.candidates(DATA_ROOT)
    holdout_ids, holdout_apps = load_holdout()
    train: list[Row] = []
    test: list[Row] = []
    for item in all_candidates:
        row = Row(item.screen_id, item.image, item.topic, item.app_id, "enrico")
        if item.screen_id in holdout_ids:
            test.append(row)
        elif item.app_id not in holdout_apps:
            train.append(row)
    train.extend(load_web_training_rows())
    return train, test


def load_web_training_rows() -> list[Row]:
    rows: list[Row] = []
    private_root = REPO_ROOT / ".private-data"
    for directory_name in WEB_TRAIN_DIRS:
        directory = private_root / directory_name
        labels = directory / "labels.tsv"
        if not labels.exists():
            continue
        with labels.open(encoding="utf-8", newline="") as handle:
            reader = csv.DictReader(
                (line for line in handle if line.strip() and not line.startswith("#")),
                delimiter="\t",
            )
            for item in reader:
                image = directory / item.get("file", "")
                topic = (item.get("topic") or "").strip()
                case_id = (item.get("id") or "").strip()
                if not image.is_file() or not topic or not case_id:
                    continue
                domain = urlparse(item.get("url") or "").netloc.lower().removeprefix("www.")
                rows.append(
                    Row(
                        screen_id=f"{directory_name}:{case_id}",
                        image=image,
                        topic=topic,
                        app_id=f"web:{domain or directory_name}",
                        source=f"web:{directory_name}",
                    )
                )
    return rows


def extract_embeddings(rows: list[Row], batch_size: int, workers: int) -> dict[str, np.ndarray]:
    weights = MobileNet_V3_Small_Weights.DEFAULT
    backbone = mobilenet_v3_small(weights=weights).eval()
    extractor = nn.Sequential(backbone.features, backbone.avgpool, nn.Flatten(1)).eval()
    loader = DataLoader(
        ScreenDataset(rows, weights.transforms()),
        batch_size=batch_size,
        shuffle=False,
        num_workers=workers,
    )
    result: dict[str, np.ndarray] = {}
    with torch.inference_mode():
        for batch_index, (screen_ids, images) in enumerate(loader, start=1):
            vectors = extractor(images).cpu().numpy().astype(np.float32)
            for screen_id, vector in zip(screen_ids, vectors):
                result[str(screen_id)] = vector
            if batch_index % 10 == 0:
                print(f"embedding batch {batch_index}/{len(loader)}")
    return result


def load_or_create_embeddings(rows: list[Row], batch_size: int, workers: int) -> dict[str, np.ndarray]:
    expected = {row.screen_id for row in rows}
    if CACHE_PATH.exists():
        cache = np.load(CACHE_PATH)
        ids = [str(value) for value in cache["ids"]]
        if set(ids) == expected:
            return {screen_id: vector for screen_id, vector in zip(ids, cache["vectors"])}
    vectors = extract_embeddings(rows, batch_size, workers)
    ordered = sorted(vectors)
    np.savez_compressed(
        CACHE_PATH,
        ids=np.array(ordered),
        vectors=np.stack([vectors[screen_id] for screen_id in ordered]),
    )
    return vectors


def softmax(logits: np.ndarray) -> np.ndarray:
    shifted = logits - logits.max(axis=1, keepdims=True)
    values = np.exp(shifted)
    return values / values.sum(axis=1, keepdims=True)


def train_and_report(train: list[Row], test: list[Row], vectors: dict[str, np.ndarray]):
    classes = sorted({row.topic for row in train})
    class_index = {topic: index for index, topic in enumerate(classes)}
    train_x = np.stack([vectors[row.screen_id] for row in train])
    train_y = np.array([class_index[row.topic] for row in train], dtype=np.int64)
    test_x = np.stack([vectors[row.screen_id] for row in test])
    test_y = np.array([class_index[row.topic] for row in test], dtype=np.int64)

    torch.manual_seed(20260728)
    head = nn.Linear(train_x.shape[1], len(classes))
    counts = Counter(train_y.tolist())
    class_weights = torch.tensor(
        [len(train_y) / (len(classes) * counts[index]) for index in range(len(classes))],
        dtype=torch.float32,
    )
    loss_function = nn.CrossEntropyLoss(weight=class_weights)
    optimizer = torch.optim.AdamW(head.parameters(), lr=0.015, weight_decay=0.003)
    features = torch.from_numpy(train_x)
    labels = torch.from_numpy(train_y)
    best_loss = float("inf")
    best_state = None
    stale = 0
    for epoch in range(1_001):
        optimizer.zero_grad(set_to_none=True)
        logits = head(features)
        loss = loss_function(logits, labels)
        loss.backward()
        optimizer.step()
        value = float(loss.detach())
        if value < best_loss - 1e-5:
            best_loss = value
            best_state = {key: tensor.detach().clone() for key, tensor in head.state_dict().items()}
            stale = 0
        else:
            stale += 1
        if epoch % 100 == 0:
            train_accuracy = float((logits.argmax(1) == labels).float().mean())
            print(f"head epoch={epoch} loss={value:.4f} train={train_accuracy:.3f}")
        if stale >= 100:
            break
    if best_state:
        head.load_state_dict(best_state)
    head.eval()
    with torch.inference_mode():
        probabilities = torch.softmax(head(torch.from_numpy(test_x)), dim=1).numpy()
    indices = probabilities.argmax(axis=1)
    predicted = np.array([classes[index] for index in indices])
    expected_names = np.array([classes[index] for index in test_y])
    confidence = probabilities[np.arange(len(test)), indices]
    correct = predicted == expected_names

    per_topic = {}
    for topic in sorted(set(expected_names)):
        mask = expected_names == topic
        per_topic[topic] = {
            "correct": int(correct[mask].sum()),
            "total": int(mask.sum()),
            "accuracy": float(correct[mask].mean()),
        }
    coverage = {}
    for threshold in (0.50, 0.60, 0.70, 0.80, 0.90):
        mask = confidence >= threshold
        coverage[f"{threshold:.2f}"] = {
            "accepted": int(mask.sum()),
            "coverage": float(mask.mean()),
            "accuracy": float(correct[mask].mean()) if mask.any() else None,
        }
    pairs = Counter(
        (expected, guess)
        for expected, guess in zip(expected_names, predicted)
        if expected != guess
    )
    report = {
        "schema": 1,
        "backbone": "torchvision/mobilenet_v3_small ImageNet",
        "split": "app-group holdout; all screens from holdout apps excluded from training",
        "training_examples": len(train),
        "test_examples": len(test),
        "training_apps": len({row.app_id for row in train}),
        "test_apps": len({row.app_id for row in test}),
        "training_sources": dict(sorted(Counter(row.source for row in train).items())),
        "classes": classes,
        "training_topics": dict(sorted(Counter(row.topic for row in train).items())),
        "test_topics": dict(sorted(Counter(row.topic for row in test).items())),
        "accuracy": float(correct.mean()),
        "per_topic": per_topic,
        "confidence_policy": coverage,
        "top_confusions": [
            {"expected": expected, "predicted": guess, "count": count}
            for (expected, guess), count in pairs.most_common(15)
        ],
    }
    REPORT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return head, classes, report


def export_onnx(head: nn.Linear, classes: list[str], report: dict) -> None:
    weights = MobileNet_V3_Small_Weights.DEFAULT
    backbone = mobilenet_v3_small(weights=weights).eval()
    model = ExportModel(
        backbone,
        head.weight.detach().numpy(),
        head.bias.detach().numpy(),
    ).eval()
    MODEL_ROOT.mkdir(parents=True, exist_ok=True)
    example = torch.zeros(1, 3, 224, 224)
    torch.onnx.export(
        model,
        example,
        MODEL_PATH,
        input_names=["image"],
        output_names=["logits"],
        opset_version=18,
        dynamo=False,
    )
    meta = {
        "schema": 1,
        "classes": classes,
        "input": {
            "shape": [1, 3, 224, 224],
            "resize": 256,
            "center_crop": 224,
            "mean": [0.485, 0.456, 0.406],
            "std": [0.229, 0.224, 0.225],
            "layout": "NCHW",
            "color": "RGB",
        },
        "output": "logits",
        "holdout_accuracy": report["accuracy"],
        "confidence_policy": report["confidence_policy"],
        "training_examples": report["training_examples"],
        "evaluation_only_source": "Enrico/RICO",
    }
    META_PATH.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"\nONNX: {MODEL_PATH} ({MODEL_PATH.stat().st_size / 1024 / 1024:.1f} MiB)")
    print(f"meta: {META_PATH}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--workers", type=int, default=0)
    parser.add_argument("--export", action="store_true")
    args = parser.parse_args()
    train, test = load_rows()
    if not train or not test:
        raise SystemExit("Train/test satirlari bulunamadi; once native benchmark'i olusturun.")
    print(f"train={len(train)} test={len(test)}")
    vectors = load_or_create_embeddings(train + test, args.batch_size, args.workers)
    head, classes, report = train_and_report(train, test, vectors)
    if args.export:
        export_onnx(head, classes, report)


if __name__ == "__main__":
    main()
