"""Web holdout v1 icin KOR baseline: cihazin gercek tahmini vs manifest ground-truth.

Neden bu arac (score-web-corpus.py degil):
export-device-eval.py holdout .eval dosyasina cihaz tahminini DEGIL manifest
ground-truth'unu yazar (Kotlin re-run testi icin). Bu yuzden score-web-corpus.py
holdout eval'de topic'i ground-truth ile ground-truth kiyaslar ve yanlislikla %100
uretir. Gercek kor baseline, cihazin DB'de sakladigi tahmindir (screenshot_items.topic).

Girdi : .private-data/device/memento.db  (export-device-eval.py cekmis olmali)
        tools/corpus-holdout-v1.tsv        (ground truth: topic + sensitive)
Cikti : konsol + .private-data/holdout-v1/blind-baseline.md   (Git'e GIRMEZ)

Siniflandiriciya DOKUNMAZ; yalnizca okur ve raporlar.
"""

from __future__ import annotations

import re
import sqlite3
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DB_PATH = REPO_ROOT / ".private-data" / "device" / "memento.db"
MANIFEST_PATH = REPO_ROOT / "tools" / "corpus-holdout-v1.tsv"
REPORT_PATH = REPO_ROOT / ".private-data" / "holdout-v1" / "blind-baseline.md"

TOPICS = (
    "RECIPE", "MOVIE", "SERIES", "NEWS", "POLITICS", "FINANCE", "PLACE",
    "PRODUCT", "SPORT", "GAME", "AI", "SCIENCE", "BOOK", "MUSIC", "VIDEO", "SOCIAL",
    "OTHER",
)

# Checkpoint'te ozellikle incelenecek sinir bolgeleri.
WATCH_PAIRS = (
    ("NEWS", "POLITICS"), ("POLITICS", "NEWS"),
    ("NEWS", "FINANCE"), ("FINANCE", "NEWS"),
    ("POLITICS", "FINANCE"), ("FINANCE", "POLITICS"),
    ("MOVIE", "SERIES"), ("SERIES", "MOVIE"),
    ("MOVIE", "BOOK"), ("BOOK", "MOVIE"),
    ("PRODUCT", "GAME"), ("GAME", "PRODUCT"),
)


def load_truth(path: Path) -> dict[str, dict[str, str]]:
    truth: dict[str, dict[str, str]] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 5 or parts[0] == "id":
            continue
        truth[parts[0]] = {"topic": parts[2], "sensitive": parts[4].lower() == "true"}
    return truth


def id_from_display(display: str, known: set[str]) -> str | None:
    stem = re.sub(r"\.(png|jpg|jpeg|webp)$", "", display, flags=re.IGNORECASE)
    m = re.match(r"Screenshot_\d{8}_\d{6}_(.+)$", stem)
    if m and m.group(1) in known:
        return m.group(1)
    for cand in sorted(known, key=len, reverse=True):
        if stem.endswith(cand):
            return cand
    return None


def main() -> None:
    if not DB_PATH.exists():
        sys.exit(f"DB yok: {DB_PATH}. Once export-device-eval.py calistir.")
    truth = load_truth(MANIFEST_PATH)
    known = set(truth)

    db = sqlite3.connect(DB_PATH)
    db.row_factory = sqlite3.Row
    rows = db.execute(
        "SELECT display_name, topic, sensitive, ocr_text, processed "
        "FROM screenshot_items WHERE processed = 1"
    ).fetchall()

    # id -> (predicted_topic, predicted_sensitive, ocr_len)
    pred: dict[str, dict] = {}
    for row in rows:
        cid = id_from_display(row["display_name"] or "", known)
        if cid is None or cid in pred:
            continue
        pred[cid] = {
            "topic": (row["topic"] or "").upper(),
            "sensitive": bool(row["sensitive"]),
            "ocr_len": len((row["ocr_text"] or "").strip()),
        }

    matched = [(cid, truth[cid], pred[cid]) for cid in known if cid in pred]
    missing = sorted(known - set(pred))
    total = len(matched)
    if total == 0:
        sys.exit("Eslesen holdout kaydi yok - cihaz islemesi bitti mi?")

    topic_hits = sum(1 for _, t, p in matched if t["topic"] == p["topic"])
    matrix: dict[str, Counter] = defaultdict(Counter)
    pairs: Counter = Counter()
    for _, t, p in matched:
        matrix[t["topic"]][p["topic"] or "-"] += 1
        if t["topic"] != p["topic"]:
            pairs[(t["topic"], p["topic"] or "-")] += 1
    false_alarms = [cid for cid, t, p in matched if not t["sensitive"] and p["sensitive"]]
    empty_ocr = [cid for cid, _, p in matched if p["ocr_len"] < 20]

    # -------- konsol --------
    print(f"eslesen holdout: {total}/{len(known)}  (cihazda yok: {len(missing)})")
    print(f"TOPIC dogruluk : {topic_hits}/{total} = {100.0*topic_hits/total:.1f}%")
    print("\ntopic bazinda:")
    for topic in TOPICS:
        r = matrix.get(topic)
        if not r:
            continue
        n = sum(r.values())
        c = r.get(topic, 0)
        worst = ", ".join(f"{k} x{v}" for k, v in
                          Counter({k: v for k, v in r.items() if k != topic}).most_common(2))
        print(f"  {topic:8s} {c:2d}/{n:2d} = {100.0*c/n:5.1f}%" + (f"   -> {worst}" if worst else ""))
    print(f"\nsensitive yanlis alarm: {len(false_alarms)}"
          + (f" ({', '.join(false_alarms[:10])})" if false_alarms else ""))
    print(f"bos/kisa OCR          : {len(empty_ocr)}"
          + (f" ({', '.join(empty_ocr[:10])})" if empty_ocr else ""))

    # -------- rapor --------
    L: list[str] = []
    a = L.append
    a("# Project Memento — Web Holdout v1 Kör Baseline")
    a("")
    a(f"Tarih: {datetime.now(timezone.utc).astimezone().strftime('%Y-%m-%d %H:%M %z')}")
    a("")
    a("Bu rapor sınıflandırıcıya **dokunulmadan** alınmıştır. Kaynak: cihazın kendi")
    a("tahmini (`screenshot_items.topic`). Ground truth: `tools/corpus-holdout-v1.tsv`.")
    a("Intent ölçülmedi (holdout'ta ground-truth intent yok).")
    a("")
    a("## Genel")
    a("")
    a("| Metrik | Doğru | Toplam | Oran |")
    a("| --- | ---: | ---: | ---: |")
    a(f"| Topic | {topic_hits} | {total} | {100.0*topic_hits/total:.1f}% |")
    a(f"| Cihazda bulunamayan | - | {len(missing)} | - |")
    a("")
    a("## Topic bazında")
    a("")
    a("| Gerçek topic | N | Doğru | Oran | En çok karıştığı |")
    a("| --- | ---: | ---: | ---: | --- |")
    for topic in TOPICS:
        r = matrix.get(topic)
        if not r:
            continue
        n = sum(r.values())
        c = r.get(topic, 0)
        wrong = Counter({k: v for k, v in r.items() if k != topic})
        worst = ", ".join(f"{k} x{v}" for k, v in wrong.most_common(3)) or "-"
        a(f"| {topic} | {n} | {c} | {100.0*c/n:.0f}% | {worst} |")
    a("")
    a("## En çok karışan çiftler")
    a("")
    if pairs:
        a("| Gerçek → Tahmin | Adet |")
        a("| --- | ---: |")
        for (real, guess), cnt in pairs.most_common(15):
            a(f"| {real} → {guess} | {cnt} |")
    else:
        a("Karışıklık yok.")
    a("")
    a("## Sınır bölgeleri (checkpoint özel ilgi)")
    a("")
    a("| Çift | Adet |")
    a("| --- | ---: |")
    for pr in WATCH_PAIRS:
        cnt = pairs.get(pr, 0)
        if cnt:
            a(f"| {pr[0]} → {pr[1]} | {cnt} |")
    if not any(pairs.get(pr, 0) for pr in WATCH_PAIRS):
        a("| (sınır çiftlerinde karışıklık yok) | 0 |")
    a("")
    a("## Uyarılar")
    a("")
    a(f"- Yanlış 'sensitive' alarmı: {len(false_alarms)}"
      + (f" ({', '.join(false_alarms[:12])})" if false_alarms else ""))
    a(f"- OCR metni boş/çok kısa: {len(empty_ocr)}"
      + (f" ({', '.join(empty_ocr[:12])})" if empty_ocr else ""))
    if missing:
        a(f"- Cihazda bulunamayan id'ler: {', '.join(missing[:20])}"
          + (" ..." if len(missing) > 20 else ""))
    a("")
    a("## Yanlış sınıflandırılanlar (triage için)")
    a("")
    a("| id | gerçek topic | tahmin | OCR uzunluğu |")
    a("| --- | --- | --- | ---: |")
    for cid, t, p in matched:
        if t["topic"] != p["topic"]:
            a(f"| {cid} | {t['topic']} | {p['topic'] or '-'} | {p['ocr_len']} |")
    a("")

    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text("\n".join(L) + "\n", encoding="utf-8", newline="\n")
    print(f"\nrapor -> {REPORT_PATH}")


if __name__ == "__main__":
    main()
