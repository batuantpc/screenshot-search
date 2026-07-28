"""Kullanici duzeltmelerini EGITIM VERISINE cevirir.

Bu, "kullanimin modeli gercekten gelistirir" halkasinin eksik parcasidir. Uygulama
kullanici bir konuyu duzelttiginde `screenshot_items.user_topic_corrected=1` isaretler ve
`topic` sutununa kullanicinin verdigi dogru etiketi yazar. Bu arac o altin etiketleri
`.eval` formatinda disari yazar; `train-topic-model.py` onlari otomatik okur ve
"corr-" onekinden taniyip yuksek agirlikla egitir.

Neden `user_topic_corrected=1` (correction_events degil): screenshot_items kullanicinin
NIHAI kararini tutar; correction_events ise ara adimlari da iceren denetim gunlugudur.
Egitim icin son dogru etiket yeterli.

Kullanim:
    python tools/export-corrections-training.py            # yerel DB kopyasindan
    python tools/export-corrections-training.py --pull      # once cihazdan cek
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
OUT_PATH = REPO_ROOT / ".private-data" / "eval" / "user-corrections.eval"

VALID_TOPICS = {
    "RECIPE", "MOVIE", "SERIES", "NEWS", "POLITICS", "FINANCE", "PLACE", "PRODUCT",
    "SPORT", "GAME", "AI", "SCIENCE", "BOOK", "MUSIC", "VIDEO", "SOCIAL", "OTHER",
}
# Bir sayfanin icerigini tasimayan yakalamalar egitime girmemeli (bkz. export-corpus-eval).
WALL_PATTERNS = (
    "cloudflare", "gercek kisi oldugunuzu", "verify you are human", "checking your browser",
    "access denied", "403 forbidden", "404 not found", "page not found", "sayfa bulunamadi",
    "enable javascript", "are you a robot", "attention required", "security check",
)
MIN_OCR_CHARS = 40


def normalize(v: str) -> str:
    v = v.lower()
    for s, d in (("ı", "i"), ("ğ", "g"), ("ü", "u"), ("ş", "s"), ("ö", "o"), ("ç", "c")):
        v = v.replace(s, d)
    return re.sub(r"[^a-z0-9]+", " ", v)


def usable(ocr: str) -> bool:
    t = (ocr or "").strip()
    if len(t) < MIN_OCR_CHARS:
        return False
    flat = normalize(t)
    return not any(p in flat for p in WALL_PATTERNS)


def pull_db() -> None:
    script = REPO_ROOT / "tools" / "pull-device-db.py"
    if subprocess.run([sys.executable, str(script)]).returncode != 0:
        sys.exit("DB cekilemedi")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pull", action="store_true")
    args = ap.parse_args()
    if args.pull:
        pull_db()
    if not DB_PATH.exists():
        sys.exit(f"DB yok: {DB_PATH} (--pull ile cekebilirsin)")

    db = sqlite3.connect(DB_PATH)
    db.row_factory = sqlite3.Row
    rows = db.execute(
        "SELECT id, display_name, ocr_text, ocr_layout, image_labels, topic, sensitive "
        "FROM screenshot_items WHERE user_topic_corrected = 1 AND processed = 1"
    ).fetchall()

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    written = 0
    skipped = 0
    with OUT_PATH.open("w", encoding="utf-8") as out:
        out.write("# Kullanici duzeltmeleri - ALTIN etiket, yuksek agirlikli egitim verisi.\n")
        out.write("# Uretici: tools/export-corrections-training.py  |  Git'e GIRMEZ\n\n")
        for r in rows:
            topic = (r["topic"] or "").upper()
            if topic not in VALID_TOPICS:
                continue
            if not usable(r["ocr_text"]):
                skipped += 1
                continue
            out.write(f"=== case corr-{r['id']}\n")
            out.write(f"display: {r['display_name']}\n")
            out.write(f"topic: {topic}\n")
            out.write(f"sensitive: {'true' if r['sensitive'] else 'false'}\n")
            out.write("--- ocr\n")
            out.write((r["ocr_text"] or "").rstrip("\n") + "\n")
            if r["ocr_layout"]:
                out.write("--- layout\n")
                out.write(r["ocr_layout"].rstrip("\n") + "\n")
            if r["image_labels"] and r["image_labels"] != "-":
                out.write("--- labels\n")
                out.write(r["image_labels"].rstrip("\n") + "\n")
            out.write("=== end\n\n")
            written += 1

    if written == 0:
        OUT_PATH.unlink(missing_ok=True)
        print("Henuz kullanilabilir kullanici duzeltmesi yok.")
        print("Uygulamada bir kartin konusunu duzeltince burasi dolar.")
    else:
        print(f"{written} kullanici duzeltmesi -> {OUT_PATH}")
    if skipped:
        print(f"({skipped} duzeltme icerik tasimadigi icin atlandi)")


if __name__ == "__main__":
    main()
