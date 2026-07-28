"""Web korpusu yakalamalarini teknik olarak denetler ve temas sayfalari uretir.

Kullanim:
    python tools/audit-web-corpus.py
    python tools/audit-web-corpus.py \
        --corpus-dir .private-data/holdout-v1 \
        --out-dir .private-data/holdout-v1/audit

Bu arac siniflandirma skoru uretmez. Eksik/bozuk dosya, yanlis boyut, birebir
tekrar ve gorsel olarak denetlenebilir kategori temas sayfalari uretir.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import statistics
from collections import defaultdict
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageStat

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CORPUS = REPO_ROOT / ".private-data" / "corpus"
THUMB_WIDTH = 216
THUMB_HEIGHT = 468
LABEL_HEIGHT = 34
SHEET_COLUMNS = 5


def load_labels(path: Path) -> list[dict[str, str]]:
    lines = [
        line for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    ]
    return list(csv.DictReader(lines, delimiter="\t"))


def visual_stddev(image: Image.Image) -> float:
    gray = image.convert("L")
    gray.thumbnail((90, 195))
    return float(ImageStat.Stat(gray).stddev[0])


def render_sheet(topic: str, rows: list[dict], out_path: Path) -> None:
    sheet_rows = (len(rows) + SHEET_COLUMNS - 1) // SHEET_COLUMNS
    sheet = Image.new(
        "RGB",
        (SHEET_COLUMNS * THUMB_WIDTH, sheet_rows * (THUMB_HEIGHT + LABEL_HEIGHT)),
        "white",
    )
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default(size=16)
    for index, row in enumerate(rows):
        x = (index % SHEET_COLUMNS) * THUMB_WIDTH
        y = (index // SHEET_COLUMNS) * (THUMB_HEIGHT + LABEL_HEIGHT)
        with Image.open(row["path"]) as source:
            thumb = source.convert("RGB")
            thumb.thumbnail((THUMB_WIDTH, THUMB_HEIGHT))
            canvas = Image.new("RGB", (THUMB_WIDTH, THUMB_HEIGHT), "#d7dce3")
            canvas.paste(
                thumb,
                ((THUMB_WIDTH - thumb.width) // 2, (THUMB_HEIGHT - thumb.height) // 2),
            )
        sheet.paste(canvas, (x, y))
        draw.rectangle((x, y + THUMB_HEIGHT, x + THUMB_WIDTH, y + THUMB_HEIGHT + LABEL_HEIGHT),
                       fill="#101722")
        draw.text((x + 7, y + THUMB_HEIGHT + 7), row["id"], fill="white", font=font)
    draw.rectangle((0, 0, sheet.width - 1, sheet.height - 1), outline="#334155", width=2)
    sheet.save(out_path, optimize=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="Web korpusu yakalama kalite denetimi.")
    parser.add_argument("--corpus-dir", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--out-dir", type=Path)
    args = parser.parse_args()

    corpus_dir = args.corpus_dir
    out_dir = args.out_dir or corpus_dir / "audit"
    labels_path = corpus_dir / "labels.tsv"
    if not labels_path.exists():
        raise SystemExit(f"Etiket dosyasi yok: {labels_path}")
    out_dir.mkdir(parents=True, exist_ok=True)

    labels = load_labels(labels_path)
    missing: list[str] = []
    bad_dimensions: list[str] = []
    tiny: list[str] = []
    low_variance: list[str] = []
    hashes: dict[str, list[str]] = defaultdict(list)
    by_topic: dict[str, list[dict]] = defaultdict(list)
    byte_sizes: list[int] = []

    for row in labels:
        path = corpus_dir / row["file"]
        if not path.exists():
            missing.append(row["id"])
            continue
        byte_sizes.append(path.stat().st_size)
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        hashes[digest].append(row["id"])
        with Image.open(path) as image:
            width, height = image.size
            stddev = visual_stddev(image)
        if (width, height) != (1080, 2340):
            bad_dimensions.append(f"{row['id']} ({width}x{height})")
        if path.stat().st_size < 80 * 1024:
            tiny.append(f"{row['id']} ({path.stat().st_size // 1024} KB)")
        if stddev < 12:
            low_variance.append(f"{row['id']} (stddev {stddev:.1f})")
        by_topic[row["topic"]].append({**row, "path": path})

    duplicates = [ids for ids in hashes.values() if len(ids) > 1]
    for topic, rows in sorted(by_topic.items()):
        render_sheet(topic, rows, out_dir / f"{topic.lower()}.png")

    report = [
        "# Web korpusu yakalama denetimi",
        "",
        f"- Etiket: **{len(labels)}**",
        f"- Mevcut PNG: **{len(labels) - len(missing)}**",
        f"- Toplam boyut: **{sum(byte_sizes) / 1024 / 1024:.1f} MB**",
        f"- Medyan dosya boyutu: **{statistics.median(byte_sizes) / 1024:.0f} KB**"
        if byte_sizes else "- Medyan dosya boyutu: -",
        f"- Eksik: **{len(missing)}**",
        f"- Yanlis boyut: **{len(bad_dimensions)}**",
        f"- Birebir tekrar grubu: **{len(duplicates)}**",
        f"- 80 KB alti (gorsel kontrol adayi): **{len(tiny)}**",
        f"- Dusuk gorsel varyans (gorsel kontrol adayi): **{len(low_variance)}**",
        "",
        "## Kontrol adaylari",
        "",
        f"- Eksik: {', '.join(missing) or '-'}",
        f"- Yanlis boyut: {', '.join(bad_dimensions) or '-'}",
        f"- Birebir tekrar: {'; '.join(', '.join(ids) for ids in duplicates) or '-'}",
        f"- Kucuk dosya: {', '.join(tiny) or '-'}",
        f"- Dusuk varyans: {', '.join(low_variance) or '-'}",
        "",
    ]
    report_path = out_dir / "capture-audit.md"
    report_path.write_text("\n".join(report), encoding="utf-8", newline="\n")
    print("\n".join(report[:10]))
    print(f"\nrapor: {report_path}")
    print(f"temas sayfasi: {len(by_topic)}")


if __name__ == "__main__":
    main()
