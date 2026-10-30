"""Evaluate an actual vision-language model on the native screenshot holdout.

Unlike the ImageNet MobileNet experiment, MobileCLIP was trained to align images
with natural-language concepts. This is the relevant architecture for Screenshot Search:
the model can compare a screenshot with prompts such as "a recipe screenshot" or
"a sports screenshot" instead of relying on OCR token collisions.

This script is an experiment only. It downloads Apple's official MobileCLIP-S0
checkpoint into the private model cache and never copies it into the APK.
"""

from __future__ import annotations

import csv
import json
from collections import Counter
from pathlib import Path

import mobileclip
import torch
from huggingface_hub import hf_hub_download
from PIL import Image

REPO_ROOT = Path(__file__).resolve().parent.parent
DATA_ROOT = REPO_ROOT / ".private-data" / "native-ui-v1"
REPORT_PATH = DATA_ROOT / "mobileclip-zero-shot-report.json"

PROMPTS = {
    "AI": (
        "a mobile screenshot about artificial intelligence, ChatGPT, or machine learning",
        "an AI technology article or assistant app screenshot",
    ),
    "BOOK": (
        "a mobile screenshot about a book, ebook, novel, or reading",
        "a book library or book recommendation screenshot",
    ),
    "FINANCE": (
        "a finance, banking, investment, stock market, or cryptocurrency mobile screenshot",
        "a mobile screenshot about money, exchange rates, or personal finance",
    ),
    "GAME": (
        "a mobile video game screenshot or game advertisement",
        "a gaming app, game score, or game store screenshot",
    ),
    "MOVIE": (
        "a movie poster, film review, cinema, or movie recommendation mobile screenshot",
        "a mobile screenshot about a film",
    ),
    "MUSIC": (
        "a music player, song, album, artist, or concert mobile screenshot",
        "a mobile screenshot about music",
    ),
    "NEWS": (
        "a news article or current events mobile screenshot",
        "a mobile news feed with headlines",
    ),
    "OTHER": (
        "a generic mobile app login, settings, form, menu, tutorial, or utility screen",
        "a mobile interface with no clear content topic",
    ),
    "PLACE": (
        "a map, travel destination, restaurant, hotel, or place recommendation mobile screenshot",
        "a mobile screenshot about a location or somewhere to visit",
    ),
    "POLITICS": (
        "a politics, election, government, parliament, or politician mobile screenshot",
        "a political news mobile screenshot",
    ),
    "PRODUCT": (
        "an online shopping, ecommerce, clothing, electronics, or product mobile screenshot",
        "a product listing or something to buy on a phone",
    ),
    "RECIPE": (
        "a food recipe, cooking instructions, ingredients, meal, or restaurant dish screenshot",
        "a mobile screenshot about food and cooking",
    ),
    "SCIENCE": (
        "a science, astronomy, biology, physics, or research mobile screenshot",
        "a scientific article mobile screenshot",
    ),
    "SERIES": (
        "a television series, streaming show, episode, or season mobile screenshot",
        "a mobile screenshot about a TV show",
    ),
    "SOCIAL": (
        "a social media post, chat, user profile, community, or online forum mobile screenshot",
        "an Instagram, Twitter, Reddit, or messaging screenshot",
    ),
    "SPORT": (
        "a sports, football, basketball, match, athlete, or fitness mobile screenshot",
        "a mobile screenshot about a sports team or competition",
    ),
    "VIDEO": (
        "a video player, short video, YouTube, TikTok, or online video mobile screenshot",
        "a mobile screenshot about watching a video",
    ),
}


def load_rows() -> list[dict[str, str]]:
    with (DATA_ROOT / "manifest.tsv").open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle, delimiter="\t"))


def main() -> None:
    rows = load_rows()
    checkpoint = hf_hub_download("apple/MobileCLIP-S0", "mobileclip_s0.pt")
    model, _, preprocess = mobileclip.create_model_and_transforms(
        "mobileclip_s0",
        pretrained=checkpoint,
    )
    tokenizer = mobileclip.get_tokenizer("mobileclip_s0")
    model.eval()

    classes = sorted(PROMPTS)
    with torch.inference_mode():
        class_vectors = []
        for topic in classes:
            tokens = tokenizer(list(PROMPTS[topic]))
            vectors = model.encode_text(tokens)
            vectors = vectors / vectors.norm(dim=-1, keepdim=True)
            vector = vectors.mean(dim=0)
            class_vectors.append(vector / vector.norm())
        text_vectors = torch.stack(class_vectors)

        predictions: list[str] = []
        confidences: list[float] = []
        batch_size = 16
        for offset in range(0, len(rows), batch_size):
            batch_rows = rows[offset : offset + batch_size]
            tensors = []
            for row in batch_rows:
                image_path = next((DATA_ROOT / "screenshots").rglob(row["file"]))
                with Image.open(image_path) as image:
                    tensors.append(preprocess(image.convert("RGB")))
            image_vectors = model.encode_image(torch.stack(tensors))
            image_vectors = image_vectors / image_vectors.norm(dim=-1, keepdim=True)
            probabilities = (100.0 * image_vectors @ text_vectors.T).softmax(dim=-1)
            values, indices = probabilities.max(dim=1)
            predictions.extend(classes[int(index)] for index in indices)
            confidences.extend(float(value) for value in values)
            print(f"{min(offset + batch_size, len(rows))}/{len(rows)}")

    expected = [row["topic"] for row in rows]
    correct = [gold == guess for gold, guess in zip(expected, predictions)]
    per_topic = {}
    for topic in sorted(set(expected)):
        indices = [index for index, value in enumerate(expected) if value == topic]
        hits = sum(correct[index] for index in indices)
        per_topic[topic] = {
            "correct": hits,
            "total": len(indices),
            "accuracy": hits / len(indices),
        }
    coverage = {}
    for threshold in (0.50, 0.60, 0.70, 0.80, 0.90):
        accepted = [index for index, value in enumerate(confidences) if value >= threshold]
        coverage[f"{threshold:.2f}"] = {
            "accepted": len(accepted),
            "coverage": len(accepted) / len(rows),
            "accuracy": (
                sum(correct[index] for index in accepted) / len(accepted)
                if accepted
                else None
            ),
        }
    confusions = Counter(
        (gold, guess)
        for gold, guess in zip(expected, predictions)
        if gold != guess
    )
    report = {
        "schema": 1,
        "model": "Apple MobileCLIP-S0 zero-shot",
        "examples": len(rows),
        "accuracy": sum(correct) / len(correct),
        "per_topic": per_topic,
        "confidence_policy": coverage,
        "top_confusions": [
            {"expected": gold, "predicted": guess, "count": count}
            for (gold, guess), count in confusions.most_common(15)
        ],
        "predictions": [
            {
                "id": f"native-ui-{row['screen_id']}",
                "expected": gold,
                "predicted": guess,
                "confidence": confidence,
            }
            for row, gold, guess, confidence in zip(
                rows,
                expected,
                predictions,
                confidences,
            )
        ],
    }
    REPORT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"\nreport: {REPORT_PATH}")


if __name__ == "__main__":
    main()
