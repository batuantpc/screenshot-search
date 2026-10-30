"""Korpus -> cihaz -> OCR -> eval -> egitim hattini tek komutta yurutur.

Neden: USB baglantisi kararsiz. Her adimi elle yurutmek yerine hat tek seferde kosar;
telefonun bagli kalmasi gereken sure minimuma iner. Her adim baglantiyi dogrular ve
koparsa temiz hata verir (yarim veriyle devam etmez).

Kullanim:
    python tools/run-training-pipeline.py --corpus tools/corpus-real-v1.tsv --name real-v1 \
        --out-dir .private-data/real-v1
    python tools/run-training-pipeline.py --skip-push --name real-v1   # zaten cihazdaysa
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "com.screenshotsearch.app"
PREFS = "shared_prefs/screenshot_search_sync.xml"


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


def require_device(adb: str) -> None:
    out = subprocess.run([adb, "devices"], capture_output=True, text=True).stdout
    ready = [l for l in out.splitlines()[1:] if l.strip() and l.split()[-1] == "device"]
    if not ready:
        sys.exit(
            "Cihaz hazir degil.\n"
            "  - kabloyu (veri destekli olani) tak\n"
            "  - telefonda USB modunu 'Dosya aktarimi' yap\n"
            "  - USB hata ayiklama izni ver\n"
            f"  mevcut: {out.strip()}"
        )


def run(cmd: list[str], desc: str) -> None:
    print(f"\n=== {desc} ===")
    if subprocess.run(cmd).returncode != 0:
        sys.exit(f"basarisiz: {desc}")


def force_full_scan(adb: str) -> None:
    """last_scan=0 yaparak tam taramayi tetikler.

    Artimli tarama yalniz `DATE_ADDED >= son_tarama - 10sn` araligina bakiyor; yeni
    push'lanan dosyalar bu pencerenin gerisinde kalip hic gorulmuyordu.
    """
    subprocess.run([adb, "shell", "am", "force-stop", PACKAGE], capture_output=True)
    time.sleep(1)
    current = subprocess.run(
        [adb, "exec-out", "run-as", PACKAGE, "cat", PREFS], capture_output=True, text=True
    ).stdout
    version = "26"
    for line in current.splitlines():
        if "derived_metadata_version" in line and 'value="' in line:
            version = line.split('value="')[1].split('"')[0]
    xml = (
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
        '    <long name="last_scan_seconds" value="0" />\n'
        '    <string name="last_access_level">FULL</string>\n'
        f'    <int name="derived_metadata_version" value="{version}" />\n</map>\n'
    )
    subprocess.run(
        [adb, "shell", "run-as", PACKAGE, "sh", "-c", f"cat > {PREFS}"],
        input=xml.encode(), capture_output=True,
    )
    subprocess.run(
        [adb, "shell", "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1"],
        capture_output=True,
    )


def wait_for_ocr(adb: str, timeout_s: int = 900) -> None:
    """Islenmemis kayit kalmayana kadar bekler."""
    print("\n=== OCR bekleniyor ===")
    pull = REPO_ROOT / "tools" / "pull-device-db.py"
    deadline = time.time() + timeout_s
    last = ""
    while time.time() < deadline:
        time.sleep(20)
        subprocess.run([sys.executable, str(pull)], capture_output=True)
        result = subprocess.run(
            [sys.executable, str(pull), "--status"], capture_output=True, text=True
        )
        status = result.stdout.strip()
        if status and status != last:
            print("  " + status)
            last = status
        if status.startswith("islenen ") and "/" in status:
            done, total = status.replace("islenen ", "").split("/")
            if done == total and int(total) > 0:
                print("  tamamlandi")
                return
    print("  UYARI: zaman asimi, mevcut veriyle devam ediliyor")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", type=Path, help="manifest (push icin)")
    ap.add_argument("--out-dir", type=Path, help="cekilen PNG klasoru")
    ap.add_argument("--name", required=True, help="eval cikti adi")
    ap.add_argument("--skip-push", action="store_true")
    ap.add_argument("--skip-train", action="store_true")
    args = ap.parse_args()

    adb = find_adb()
    require_device(adb)

    if not args.skip_push:
        if not args.corpus or not args.out_dir:
            sys.exit("--corpus ve --out-dir gerekli (veya --skip-push)")
        run([sys.executable, str(REPO_ROOT / "tools" / "capture-web-corpus.py"),
             "--manifest", str(args.corpus), "--out-dir", str(args.out_dir), "--push"],
            "korpus cihaza aktariliyor")
        force_full_scan(adb)
        wait_for_ocr(adb)
    else:
        run([sys.executable, str(REPO_ROOT / "tools" / "pull-device-db.py")], "cihaz DB'si cekiliyor")

    if args.corpus:
        run([sys.executable, str(REPO_ROOT / "tools" / "export-corpus-eval.py"),
             "--manifest", str(args.corpus), "--name", args.name],
            "eval uretiliyor")

    if not args.skip_train:
        run([sys.executable, str(REPO_ROOT / "tools" / "train-topic-model.py")],
            "model egitiliyor ve olculuyor")


if __name__ == "__main__":
    main()
