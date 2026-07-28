"""Cihaz veritabanini GUVENLI cekar: bozuk cekme iyi kopyayi ezmez.

Neden ayri arac: USB baglantisi koptugunda `adb exec-out ... > memento.db` yonlendirmesi
dosyayi 0 byte'a indiriyor ve o ana kadarki iyi kopya kayboluyor. Bu arac once gecici
dosyaya ceker, SQLite olarak acilip beklenen tabloyu icerdigini dogrular, ancak ondan
sonra hedefin uzerine yazar.

Kullanim:
    python tools/pull-device-db.py            # ceker ve dogrular
    python tools/pull-device-db.py --status   # sadece islenme durumunu yazar
"""

from __future__ import annotations

import argparse
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DB_PATH = REPO_ROOT / ".private-data" / "device" / "memento.db"
PACKAGE = "com.projectmemento.app"


def find_adb() -> str:
    for cand in (os.environ.get("ADB"),
                 str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe"),
                 "adb"):
        if not cand:
            continue
        try:
            subprocess.run([cand, "version"], capture_output=True, check=True)
            return cand
        except (OSError, subprocess.CalledProcessError):
            continue
    sys.exit("adb bulunamadi.")


def device_ready(adb: str) -> bool:
    out = subprocess.run([adb, "devices"], capture_output=True, text=True).stdout
    return any(line.split()[-1] == "device" for line in out.splitlines()[1:] if line.strip())


def pull(adb: str) -> bool:
    """Basarili ve dogrulanmis bir cekme yapildiysa True."""
    if not device_ready(adb):
        print("cihaz hazir degil (offline/unauthorized/yok) - mevcut kopya korundu")
        return False

    result = subprocess.run(
        [adb, "exec-out", "run-as", PACKAGE, "cat", "databases/memento.db"],
        capture_output=True,
    )
    if result.returncode != 0 or len(result.stdout) < 4096:
        print(f"cekme basarisiz ({len(result.stdout)} byte) - mevcut kopya korundu")
        return False

    with tempfile.NamedTemporaryFile(delete=False, suffix=".db") as tmp:
        tmp.write(result.stdout)
        tmp_path = Path(tmp.name)
    try:
        db = sqlite3.connect(tmp_path)
        count = db.execute("SELECT COUNT(*) FROM screenshot_items").fetchone()[0]
        db.close()
    except Exception as exc:
        tmp_path.unlink(missing_ok=True)
        print(f"cekilen dosya gecerli DB degil ({exc}) - mevcut kopya korundu")
        return False

    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(tmp_path), DB_PATH)
    print(f"tamam: {count} kayit -> {DB_PATH}")
    return True


def status() -> None:
    if not DB_PATH.exists():
        sys.exit("yerel DB kopyasi yok")
    db = sqlite3.connect(DB_PATH)
    total = db.execute("SELECT COUNT(*) FROM screenshot_items").fetchone()[0]
    done = db.execute("SELECT COUNT(*) FROM screenshot_items WHERE processed=1").fetchone()[0]
    print(f"islenen {done}/{total}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--status", action="store_true")
    args = ap.parse_args()
    if args.status:
        status()
        return
    if pull(find_adb()):
        status()


if __name__ == "__main__":
    main()
