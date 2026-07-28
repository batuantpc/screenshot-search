"""RSS/Atom akislarindan gercek-dunya makale korpusu uretir.

Neden akis: Wikipedia olcek veriyor ama hepsi ansiklopedi dili. Kullanicinin gercekte
screenshot aldigi seyler haber/tarif/spor/oyun MAKALELERI. Elle URL yazmak olceklenmez;
akislar her kategoride onlarca farkli siteden derin (ana sayfa degil) sayfa verir.

Wikipedia uretecinden farki: burada URL'ler yayinlarin KENDI guncel icerigidir, yani
gorsel duzen de gercek (reklam, paylas butonlari, yorum alani...) - modelin cihazda
karsilasacagi seye daha yakin.

Kullanim:
    python tools/generate-corpus-from-feeds.py --per-topic 45
"""

from __future__ import annotations

import argparse
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT = REPO_ROOT / "tools" / "corpus-feed-v1.tsv"
UA = "ProjectMementoCorpusBot/1.0 (personal research project)"
REQUEST_DELAY = 0.8

# topic -> akis adresleri. TR + EN karisik, kategori basina cok sayida FARKLI domain.
FEEDS: dict[str, list[str]] = {
    "NEWS": [
        "https://feeds.bbci.co.uk/turkce/rss.xml",
        "https://www.ntv.com.tr/gundem.rss",
        "https://www.sozcu.com.tr/feed/",
        "https://www.aa.com.tr/tr/rss/default?cat=guncel",
        "https://feeds.bbci.co.uk/news/world/rss.xml",
        "https://feeds.npr.org/1001/rss.xml",
        "https://www.theguardian.com/world/rss",
        "https://www.aljazeera.com/xml/rss/all.xml",
    ],
    "POLITICS": [
        "https://www.theguardian.com/politics/rss",
        "https://feeds.bbci.co.uk/news/politics/rss.xml",
        "https://rss.politico.com/politics-news.xml",
        "https://www.aa.com.tr/tr/rss/default?cat=politika",
    ],
    "FINANCE": [
        "https://www.investing.com/rss/news_25.rss",
        "https://www.cnbc.com/id/10001147/device/rss/rss.html",
        "https://feeds.marketwatch.com/marketwatch/topstories/",
        "https://www.aa.com.tr/tr/rss/default?cat=ekonomi",
        "https://feeds.bbci.co.uk/news/business/rss.xml",
    ],
    "SPORT": [
        "https://www.fotomac.com.tr/rss/anasayfa.xml",
        "https://www.sporx.com/rss/",
        "https://feeds.bbci.co.uk/sport/rss.xml",
        "https://www.espn.com/espn/rss/news",
        "https://www.aa.com.tr/tr/rss/default?cat=spor",
    ],
    "SCIENCE": [
        "https://www.sciencedaily.com/rss/all.xml",
        "https://phys.org/rss-feed/",
        "https://evrimagaci.org/rss",
        "https://www.nasa.gov/rss/dyn/breaking_news.rss",
        "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml",
    ],
    "AI": [
        "https://openai.com/news/rss.xml",
        "https://www.artificialintelligence-news.com/feed/",
        "https://venturebeat.com/category/ai/feed/",
        "https://webrazzi.com/feed",
        "https://export.arxiv.org/rss/cs.AI",
    ],
    "MOVIE": [
        "https://www.beyazperde.com/rss/haberler.xml",
        "https://www.indiewire.com/c/film/feed/",
        "https://www.rogerebert.com/feed",
        "https://variety.com/v/film/feed/",
    ],
    "SERIES": [
        "https://tvline.com/feed/",
        "https://variety.com/v/tv/feed/",
        "https://www.indiewire.com/c/tv/feed/",
    ],
    "GAME": [
        "https://feeds.ign.com/ign/games-all",
        "https://www.gamespot.com/feeds/news/",
        "https://www.eurogamer.net/feed",
        "https://www.polygon.com/rss/index.xml",
    ],
    "MUSIC": [
        "https://pitchfork.com/feed/feed-news/rss",
        "https://www.rollingstone.com/music/feed/",
        "https://www.billboard.com/feed/",
    ],
    "BOOK": [
        "https://lithub.com/feed/",
        "https://www.theguardian.com/books/rss",
        "https://bookriot.com/feed/",
    ],
    "RECIPE": [
        "https://www.nefisyemektarifleri.com/feed/",
        "https://yemek.com/feed/",
        "https://www.seriouseats.com/rss",
        "https://www.bbcgoodfood.com/feed",
    ],
    "PLACE": [
        "https://www.lonelyplanet.com/news/feed",
        "https://www.theguardian.com/travel/rss",
        "https://feeds.bbci.co.uk/news/uk/rss.xml",
    ],
}


def fetch(url: str, retries: int = 2) -> str | None:
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    delay = 1.5
    for attempt in range(retries + 1):
        try:
            with urllib.request.urlopen(request, timeout=25) as response:
                data = response.read()
            time.sleep(REQUEST_DELAY)
            return data.decode("utf-8", errors="replace")
        except Exception as exc:
            if attempt < retries:
                time.sleep(delay)
                delay *= 2
                continue
            print(f"    uyari: {url} -> {exc}")
    return None


def feed_links(xml: str) -> list[str]:
    """RSS <link>...</link> ve Atom <link href="..."/> bicimlerini birlikte destekler."""
    links: list[str] = []
    for match in re.finditer(r"<link[^>]*\shref=[\"']([^\"']+)[\"']", xml, re.I):
        links.append(match.group(1))
    for match in re.finditer(r"<link>\s*(?:<!\[CDATA\[)?\s*(https?://[^<\]\s]+)", xml, re.I):
        links.append(match.group(1))
    seen: set[str] = set()
    out: list[str] = []
    for link in links:
        link = link.strip()
        # Akisin kendi adresi ve kategori koku ise makale degildir.
        if link.endswith((".xml", ".rss", "/feed", "/feed/", "/rss")):
            continue
        if link in seen:
            continue
        seen.add(link)
        out.append(link)
    return out


def slug(value: str) -> str:
    value = value.lower()
    for src, dst in (("ı", "i"), ("ğ", "g"), ("ü", "u"), ("ş", "s"), ("ö", "o"), ("ç", "c")):
        value = value.replace(src, dst)
    return re.sub(r"[^a-z0-9]+", "-", value).strip("-")[:44] or "x"


def existing_urls() -> set[str]:
    urls: set[str] = set()
    for path in (REPO_ROOT / "tools").glob("corpus-*.tsv"):
        for line in path.read_text(encoding="utf-8").splitlines():
            parts = line.split("\t")
            if len(parts) >= 2 and parts[1].startswith("http"):
                urls.add(parts[1])
    return urls


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-topic", type=int, default=45)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = ap.parse_args()

    seen_urls = existing_urls()
    print(f"mevcut korpuslardaki URL: {len(seen_urls)} (cakisma engellenecek)")
    rows: list[tuple[str, str, str]] = []

    for topic, feeds in FEEDS.items():
        picked = 0
        per_feed = max(4, args.per_topic // max(1, len(feeds)) + 3)
        for feed in feeds:
            if picked >= args.per_topic:
                break
            xml = fetch(feed)
            if not xml:
                continue
            links = feed_links(xml)
            host = urllib.parse.urlparse(feed).netloc.replace("www.", "").split(".")[0]
            added = 0
            for link in links:
                if picked >= args.per_topic or added >= per_feed:
                    break
                if not link.startswith("http") or link in seen_urls:
                    continue
                tail = urllib.parse.urlparse(link).path.rstrip("/").rsplit("/", 1)[-1]
                entry_id = f"f-{topic.lower()}-{slug(host)}-{slug(tail)}"[:70]
                if any(entry_id == r[0] for r in rows):
                    continue
                seen_urls.add(link)
                rows.append((entry_id, link, topic))
                picked += 1
                added += 1
            print(f"  {topic:8s} {host[:24]:26s} +{added}")
        print(f"  -> {topic}: {picked}\n")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write("# Project Memento - RSS akislarindan gercek-dunya makale korpusu\n")
        fh.write("# Uretici: tools/generate-corpus-from-feeds.py\n")
        fh.write("# Yayinlarin kendi guncel makaleleri: gercek gorsel duzen, reklam, yorum alani.\n")
        fh.write("id\turl\ttopic\tintent\tsensitive\tnote\n")
        for entry_id, url, topic in rows:
            fh.write(f"{entry_id}\t{url}\t{topic}\t-\tfalse\tfeed auto\n")

    counts: dict[str, int] = {}
    for _, _, topic in rows:
        counts[topic] = counts.get(topic, 0) + 1
    domains = {urllib.parse.urlparse(u).netloc for _, u, _ in rows}
    print(f"\ntoplam {len(rows)} satir, {len(domains)} farkli domain -> {args.out}")
    print("dagilim: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))


if __name__ == "__main__":
    main()
