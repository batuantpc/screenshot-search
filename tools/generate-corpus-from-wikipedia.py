"""Wikipedia kategori API'sinden dengeli, etiketli korpus manifesti uretir.

Neden: siniflandiriciyi elle keyword yazarak degil VERIYLE egitmek istiyoruz. 1700+ URL'yi
elle yazmak mantiksiz; Wikipedia'nin kategori agaci zaten konuya gore etiketlenmis devasa
bir havuz. TR + EN karisik cekilir, boylece model iki dili de ogrenir.

Cikti manifesti mevcut `capture-web-corpus.py` ile birebir uyumludur.

Kullanim:
    python tools/generate-corpus-from-wikipedia.py --per-topic 120
    python tools/generate-corpus-from-wikipedia.py --per-topic 120 --out tools/corpus-wiki-v1.tsv
"""

from __future__ import annotations

import argparse
import json
import random
import re
import sys
import time
import urllib.parse
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT = REPO_ROOT / "tools" / "corpus-wiki-v1.tsv"
EXISTING_MANIFESTS = [
    REPO_ROOT / "tools" / "corpus-manifest.tsv",
    REPO_ROOT / "tools" / "corpus-holdout-v1.tsv",
    REPO_ROOT / "tools" / "corpus-en-v1.tsv",
]

UA = "ScreenshotSearchCorpusBot/1.0 (personal research project; Python urllib)"
# API'yi yormamak icin her istek arasi bekleme (anonim kullanimda 429 yiyoruz).
REQUEST_DELAY = 1.2

# topic -> [(lang, kategori adi), ...]
# Kategoriler bilerek genis ve "yaprak"a yakin secildi; cok ust kategoriler alakasiz
# makale getirir. Her topic icin TR ve EN karisik.
CATEGORIES: dict[str, list[tuple[str, str]]] = {
    "RECIPE": [
        ("tr", "Türk mutfağı"), ("tr", "Tatlılar"), ("tr", "Çorbalar"),
        ("en", "Turkish cuisine"), ("en", "Desserts"), ("en", "Noodle dishes"),
        ("en", "Breads"), ("en", "Stews"),
    ],
    "MOVIE": [
        ("tr", "Türk filmleri"), ("en", "2020s American drama films"),
        ("en", "American science fiction films"), ("en", "Films directed by Christopher Nolan"),
        ("en", "Academy Award for Best Picture winners"),
    ],
    "SERIES": [
        ("tr", "Türk dizileri"), ("en", "American drama television series"),
        ("en", "Netflix original programming"), ("en", "HBO original programming"),
        ("en", "British television sitcoms"),
    ],
    "NEWS": [
        ("en", "Newspapers published in the United States"), ("tr", "Türkiye'deki gazeteler"),
        ("en", "News agencies"), ("en", "Television news channels"),
    ],
    "POLITICS": [
        ("tr", "Türkiye'deki siyasi partiler"), ("en", "Political parties in the United States"),
        ("en", "Legislatures"), ("en", "Heads of government"),
        ("en", "International organizations"),
    ],
    "FINANCE": [
        ("en", "Central banks"), ("en", "Stock exchanges"), ("tr", "Ekonomi"),
        ("en", "Financial markets"), ("en", "Cryptocurrencies"), ("en", "Macroeconomics"),
    ],
    "PLACE": [
        ("tr", "Türkiye'nin ilçeleri"), ("en", "World Heritage Sites"),
        ("en", "National parks"), ("en", "Capitals in Europe"), ("en", "Museums"),
    ],
    "PRODUCT": [
        ("en", "Sneakers"), ("en", "Smartphones"), ("en", "Home appliances"),
        ("en", "Consumer electronics brands"), ("en", "Automobile models"),
        ("en", "Toys"),
    ],
    "SPORT": [
        ("tr", "Türk futbolcular"), ("en", "Association football clubs"),
        ("en", "Basketball competitions"), ("en", "Tennis tournaments"),
        ("en", "Olympic sports"), ("en", "Motorsport"),
    ],
    "GAME": [
        ("en", "Video games"), ("en", "Role-playing video games"),
        ("en", "Video game franchises"), ("tr", "Video oyunları"),
        ("en", "Nintendo games"),
    ],
    "AI": [
        ("en", "Artificial intelligence"), ("en", "Machine learning"),
        ("en", "Neural networks"), ("en", "Large language models"),
        ("en", "Natural language processing"),
    ],
    "SCIENCE": [
        ("en", "Physics"), ("en", "Chemistry"), ("en", "Molecular biology"),
        ("en", "Astronomy"), ("en", "Geology"), ("en", "Genetics"),
        ("tr", "Fizik"), ("tr", "Biyoloji"), ("tr", "Astronomi"),
    ],
    "BOOK": [
        ("en", "Novels"), ("en", "20th-century American novels"),
        ("tr", "Türk romanları"), ("en", "Science fiction novels"),
        ("en", "Nobel Prize in Literature laureates"),
    ],
    "MUSIC": [
        ("en", "Musical groups"), ("en", "Rock albums"), ("tr", "Türk müzik grupları"),
        ("en", "Hip hop albums"), ("en", "Record labels"),
    ],
    "VIDEO": [
        ("en", "Video hosting"), ("en", "YouTube"), ("en", "Streaming media"),
        ("en", "Web series"),
    ],
    "SOCIAL": [
        ("en", "Social networking services"), ("en", "Internet forums"),
        ("en", "Online communities"), ("en", "Microblogging"),
    ],
    "OTHER": [
        ("en", "Units of measurement"), ("en", "Calendars"), ("en", "File formats"),
        ("en", "Typography"), ("tr", "Ölçü birimleri"),
    ],
}


def api(lang: str, params: dict, retries: int = 5) -> dict:
    """Wikipedia API cagrisi. 429/5xx durumunda ustel geri cekilme ile yeniden dener.

    Anonim kullanimda API agresif istegi 429 ile reddediyor; her cagri arasinda
    kisa bir bekleme ve hata halinde artan bekleme sart.
    """
    params = {**params, "format": "json", "formatversion": "2"}
    url = f"https://{lang}.wikipedia.org/w/api.php?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    delay = 2.0
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                data = json.loads(response.read().decode("utf-8"))
            time.sleep(REQUEST_DELAY)
            return data
        except urllib.error.HTTPError as exc:
            if exc.code in (429, 503, 502) and attempt < retries - 1:
                time.sleep(delay)
                delay *= 2
                continue
            raise
        except Exception:
            if attempt < retries - 1:
                time.sleep(delay)
                delay *= 2
                continue
            raise
    raise RuntimeError("erisilemedi")


def _prefix(lang: str) -> str:
    return "Category:" if lang == "en" else "Kategori:"


def _members(lang: str, category: str, kind: str, limit: int) -> list[str]:
    """Tek bir kategorinin dogrudan uyelerini dondurur (kind: 'page' | 'subcat')."""
    titles: list[str] = []
    cont: str | None = None
    while len(titles) < limit:
        params = {
            "action": "query",
            "list": "categorymembers",
            "cmtitle": _prefix(lang) + category,
            "cmlimit": "500",
            "cmnamespace": "0" if kind == "page" else "14",
            "cmtype": kind,
        }
        if cont:
            params["cmcontinue"] = cont
        try:
            data = api(lang, params)
        except Exception as exc:  # ag hatasi tur bitirmesin
            print(f"    uyari: {lang}:{category} -> {exc}")
            break
        titles.extend(m["title"] for m in data.get("query", {}).get("categorymembers", []))
        cont = data.get("continue", {}).get("cmcontinue")
        if not cont:
            break
    return titles


def category_members(lang: str, category: str, want: int) -> list[str]:
    """Kategorideki makaleleri dondurur; yetmezse BIR seviye alt kategoriye iner.

    Wikipedia'da "Novels", "Association football clubs" gibi ust kategoriler cogunlukla
    dogrudan makale degil ALT KATEGORI barindirir. Alt kategori acilmazsa Spor/Film gibi
    sinifar 12-15 ornekte kaliyordu; dengeli korpus icin bu sart.
    """
    titles = _members(lang, category, "page", want * 3)
    if len(titles) >= want:
        return titles[: want * 3]

    subcats = _members(lang, category, "subcat", 40)
    for sub in subcats:
        if len(titles) >= want * 3:
            break
        name = sub.split(":", 1)[1] if ":" in sub else sub
        titles.extend(_members(lang, name, "page", want))
    return titles[: want * 3]


def to_url(lang: str, title: str) -> str:
    return f"https://{lang}.wikipedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_"))


def slug(value: str) -> str:
    value = value.lower()
    replacements = {"ı": "i", "ğ": "g", "ü": "u", "ş": "s", "ö": "o", "ç": "c", "â": "a"}
    for src, dst in replacements.items():
        value = value.replace(src, dst)
    value = re.sub(r"[^a-z0-9]+", "-", value).strip("-")
    return value[:40] or "x"


def load_existing_urls() -> set[str]:
    urls: set[str] = set()
    for path in EXISTING_MANIFESTS:
        if not path.exists():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            parts = line.split("\t")
            if len(parts) >= 2 and parts[1].startswith("http"):
                urls.add(parts[1])
    return urls


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-topic", type=int, default=120)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    random.seed(args.seed)
    existing = load_existing_urls()
    print(f"mevcut korpuslardaki URL: {len(existing)} (cakisma engellenecek)")

    rows: list[tuple[str, str, str]] = []
    for topic, sources in CATEGORIES.items():
        pool: list[tuple[str, str]] = []
        per_source = max(8, (args.per_topic * 3) // len(sources))
        for lang, category in sources:
            titles = category_members(lang, category, per_source)
            pool.extend((lang, t) for t in titles)
            print(f"  {topic:8s} {lang}:{category[:34]:36s} +{len(titles)}")
        random.shuffle(pool)

        seen_ids: set[str] = set()
        picked = 0
        for lang, title in pool:
            if picked >= args.per_topic:
                break
            url = to_url(lang, title)
            if url in existing:
                continue
            entry_id = f"w-{topic.lower()}-{lang}-{slug(title)}"
            if entry_id in seen_ids:
                continue
            seen_ids.add(entry_id)
            existing.add(url)
            rows.append((entry_id, url, topic))
            picked += 1
        print(f"  -> {topic}: {picked} secildi\n")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write("# Screenshot Search - Wikipedia tabanli egitim korpusu\n")
        fh.write("# Uretici: tools/generate-corpus-from-wikipedia.py (kategori API'si)\n")
        fh.write("# Amac: kural ezberi yerine VERIYLE egitim. TR+EN karisik.\n")
        fh.write("# intent tum satirlarda '-' (olculmez). sensitive=false (kamuya acik sayfalar).\n")
        fh.write("id\turl\ttopic\tintent\tsensitive\tnote\n")
        for entry_id, url, topic in rows:
            fh.write(f"{entry_id}\t{url}\t{topic}\t-\tfalse\twiki auto\n")

    counts: dict[str, int] = {}
    for _, _, topic in rows:
        counts[topic] = counts.get(topic, 0) + 1
    print(f"\ntoplam {len(rows)} satir -> {args.out}")
    print("dagilim: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))


if __name__ == "__main__":
    main()
