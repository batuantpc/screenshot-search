"""Cihazdaki Screenshot Search veritabanini ceker ve ozel eval setine donusturur.

Kullanim (repo kokunden, telefon USB ile bagliyken):
    python tools/export-device-eval.py

Cikti: .private-data/eval/device-verified.eval  (Git'e GIRMEZ)
Not: Yalnizca debug build'de calisir (run-as icin debuggable gerekir).
"""

from __future__ import annotations

import os
import re
import sqlite3
import subprocess
import sys
from pathlib import Path

PACKAGE = "com.screenshotsearch.app"
REPO_ROOT = Path(__file__).resolve().parent.parent
PRIVATE_DIR = REPO_ROOT / ".private-data"
DB_PATH = PRIVATE_DIR / "device" / "screenshot_search.db"
EVAL_PATH = PRIVATE_DIR / "eval" / "device-verified.eval"
LEGACY_MANIFEST_PATH = REPO_ROOT / "tools" / "corpus-manifest.tsv"
HOLDOUT_MANIFEST_PATH = REPO_ROOT / "tools" / "corpus-holdout-v1.tsv"


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


def export_eval() -> int:
    db = sqlite3.connect(DB_PATH)
    db.row_factory = sqlite3.Row
    rows = db.execute(
        """
        SELECT id, display_name, ocr_text, ocr_layout, image_labels, topic, intent, sensitive
        FROM screenshot_items
        WHERE processed = 1 AND (ocr_text != '' OR image_labels NOT IN ('', '-'))
        ORDER BY captured_at
        """
    ).fetchall()

    legacy_truth = load_manifest_truth(LEGACY_MANIFEST_PATH)
    holdout_truth = load_manifest_truth(HOLDOUT_MANIFEST_PATH)
    truth = {**legacy_truth, **holdout_truth}
    test_ids = load_locked_test_ids()
    EVAL_PATH.parent.mkdir(parents=True, exist_ok=True)
    dev_path = EVAL_PATH.parent / "web-corpus-dev.eval"
    test_path = EVAL_PATH.parent / "web-corpus-test.eval"
    holdout_path = EVAL_PATH.parent / "web-holdout-v1.eval"
    n_personal = n_dev = n_test = n_holdout = 0

    dev = dev_path.open("w", encoding="utf-8")
    test = test_path.open("w", encoding="utf-8")
    holdout = holdout_path.open("w", encoding="utf-8")
    dev.write("# Web korpusu DEV seti - siniflandirici kurallari BUNA gore ayarlanir.\n")
    dev.write("# OCR cihazdan, etiketler manifestten (ground truth). Git'e girmez.\n\n")
    test.write("# Web korpusu KILITLI TEST seti - kurallar buna gore ayarlanmaz, sadece olculur.\n")
    test.write("# Uyeleri tools/corpus-test-ids.txt'te. Dogruluk buradaki sayidir. Git'e girmez.\n\n")
    holdout.write("# Web holdout v1 - bu set ilk baseline alinmadan kurallara gostermek YASAK.\n")
    holdout.write("# Topic/sensitive manifestten; intent bilinmedigi icin yazilmaz. Git'e girmez.\n\n")
    try:
        with EVAL_PATH.open("w", encoding="utf-8") as personal:
            personal.write("# Gercek cihaz eval seti - uygulamada dogrulanmis/duzeltilmis etiketler\n")
            personal.write("# Bu dosya kisisel OCR metni icerir, GIT'E GIRMEZ (.private-data ignore'da).\n\n")
            for row in rows:
                cid = corpus_id_of(row["display_name"], truth)
                if cid:
                    topic, intent, sensitive = truth[cid]
                    if cid in holdout_truth:
                        write_case(holdout, cid, row, topic, intent, sensitive)
                        n_holdout += 1
                    elif cid in test_ids:
                        write_case(test, cid, row, topic, intent, sensitive)
                        n_test += 1
                    else:
                        write_case(dev, cid, row, topic, intent, sensitive)
                        n_dev += 1
                else:
                    write_case(personal, f"device-{row['id']:03d}", row,
                               row["topic"], row["intent"], bool(row["sensitive"]))
                    n_personal += 1
    finally:
        dev.close()
        test.close()
        holdout.close()

    if n_dev or n_test:
        print(f"web korpusu: dev {n_dev} -> {dev_path.name}, test {n_test} -> {test_path.name}")
    else:
        dev_path.unlink(missing_ok=True)
        test_path.unlink(missing_ok=True)
    if n_holdout:
        print(f"web holdout: {n_holdout} -> {holdout_path.name}")
    else:
        holdout_path.unlink(missing_ok=True)
    # Eski birlesik dosya varsa temizle (artik dev/test ayrimi kullaniliyor).
    (EVAL_PATH.parent / "web-corpus.eval").unlink(missing_ok=True)
    return n_personal


def load_manifest_truth(manifest: Path) -> dict:
    """Bir manifestteki kimlikleri ground-truth etiketleriyle dondurur.

    Web korpusu kayitlari cihazda modelin KENDI tahminini tasir; onlari
    device-verified.eval'e yazmak regresyon setini modelin hatalariyla kirletir.
    Bu yuzden korpus kayitlari manifest etiketleriyle ayri dosyaya gider.
    """
    truth = {}
    if not manifest.exists():
        return truth
    for line in manifest.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 5 or parts[0] == "id":
            continue
        truth[parts[0]] = (parts[2], parts[3], parts[4].lower() == "true")
    return truth


def load_locked_test_ids() -> set:
    """Kilitli test setinin korpus kimlikleri. Siniflandirici bunlara gore ayarlanmaz."""
    path = REPO_ROOT / "tools" / "corpus-test-ids.txt"
    if not path.exists():
        return set()
    return {
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    }


def corpus_id_of(display_name: str, truth: dict):
    m = re.match(r"Screenshot_\d{8}_\d{6}_(.+)\.(png|jpg)$", display_name)
    if not m:
        return None
    cid = m.group(1)
    return cid if cid in truth else None


def write_case(out, case_id, row, topic, intent, sensitive) -> None:
    out.write(f"=== case {case_id}\n")
    out.write(f"display: {row['display_name']}\n")
    out.write(f"topic: {topic}\n")
    if intent != "-":
        out.write(f"intent: {intent}\n")
    out.write(f"sensitive: {'true' if sensitive else 'false'}\n")
    out.write("--- ocr\n")
    out.write(row["ocr_text"].rstrip("\n") + "\n")
    if row["ocr_layout"]:
        out.write("--- layout\n")
        out.write(row["ocr_layout"].rstrip("\n") + "\n")
    if row["image_labels"] and row["image_labels"] != "-":
        out.write("--- labels\n")
        out.write(row["image_labels"].rstrip("\n") + "\n")
    out.write("=== end\n\n")


def main() -> None:
    adb = find_adb()
    pull_database(adb)
    count = export_eval()
    print(f"{count} kayit yazildi -> {EVAL_PATH}")
    print("Rapor icin: android/ altinda gradlew.bat :app:testDebugUnitTest "
          '--tests "com.screenshotsearch.app.eval.ClassifierEvalTest" --rerun -i')


if __name__ == "__main__":
    main()
