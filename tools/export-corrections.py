"""Cihazdaki duzeltme denetim gecmisini cekip ozetler.

Kullanim (repo kokunden, telefon USB ile bagliyken):
    python tools/export-corrections.py

Cikti:
    .private-data/corrections/correction-events.tsv  (ham olaylar, Git'e GIRMEZ)
    konsola ozet: alan bazinda sayim, en cok karisan onceki->yeni ciftleri,
    modelin dusuk guvenle yaptigi hatalar, surum bazinda dagilim.

Not: yalnizca debug build'de calisir (run-as icin debuggable gerekir).
"""

from __future__ import annotations

import os
import sqlite3
import subprocess
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

PACKAGE = "com.screenshotsearch.app"
REPO_ROOT = Path(__file__).resolve().parent.parent
PRIVATE_DIR = REPO_ROOT / ".private-data"
DB_PATH = PRIVATE_DIR / "device" / "screenshot_search.db"
OUT_PATH = PRIVATE_DIR / "corrections" / "correction-events.tsv"


def find_adb() -> str:
    candidates = [
        os.environ.get("ADB"),
        str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe"),
        "adb",
    ]
    for candidate in candidates:
        if not candidate:
            continue
        try:
            subprocess.run([candidate, "version"], capture_output=True, check=True)
            return candidate
        except (OSError, subprocess.CalledProcessError):
            continue
    sys.exit("adb bulunamadi. ADB ortam degiskeniyle yol verin.")


def pull_database(adb: str) -> None:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        [adb, "exec-out", "run-as", PACKAGE, "cat", "databases/screenshot_search.db"],
        capture_output=True,
    )
    if result.returncode != 0 or not result.stdout:
        sys.exit(f"Veritabani cekilemedi: {result.stderr.decode(errors='replace').strip()}")
    DB_PATH.write_bytes(result.stdout)


def export() -> int:
    db = sqlite3.connect(DB_PATH)
    db.row_factory = sqlite3.Row
    # Tablo eski bir surumden gelen DB'de olmayabilir.
    tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    if "correction_events" not in tables:
        sys.exit("correction_events tablosu yok. Cihazda DB v8+ gerekli (bir duzeltme yapin).")

    rows = db.execute(
        """
        SELECT screenshot_id, field, previous_value, new_value,
               previous_confidence, previous_was_user, classifier_version, corrected_at
        FROM correction_events
        ORDER BY corrected_at DESC
        """
    ).fetchall()

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with OUT_PATH.open("w", encoding="utf-8") as out:
        out.write(
            "screenshot_id\tfield\tprevious\tnew\tprev_confidence\t"
            "prev_was_user\tclassifier_version\tcorrected_at_iso\n"
        )
        for r in rows:
            iso = datetime.fromtimestamp(r["corrected_at"] / 1000, tz=timezone.utc).isoformat()
            out.write(
                f"{r['screenshot_id']}\t{r['field']}\t{r['previous_value']}\t{r['new_value']}\t"
                f"{r['previous_confidence']:.2f}\t{r['previous_was_user']}\t"
                f"{r['classifier_version']}\t{iso}\n"
            )

    summarize(rows)
    return len(rows)


def summarize(rows) -> None:
    if not rows:
        print("Henuz duzeltme yok.")
        return

    # Yalnizca modelin tahminini duzeltenler kalite sinyali; kullanicinin kendi
    # duzeltmesini tekrar degistirmesi model hatasi degil.
    model_errors = [r for r in rows if not r["previous_was_user"]]

    print(f"\nToplam duzeltme olayi: {len(rows)}  (modelin hatasi: {len(model_errors)})")

    by_field = Counter(r["field"] for r in model_errors)
    print("\nAlan bazinda model hatasi:")
    for field, count in by_field.most_common():
        print(f"  {field:8} {count}")

    pairs = Counter(
        f"{r['previous_value']} -> {r['new_value']}" for r in model_errors
    )
    print("\nEn cok karisan (model -> kullanici):")
    for pair, count in pairs.most_common(10):
        print(f"  {pair:28} {count}")

    # Modelin YUKSEK guvenle yaptigi hatalar en tehlikelisi: emin ama yanlis.
    confident = [r for r in model_errors if r["previous_confidence"] >= 0.8]
    if confident:
        print(f"\nYuksek guvenli hatalar ({len(confident)} adet, guven >= 0.80):")
        for r in confident[:10]:
            print(
                f"  #{r['screenshot_id']} {r['field']}: "
                f"{r['previous_value']} -> {r['new_value']} (guven {r['previous_confidence']:.2f})"
            )

    by_version = Counter(r["classifier_version"] for r in model_errors)
    print("\nSiniflandirici surumune gore hata:")
    for version, count in sorted(by_version.items()):
        print(f"  v{version}: {count}")

    print(f"\nHam olaylar -> {OUT_PATH}")


def main() -> None:
    adb = find_adb()
    pull_database(adb)
    count = export()
    print(f"\n{count} olay disa aktarildi.")


if __name__ == "__main__":
    main()
