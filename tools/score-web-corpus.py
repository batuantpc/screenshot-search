"""Web korpusunun cihazdaki siniflandirma sonucunu ground-truth ile karsilastirir.

Elle etiketleme YOK: etiketler zaten capture-web-corpus.py tarafindan yazildi.
Cihazda uygulama screenshot'lari isledikten sonra bu arac topic/intent dogrulugunu
ve karisiklik matrisini cikarir. Manifestte intent "-" ise o ornek intent
metriginden cikarilir; topic ve sensitive metriklerine dahil kalir.

Akis:
    1) python tools/capture-web-corpus.py --push        # korpusu cihaza at
    2) uygulamada taramayi bekle
    3) python tools/export-device-eval.py               # cihaz DB -> .eval
    4) python tools/score-web-corpus.py                 # bu arac

Kullanim:
    python tools/score-web-corpus.py
    python tools/score-web-corpus.py --eval .private-data/eval/device-verified.eval
    python tools/score-web-corpus.py --self-test        # cihaz gerekmez, mantik testi

Girdi : .private-data/corpus/labels.tsv  +  .private-data/eval/*.eval
Cikti : konsol + .private-data/corpus/score-report.md   (Git'e GIRMEZ)

Eslesme kurali: eval kaydindaki "display:" alani Screenshot_<tarih>_<saat>_<id>.png
seklinde; sondaki <id> manifest id'sidir. Cihazdaki diger screenshot'lar (kullanicinin
kendi ekran goruntuleri) manifest id'siyle eslesmedigi icin otomatik disarida kalir.
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from collections import Counter, defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
LABELS_PATH = REPO_ROOT / ".private-data" / "corpus" / "labels.tsv"
EVAL_PATH = REPO_ROOT / ".private-data" / "eval" / "device-verified.eval"
REPORT_PATH = REPO_ROOT / ".private-data" / "corpus" / "score-report.md"

TOPICS = (
    "RECIPE", "MOVIE", "SERIES", "NEWS", "POLITICS", "FINANCE", "PLACE",
    "PRODUCT", "SPORT", "GAME", "AI", "SCIENCE", "BOOK", "MUSIC", "VIDEO", "SOCIAL",
    "OTHER",
)
INTENTS = ("GO", "TRY", "BUY", "READ_WATCH", "REMEMBER", "UNCLEAR")


class Case:
    """seed-set.eval / device-verified.eval formatindaki tek bir '=== case' blogu."""

    __slots__ = ("name", "display", "topic", "intent", "sensitive", "ocr_chars")

    def __init__(self, name: str) -> None:
        self.name = name
        self.display = ""
        self.topic = ""
        self.intent = ""
        self.sensitive = False
        self.ocr_chars = 0


def parse_eval(path: Path) -> list[Case]:
    """export-device-eval.py'nin urettigi (ve seed-set.eval ile ayni olan) formati okur."""
    if not path.exists():
        sys.exit(f"Eval dosyasi yok: {path}\n"
                 "Once 'python tools/export-device-eval.py' calistirin.")
    cases: list[Case] = []
    current: Case | None = None
    section = ""
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.rstrip("\n")
        if line.startswith("=== case "):
            current = Case(line[len("=== case "):].strip())
            section = "head"
            continue
        if line.startswith("=== end"):
            if current:
                cases.append(current)
            current = None
            section = ""
            continue
        if current is None:
            continue
        if line.startswith("--- "):
            section = line[4:].strip()
            continue
        if section == "head":
            key, _, value = line.partition(":")
            key, value = key.strip(), value.strip()
            if key == "display":
                current.display = value
            elif key == "topic":
                current.topic = value
            elif key == "intent":
                current.intent = value
            elif key == "sensitive":
                current.sensitive = value.lower() == "true"
        elif section == "ocr":
            current.ocr_chars += len(line)
    return cases


def parse_labels(path: Path) -> dict[str, dict[str, str]]:
    if not path.exists():
        sys.exit(f"Etiket dosyasi yok: {path}\n"
                 "Once 'python tools/capture-web-corpus.py' calistirin.")
    labels: dict[str, dict[str, str]] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#") or line.startswith("id\t"):
            continue
        cells = line.split("\t")
        if len(cells) < 7:
            continue
        labels[cells[0]] = {
            "id": cells[0], "file": cells[1], "topic": cells[2],
            "intent": cells[3], "sensitive": cells[4], "url": cells[5],
        }
    return labels


def id_from_display(display: str, known_ids: set[str]) -> str | None:
    """Screenshot_20260721_120007_recipe-en-carbonara.png -> recipe-en-carbonara"""
    stem = re.sub(r"\.(png|jpg|jpeg|webp)$", "", display, flags=re.IGNORECASE)
    match = re.match(r"Screenshot_\d{8}_\d{6}_(.+)$", stem)
    if match and match.group(1) in known_ids:
        return match.group(1)
    # Cihaz dosyayi yeniden adlandirmis olabilir; sondaki id parcasini ara.
    for candidate in sorted(known_ids, key=len, reverse=True):
        if stem.endswith(candidate):
            return candidate
    return None


class Score:
    def __init__(self) -> None:
        self.matched: list[tuple[str, dict[str, str], Case]] = []
        self.missing: list[str] = []
        self.extra: list[str] = []

    @property
    def topic_hits(self) -> int:
        return sum(1 for _, truth, case in self.matched if truth["topic"] == case.topic)

    @property
    def intent_hits(self) -> int:
        return sum(
            1 for _, truth, case in self.matched
            if truth["intent"] in INTENTS and truth["intent"] == case.intent
        )

    @property
    def intent_total(self) -> int:
        return sum(1 for _, truth, _ in self.matched if truth["intent"] in INTENTS)

    @property
    def sensitive_false_alarms(self) -> list[str]:
        return [i for i, truth, case in self.matched
                if truth["sensitive"] == "false" and case.sensitive]

    @property
    def empty_ocr(self) -> list[str]:
        return [i for i, _, case in self.matched if case.ocr_chars < 20]


def build_score(labels: dict[str, dict[str, str]], cases: list[Case]) -> Score:
    score = Score()
    known = set(labels)
    seen: set[str] = set()
    for case in cases:
        entry_id = id_from_display(case.display, known)
        if entry_id is None:
            score.extra.append(case.display or case.name)
            continue
        if entry_id in seen:
            continue  # ayni id iki kez islenmis; ilkini alalim
        seen.add(entry_id)
        score.matched.append((entry_id, labels[entry_id], case))
    score.missing = sorted(known - seen)
    return score


def confusion_rows(score: Score) -> dict[str, Counter]:
    matrix: dict[str, Counter] = defaultdict(Counter)
    for _, truth, case in score.matched:
        matrix[truth["topic"]][case.topic or "-"] += 1
    return matrix


def render_report(score: Score, eval_path: Path, labels_path: Path) -> str:
    total = len(score.matched)
    lines: list[str] = []
    add = lines.append
    add("# Web korpusu skor raporu")
    add("")
    add(f"- Etiket dosyasi: `{labels_path}`")
    add(f"- Eval dosyasi  : `{eval_path}`")
    add(f"- Eslesen ornek : **{total}**")
    add(f"- Cihazda bulunamayan: {len(score.missing)}")
    add(f"- Korpus disi eval kaydi (kullanicinin kendi screenshot'lari): {len(score.extra)}")
    add("")
    if total == 0:
        add("> Eslesen ornek yok. Korpus cihaza atildi mi, tarama bitti mi?")
        return "\n".join(lines) + "\n"

    topic_pct = 100.0 * score.topic_hits / total
    add("## Genel")
    add("")
    add("| Metrik | Dogru | Toplam | Oran |")
    add("| --- | ---: | ---: | ---: |")
    add(f"| Topic | {score.topic_hits} | {total} | {topic_pct:.1f}% |")
    if score.intent_total:
        intent_pct = 100.0 * score.intent_hits / score.intent_total
        add(f"| Intent | {score.intent_hits} | {score.intent_total} | {intent_pct:.1f}% |")
    else:
        add("| Intent | - | 0 | Etiketsiz (olculmedi) |")
    add("")

    matrix = confusion_rows(score)
    add("## Topic bazinda")
    add("")
    add("| Gercek topic | N | Dogru | Oran | En cok karistigi |")
    add("| --- | ---: | ---: | ---: | --- |")
    for topic in TOPICS:
        row = matrix.get(topic)
        if not row:
            continue
        count = sum(row.values())
        correct = row.get(topic, 0)
        wrong = Counter({k: v for k, v in row.items() if k != topic})
        worst = ", ".join(f"{k} x{v}" for k, v in wrong.most_common(3)) or "-"
        add(f"| {topic} | {count} | {correct} | {100.0 * correct / count:.0f}% | {worst} |")
    add("")

    pairs = Counter()
    for _, truth, case in score.matched:
        if truth["topic"] != case.topic:
            pairs[(truth["topic"], case.topic or "-")] += 1
    add("## En cok karisan ciftler")
    add("")
    if pairs:
        add("| Gercek -> Tahmin | Adet |")
        add("| --- | ---: |")
        for (real, guess), count in pairs.most_common(12):
            add(f"| {real} -> {guess} | {count} |")
    else:
        add("Karisiklik yok.")
    add("")

    intent_pairs = Counter()
    for _, truth, case in score.matched:
        if truth["intent"] in INTENTS and truth["intent"] != case.intent:
            intent_pairs[(truth["intent"], case.intent or "-")] += 1
    add("## Intent karisikliklari")
    add("")
    if intent_pairs:
        add("| Gercek -> Tahmin | Adet |")
        add("| --- | ---: |")
        for (real, guess), count in intent_pairs.most_common(12):
            add(f"| {real} -> {guess} | {count} |")
    else:
        add("Karisiklik yok.")
    add("")

    add("## Uyarilar")
    add("")
    alarms = score.sensitive_false_alarms
    add(f"- Yanlis 'sensitive' alarmi: {len(alarms)}"
        + (f" ({', '.join(alarms[:8])})" if alarms else ""))
    empty = score.empty_ocr
    add(f"- OCR metni bos/cok kisa: {len(empty)}"
        + (f" ({', '.join(empty[:8])})" if empty else ""))
    if score.missing:
        add(f"- Cihazda bulunamayan id'ler: {', '.join(score.missing[:20])}"
            + (" ..." if len(score.missing) > 20 else ""))
    add("")

    add("## Yanlis siniflandirilanlar")
    add("")
    add("| id | gercek topic | tahmin | gercek intent | tahmin | url |")
    add("| --- | --- | --- | --- | --- | --- |")
    for entry_id, truth, case in score.matched:
        intent_wrong = truth["intent"] in INTENTS and truth["intent"] != case.intent
        if truth["topic"] == case.topic and not intent_wrong:
            continue
        add(f"| {entry_id} | {truth['topic']} | {case.topic or '-'} | "
            f"{truth['intent']} | {case.intent or '-'} | {truth['url']} |")
    add("")
    return "\n".join(lines) + "\n"


def print_console(score: Score) -> None:
    total = len(score.matched)
    print(f"eslesen ornek : {total}")
    if total == 0:
        print("Eslesen ornek yok - korpus cihaza atilmis ve taranmis mi?")
        return
    print(f"topic  dogruluk: {score.topic_hits}/{total} = {100.0 * score.topic_hits / total:.1f}%")
    if score.intent_total:
        print(
            f"intent dogruluk: {score.intent_hits}/{score.intent_total} = "
            f"{100.0 * score.intent_hits / score.intent_total:.1f}%"
        )
    else:
        print("intent dogruluk: olculmedi (ground truth intent yok)")
    print(f"cihazda yok    : {len(score.missing)}")
    print(f"korpus disi    : {len(score.extra)}")
    matrix = confusion_rows(score)
    print("\ntopic bazinda:")
    for topic in TOPICS:
        row = matrix.get(topic)
        if not row:
            continue
        count = sum(row.values())
        correct = row.get(topic, 0)
        wrong = ", ".join(f"{k} x{v}" for k, v in
                          Counter({k: v for k, v in row.items() if k != topic}).most_common(2))
        print(f"  {topic:8s} {correct:3d}/{count:3d} = {100.0 * correct / count:5.1f}%"
              + (f"   -> {wrong}" if wrong else ""))


# --------------------------------------------------------------- self-test


SELF_TEST_LABELS = """\
id\tfile\ttopic\tintent\tsensitive\turl\tcaptured_at
a-recipe\tScreenshot_20260721_120000_a-recipe.png\tRECIPE\tTRY\tfalse\thttps://x/1\t2026-07-21T12:00:00+00:00
b-movie\tScreenshot_20260721_120001_b-movie.png\tMOVIE\tREAD_WATCH\tfalse\thttps://x/2\t2026-07-21T12:00:01+00:00
c-place\tScreenshot_20260721_120002_c-place.png\tPLACE\tGO\tfalse\thttps://x/3\t2026-07-21T12:00:02+00:00
d-other\tScreenshot_20260721_120003_d-other.png\tOTHER\t-\tfalse\thttps://x/4\t2026-07-21T12:00:03+00:00
e-gone\tScreenshot_20260721_120004_e-gone.png\tGAME\tTRY\tfalse\thttps://x/5\t2026-07-21T12:00:04+00:00
"""

SELF_TEST_EVAL = """\
# sahte cihaz ciktisi

=== case device-001
display: Screenshot_20260721_120000_a-recipe.png
topic: RECIPE
intent: TRY
sensitive: false
--- ocr
Mercimek corbasi tarifi malzemeler ve yapilisi burada
=== end

=== case device-002
display: Screenshot_20260721_120001_b-movie.png
topic: VIDEO
intent: READ_WATCH
sensitive: false
--- ocr
Film sayfasi puan 9.3 yonetmen oyuncular fragman izle
=== end

=== case device-003
display: Screenshot_20260721_120002_c-place.png
topic: PLACE
intent: REMEMBER
sensitive: false
--- ocr
Kapadokya gezi rehberi balon turu konaklama
=== end

=== case device-004
display: Screenshot_20260721_120003_d-other.png
topic: OTHER
intent: REMEMBER
sensitive: true
--- ocr
Dolar euro kur tablosu guncel degerler
=== end

=== case device-005
display: Screenshot_20260715_105752_Chrome.jpg
topic: NEWS
intent: READ_WATCH
sensitive: false
--- ocr
Kullanicinin kendi screenshot'i, korpusa ait degil
=== end
"""


def self_test() -> None:
    """Cihaz olmadan mantigi dogrular: eslesme, dogruluk, karisiklik, uyarilar."""
    with tempfile.TemporaryDirectory(prefix="memento-score-") as tmp:
        root = Path(tmp)
        labels_path = root / "labels.tsv"
        eval_path = root / "device.eval"
        labels_path.write_text(SELF_TEST_LABELS, encoding="utf-8")
        eval_path.write_text(SELF_TEST_EVAL, encoding="utf-8")

        labels = parse_labels(labels_path)
        cases = parse_eval(eval_path)
        score = build_score(labels, cases)

        checks: list[tuple[str, object, object]] = [
            ("etiket sayisi", len(labels), 5),
            ("eval case sayisi", len(cases), 5),
            ("eslesen", len(score.matched), 4),
            ("cihazda yok", score.missing, ["e-gone"]),
            ("korpus disi", len(score.extra), 1),
            ("topic dogru", score.topic_hits, 3),          # b-movie yanlis
            ("intent toplam", score.intent_total, 3),      # d-other etiketsiz
            ("intent dogru", score.intent_hits, 2),        # c-place yanlis
            ("sensitive yanlis alarm", score.sensitive_false_alarms, ["d-other"]),
            ("bos ocr", score.empty_ocr, []),
        ]
        failures = [(name, got, want) for name, got, want in checks if got != want]
        for name, got, want in checks:
            mark = "FAIL" if (name, got, want) in failures else "ok  "
            print(f"  [{mark}] {name}: {got!r} (beklenen {want!r})")

        matrix = confusion_rows(score)
        if matrix["MOVIE"]["VIDEO"] != 1:
            failures.append(("karisiklik matrisi MOVIE->VIDEO", matrix["MOVIE"], 1))
            print(f"  [FAIL] karisiklik matrisi MOVIE->VIDEO: {matrix['MOVIE']!r}")
        else:
            print("  [ok  ] karisiklik matrisi MOVIE->VIDEO: 1")

        report = render_report(score, eval_path, labels_path)
        for needle in ("Topic | 3 | 4 | 75.0%", "Intent | 2 | 3 | 66.7%",
                       "MOVIE -> VIDEO", "b-movie"):
            if needle not in report:
                failures.append(("rapor icerigi", needle, "raporda"))
                print(f"  [FAIL] rapor icinde bulunamadi: {needle!r}")
            else:
                print(f"  [ok  ] rapor icerigi: {needle!r}")

        if failures:
            sys.exit(f"\nself-test BASARISIZ ({len(failures)} kontrol)")
        print("\nself-test gecti.")


# -------------------------------------------------------------------- main


def main() -> None:
    parser = argparse.ArgumentParser(description="Web korpusu skorlama araci.")
    parser.add_argument("--labels", type=Path, default=LABELS_PATH)
    parser.add_argument("--eval", dest="eval_path", type=Path, default=EVAL_PATH)
    parser.add_argument("--report", type=Path, default=REPORT_PATH)
    parser.add_argument("--self-test", action="store_true",
                        help="Cihaz gerekmez; sahte veriyle mantigi dogrular")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return

    labels = parse_labels(args.labels)
    cases = parse_eval(args.eval_path)
    score = build_score(labels, cases)
    print_console(score)

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(render_report(score, args.eval_path, args.labels),
                           encoding="utf-8", newline="\n")
    print(f"\nrapor -> {args.report}")


if __name__ == "__main__":
    main()
