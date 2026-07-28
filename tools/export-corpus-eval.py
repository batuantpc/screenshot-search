"""Herhangi bir korpus manifestini cihaz DB'sindeki OCR ile birlestirip .eval uretir.

`export-en-eval.py` tek bir manifeste bagliydi; her yeni korpus icin yeni arac yazmak
yerine bu genel surum kullanilir. Uretilen .eval dosyalari hem JVM `ClassifierEvalTest`
hem de `train-topic-model.py` tarafindan otomatik okunur.

Kullanim:
    python tools/export-corpus-eval.py --manifest tools/corpus-real-v1.tsv --name real-v1
    python tools/export-corpus-eval.py --manifest tools/corpus-wiki-batch1.tsv --name wiki-v1
    python tools/export-corpus-eval.py --manifest tools/corpus-real-v1.tsv --name real-v1 --pull
"""

from __future__ import annotations

import argparse
import re
import sqlite3
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DB_PATH = REPO_ROOT / ".private-data" / "device" / "memento.db"
EVAL_DIR = REPO_ROOT / ".private-data" / "eval"


def load_truth(path: Path) -> dict[str, tuple[str, bool]]:
    truth: dict[str, tuple[str, bool]] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 5 or parts[0] == "id":
            continue
        truth[parts[0]] = (parts[2], parts[4].lower() == "true")
    return truth


def id_of(display: str, known: set[str]) -> str | None:
    match = re.match(r"Screenshot_\d{8}_\d{6}_(.+)\.(png|jpg)$", display or "")
    return match.group(1) if match and match.group(1) in known else None


# Bot dogrulama / hata / login duvarlari. Bunlar manifestteki konuyla ETIKETLENIR ama
# icerik tasimaz; egitime girerlerse modele "Cloudflare sayfasi = Tarif" gibi zehirli
# bir esleme ogretirler. Gercek bir yakalama hatasidir, sinif ornegi degildir.
WALL_PATTERNS = (
    "cloudflare",
    "gercek kisi oldugunuzu",
    "verify you are human",
    "verifying you are human",
    "checking your browser",
    "are you a robot",
    "access denied",
    "403 forbidden",
    "404 not found",
    "page not found",
    "sayfa bulunamadi",
    "enable javascript",
    "javascript is disabled",
    "unusual traffic",
    "rate limited",
    "security check",
    "attention required",
)
MIN_OCR_CHARS = 60


def normalize(value: str) -> str:
    lowered = value.lower()
    for src, dst in (("ı", "i"), ("ğ", "g"), ("ü", "u"), ("ş", "s"), ("ö", "o"), ("ç", "c")):
        lowered = lowered.replace(src, dst)
    return re.sub(r"[^a-z0-9]+", " ", lowered)


def reject_reason(ocr_text: str) -> str | None:
    """Egitime alinmamasi gereken yakalama icin sebep dondurur, temizse None."""
    text = (ocr_text or "").strip()
    if len(text) < MIN_OCR_CHARS:
        return f"cok kisa OCR ({len(text)} karakter)"
    flat = normalize(text)
    for pattern in WALL_PATTERNS:
        if pattern in flat:
            return f"duvar/hata sayfasi ('{pattern}')"
    return None


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", type=Path, required=True)
    ap.add_argument("--name", required=True, help="cikti adi: .private-data/eval/<name>.eval")
    ap.add_argument("--pull", action="store_true", help="once cihaz DB'sini guvenli cek")
    args = ap.parse_args()

    if args.pull:
        script = REPO_ROOT / "tools" / "pull-device-db.py"
        if subprocess.run([sys.executable, str(script)]).returncode != 0:
            sys.exit("DB cekilemedi")
    if not DB_PATH.exists():
        sys.exit(f"DB yok: {DB_PATH}")

    truth = load_truth(args.manifest)
    known = set(truth)
    db = sqlite3.connect(DB_PATH)
    db.row_factory = sqlite3.Row
    rows = db.execute(
        "SELECT display_name, ocr_text, ocr_layout, image_labels "
        "FROM screenshot_items WHERE processed = 1 ORDER BY captured_at"
    ).fetchall()

    EVAL_DIR.mkdir(parents=True, exist_ok=True)
    out_path = EVAL_DIR / f"{args.name}.eval"
    written: set[str] = set()
    rejected: list[tuple[str, str]] = []
    with out_path.open("w", encoding="utf-8") as out:
        out.write(f"# {args.name} - manifest: {args.manifest.name}\n")
        out.write("# Ground truth manifestten, OCR cihazdan. Git'e GIRMEZ.\n\n")
        for row in rows:
            cid = id_of(row["display_name"], known)
            if cid is None or cid in written:
                continue
            reason = reject_reason(row["ocr_text"])
            if reason:
                rejected.append((cid, reason))
                continue
            written.add(cid)
            topic, sensitive = truth[cid]
            out.write(f"=== case {cid}\n")
            out.write(f"display: {row['display_name']}\n")
            out.write(f"topic: {topic}\n")
            out.write(f"sensitive: {'true' if sensitive else 'false'}\n")
            out.write("--- ocr\n")
            out.write((row["ocr_text"] or "").rstrip("\n") + "\n")
            if row["ocr_layout"]:
                out.write("--- layout\n")
                out.write(row["ocr_layout"].rstrip("\n") + "\n")
            if row["image_labels"] and row["image_labels"] != "-":
                out.write("--- labels\n")
                out.write(row["image_labels"].rstrip("\n") + "\n")
            out.write("=== end\n\n")

    missing = sorted(known - written - {cid for cid, _ in rejected})
    print(f"{len(written)} case -> {out_path}")
    if rejected:
        print(f"egitime ALINMAYAN yakalama ({len(rejected)}) - duvar/hata sayfasi:")
        for cid, reason in rejected[:20]:
            print(f"  {cid}: {reason}")
    if missing:
        print(f"cihazda bulunamayan ({len(missing)}): {', '.join(missing[:15])}"
              + (" ..." if len(missing) > 15 else ""))


if __name__ == "__main__":
    main()
