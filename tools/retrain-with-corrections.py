"""Kullanici duzeltmelerini toplayip modeli yeniden egitir (tek komut).

Halkanin son adimi: cihazdan duzeltmeleri cek -> egitim verisine ekle -> yeniden egit
ve olc -> yeni model dosyasini uret. Sonra APK'yi yeniden derleyip kurmak yeterli;
`DERIVED_METADATA_VERSION` artirilinca cihaz kutuphaneyi yeni modelle yeniden siniflar.

Kullanim:
    python tools/retrain-with-corrections.py            # cihazdan cek + yeniden egit + export
    python tools/retrain-with-corrections.py --no-pull   # yerel DB kopyasini kullan
    python tools/retrain-with-corrections.py --dry        # export etmeden sadece olc
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
T = REPO_ROOT / "tools"


def run(cmd: list[str], desc: str) -> None:
    print(f"\n=== {desc} ===")
    if subprocess.run(cmd).returncode != 0:
        sys.exit(f"basarisiz: {desc}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-pull", action="store_true", help="cihazdan cekme, yerel DB kullan")
    ap.add_argument("--dry", action="store_true", help="model export etme, sadece olc")
    args = ap.parse_args()

    py = sys.executable
    export_cmd = [py, str(T / "export-corrections-training.py")]
    if not args.no_pull:
        export_cmd.append("--pull")
    run(export_cmd, "kullanici duzeltmeleri egitim verisine cevriliyor")

    train_cmd = [py, str(T / "train-topic-model.py")]
    if not args.dry:
        train_cmd.append("--export")
    run(train_cmd, "model yeniden egitiliyor ve olculuyor")

    if not args.dry:
        print("\nSonraki: APK'yi yeniden derle+kur, DERIVED_METADATA_VERSION'i artir ki")
        print("cihaz kutuphaneyi yeni modelle yeniden siniflasin.")


if __name__ == "__main__":
    main()
