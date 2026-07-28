"""PC'de tesseract ile OCR yapar; cihaza HIC dokunmadan .eval egitim verisi uretir.

Neden PC-OCR: cihaz OCR'i her veri turunda push+isle+temizle dongusu ve kararsiz USB
demekti. Capraz-OCR dogrulamasi (tools/ dinda olculdu) gosterdi ki tesseract ile egitilen
model cihaz ML Kit metnini AYNI dogrulukla siniflandiriyor (fark -0,3 puan): karakter
n-gram'lari OCR-motoru farkina dayanikli. Boylece sinirsiz veri, cihaz bagimliligi yok.

Girdi : bir manifest (id/url/topic) + o manifest icin cekilmis PNG klasoru
Cikti : .private-data/eval/<name>.eval  (train-topic-model.py otomatik okur)

Kullanim:
    python tools/ocr-corpus-local.py --manifest tools/corpus-x.tsv --dir .private-data/x --name x
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
EVAL_DIR = REPO_ROOT / ".private-data" / "eval"
TESSDATA = os.path.expanduser("~/scoop/apps/tesseract/current/tessdata")


def _find_tesseract() -> str:
    """Windows'ta scoop shim'i .exe uzantili; PATH'te de olabilir."""
    import shutil
    for c in (
        os.environ.get("TESSERACT"),
        os.path.expanduser("~/scoop/shims/tesseract.exe"),
        os.path.expanduser("~/scoop/apps/tesseract/current/tesseract.exe"),
        shutil.which("tesseract"),
    ):
        if c and Path(c).exists():
            return c
    return "tesseract"


TESS = _find_tesseract()

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


def reject(ocr: str) -> str | None:
    t = (ocr or "").strip()
    if len(t) < MIN_OCR_CHARS:
        return f"kisa OCR ({len(t)})"
    flat = normalize(t)
    for p in WALL_PATTERNS:
        if p in flat:
            return f"duvar ({p})"
    return None


def load_manifest(path: Path) -> dict[str, tuple[str, bool]]:
    truth = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("#") or not line.strip():
            continue
        p = line.split("\t")
        if len(p) < 5 or p[0] == "id":
            continue
        truth[p[0]] = (p[2], p[4].lower() == "true")
    return truth


def tess_ocr(png: Path) -> str:
    env = {**os.environ, "TESSDATA_PREFIX": TESSDATA}
    r = subprocess.run(
        [TESS, str(png), "stdout", "-l", "tur+eng"],
        capture_output=True, text=True, encoding="utf-8", env=env,
    )
    return (r.stdout or "").strip()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", type=Path, required=True)
    ap.add_argument("--dir", type=Path, required=True)
    ap.add_argument("--name", required=True)
    args = ap.parse_args()

    if not Path(TESS).exists():
        sys.exit(f"tesseract yok: {TESS}")
    truth = load_manifest(args.manifest)

    png_by_id: dict[str, Path] = {}
    for p in args.dir.glob("*.png"):
        m = re.match(r"Screenshot_\d{8}_\d{6}_(.+)\.png$", p.name)
        if m:
            png_by_id[m.group(1)] = p

    EVAL_DIR.mkdir(parents=True, exist_ok=True)
    out_path = EVAL_DIR / f"{args.name}.eval"
    written = rejected = missing = 0
    total = len(truth)
    with out_path.open("w", encoding="utf-8") as out:
        out.write(f"# {args.name} - PC tesseract OCR (cihaz kullanilmadi). Git'e GIRMEZ.\n\n")
        for i, (cid, (topic, sensitive)) in enumerate(truth.items(), start=1):
            png = png_by_id.get(cid)
            if png is None:
                missing += 1
                continue
            ocr = tess_ocr(png)
            if reject(ocr):
                rejected += 1
                continue
            out.write(f"=== case {cid}\n")
            out.write(f"display: {png.name}\n")
            out.write(f"topic: {topic}\n")
            out.write(f"sensitive: {'true' if sensitive else 'false'}\n")
            out.write("--- ocr\n")
            out.write(ocr + "\n")
            out.write("=== end\n\n")
            written += 1
            if i % 100 == 0:
                print(f"  {i}/{total} (yazilan {written})")

    print(f"{written} case -> {out_path}  (atlanan duvar/kisa: {rejected}, PNG yok: {missing})")


if __name__ == "__main__":
    main()
