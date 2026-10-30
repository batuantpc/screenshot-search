"""Etiketli web korpusu uretir: headless Chrome ile telefon boyutunda ekran goruntusu alir.

Amac: siniflandiriciyi olcmek icin ground-truth etiketli bir set uretmek.
Internetten otomatik uretilen screenshot'larda topic ve sensitive etiketlenebilir.
Kullanicinin neden kaydettigini bilemeyecegimiz holdout setlerinde intent "-" olur.

Kullanim (repo kokunden):
    python tools/capture-web-corpus.py                 # manifestteki her sey
    python tools/capture-web-corpus.py --limit 8       # ilk 8 kayit
    python tools/capture-web-corpus.py --topic RECIPE  # tek konu
    python tools/capture-web-corpus.py --only recipe-tr-mercimek
    python tools/capture-web-corpus.py --force         # var olani yeniden cek
    python tools/capture-web-corpus.py --push --dry-run  # adb komutlarini bas

Girdi : tools/corpus-manifest.tsv   (Git'e girer, kisisel veri yok)
Cikti : .private-data/corpus/*.png + .private-data/corpus/labels.tsv  (Git'e GIRMEZ)

Neden CDP (DevTools Protocol), duz --screenshot bayragi degil:
Chrome'un "--headless --screenshot --window-size" yolu masaustu render motorunu
kullanir; <meta name=viewport> yok sayilir, dar pencerede metin sag kenardan
kirpilir ve OCR'a bozuk kelime gider. CDP uzerinden Emulation.setDeviceMetricsOverride
ile mobile=true verince gercek mobil layout cikar. Ayrica sayfa yuklendikten sonra
cerez bannerlarini gizleyip sahte tarayici cubugu enjekte edebiliyoruz.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import unquote
from urllib.request import urlopen

REPO_ROOT = Path(__file__).resolve().parent.parent
MANIFEST_PATH = REPO_ROOT / "tools" / "corpus-manifest.tsv"
CORPUS_DIR = REPO_ROOT / ".private-data" / "corpus"
LABELS_PATH = CORPUS_DIR / "labels.tsv"

# ScreenshotItem.kt ile birebir ayni olmali (data/ScreenshotItem.kt).
TOPICS = (
    "RECIPE", "MOVIE", "SERIES", "NEWS", "POLITICS", "FINANCE", "PLACE",
    "PRODUCT", "SPORT", "GAME", "AI", "SCIENCE", "BOOK", "MUSIC", "VIDEO", "SOCIAL",
    "OTHER",
)
INTENTS = ("GO", "TRY", "BUY", "READ_WATCH", "REMEMBER", "UNCLEAR")
OPTIONAL_INTENTS = INTENTS + ("-",)

# 1080x2340 fiziksel piksel = 360x780 CSS px @ dsf 3 (yaygin Android telefon).
DEVICE_WIDTH = 360
DEVICE_HEIGHT = 780
DEVICE_SCALE = 3
MOBILE_UA = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
)
# Durum cubugu (24) + Chrome adres cubugu (56) = 80 CSS px, gercek cihazdaki gibi.
BROWSER_CHROME_HEIGHT = 80

# Dosya adlari sabit kalsin diye zaman damgasi manifest sirasindan turetilir;
# boylece ayni manifest her kosuda ayni dosya adini uretir (idempotent).
FILENAME_DATE = "20260721"
FILENAME_BASE_SECOND = 12 * 3600  # 12:00:00

DEVICE_SCREENSHOT_DIR = "/sdcard/Pictures/Screenshots"

CHROME_CANDIDATES = (
    os.environ.get("CHROME"),
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    "google-chrome",
    "chromium",
)

# Bilinen cerez/consent katmanlarini GIZLER - "kabul et" butonuna BASMAZ.
# (Kullanici adina sozlesme kabul etmiyoruz; sadece kendi DOM kopyamizi temizliyoruz.)
CLEANUP_JS = r"""
(() => {
  const selectors = [
    '#onetrust-consent-sdk', '#onetrust-banner-sdk', '#CybotCookiebotDialog',
    '#cookiescript_injected', '#usercentrics-root', '#didomi-host', '#didomi-popup',
    '[id^="sp_message_container"]', '.qc-cmp2-container', '.fc-consent-root',
    '#gdpr-consent-tool-wrapper', '.cookie-banner', '.cookie-consent',
    '.cookies-notice', '#cookie-law-info-bar', '#cookieChoiceInfo',
    '[class*="CookieBanner"]', '[class*="cookie-banner"]', '[id*="cookie-banner"]',
    '[aria-label="Cookie banner"]', '[data-testid="cookie-banner"]',
    '#credential_picker_container', 'iframe[title*="onetrust"]',
  ];
  for (const sel of selectors) {
    document.querySelectorAll(sel).forEach((el) => el.remove());
  }
  // Banner'lar govdeyi kilitliyor olabilir; kaydirmayi geri ac.
  for (const el of [document.documentElement, document.body]) {
    if (!el) continue;
    el.style.setProperty('overflow', 'visible', 'important');
    el.style.setProperty('position', 'static', 'important');
  }
  // Tam ekran kaplayan yuksek z-index overlay'leri (login duvari vb.) kaldir.
  const vh = window.innerHeight, vw = window.innerWidth;
  document.querySelectorAll('body *').forEach((el) => {
    const cs = getComputedStyle(el);
    if (cs.position !== 'fixed') return;
    const z = parseInt(cs.zIndex, 10);
    if (!Number.isFinite(z) || z < 1000) return;
    const r = el.getBoundingClientRect();
    if (r.width >= vw * 0.9 && r.height >= vh * 0.55) el.remove();
  });
  return true;
})();
"""


def browser_chrome_js(url: str, clock: str) -> str:
    """Sahte durum cubugu + Chrome adres cubugu enjekte eder.

    Gercek screenshot'larda saat/pil ve omnibox'taki alan adi OCR'a giriyor
    (bkz. device-verified.eval: "10:57  2% sozcu.com.tr/suriye-den-yal").
    Bu sinyali korpustan silmek modeli haksiz yere kolaylastirirdi.
    """
    match = re.match(r"https?://([^/]+)(/[^?#]*)?", url)
    host = (match.group(1) if match else url).replace("www.", "")
    path = (match.group(2) or "") if match else ""
    # Gercek Chrome adres cubugu yuzde kodlamasini cozup gosterir (ornek: %C3%A7 -> c).
    shown = (host + unquote(path))[:34]
    payload = json.dumps({"shown": shown, "clock": clock, "chrome": BROWSER_CHROME_HEIGHT})
    # Iki tuzak var, ikisi de basimiza geldi:
    #  1) innerHTML KULLANILMIYOR - YouTube gibi Trusted Types zorunlu kilan siteler
    #     innerHTML atamasini bloklayip TypeError firlatiyor.
    #  2) Cubuk shadow DOM icine kuruluyor - Steam gibi agresif global CSS'i olan siteler
    #     yoksa bizim span'lerimizi ezip yaziyi yok ediyordu.
    return (
        "(() => { const cfg = " + payload + r""";
  const body = document.body;
  if (!body) return false;
  document.getElementById('__screenshot_search_browser_ui')?.remove();
  // Steam gibi siteler layout viewport'u gorsel viewport'tan genis tutuyor; "right:0"
  // kullanirsak cubugun sag ucu ekran disinda kaliyor. Genisligi gorsel viewport'tan aliyoruz.
  const visualWidth = Math.round(
    (window.visualViewport && window.visualViewport.width) || document.documentElement.clientWidth
  );
  const host = document.createElement('div');
  host.id = '__screenshot_search_browser_ui';
  for (const [prop, value] of Object.entries({
    position: 'fixed', top: '0px', left: '0px', width: visualWidth + 'px',
    height: cfg.chrome + 'px', 'z-index': '2147483647', margin: '0px',
    padding: '0px', border: '0px', 'pointer-events': 'none', display: 'block',
  })) host.style.setProperty(prop, value, 'important');
  const root = host.attachShadow({ mode: 'open' });
  const make = (tag, css, text) => {
    const el = document.createElement(tag);
    el.setAttribute('style', 'all:initial;box-sizing:border-box;' +
      'font-family:Roboto,Arial,Helvetica,sans-serif;color:#202124;' + css);
    if (text !== undefined) el.textContent = text;
    return el;
  };
  const bar = make('div', 'display:block;width:100%;height:' + cfg.chrome +
    'px;background:#f1f3f4');
  const status = make('div', 'display:flex;align-items:center;' +
    'justify-content:space-between;height:24px;padding:0 10px;font-size:11px;' +
    'font-weight:500;width:100%');
  status.appendChild(make('span', 'display:inline-block;font-size:11px;font-weight:500', cfg.clock));
  status.appendChild(make('span', 'display:inline-block;font-size:11px;font-weight:500', 'LTE  %84'));
  const toolbar = make('div', 'display:flex;align-items:center;height:56px;' +
    'padding:0 8px;gap:8px;width:100%');
  const omnibox = make('div', 'display:flex;align-items:center;flex:1;height:36px;' +
    'border-radius:18px;background:#ffffff;padding:0 12px;font-size:13px;' +
    'color:#5f6368;overflow:hidden;white-space:nowrap;text-overflow:clip',
    '\u{1F512}  ' + cfg.shown);
  toolbar.appendChild(omnibox);
  toolbar.appendChild(make('div', 'display:block;font-size:18px;color:#5f6368;' +
    'padding:0 4px;line-height:1', '\u22EE'));
  bar.appendChild(status);
  bar.appendChild(toolbar);
  root.appendChild(bar);
  document.documentElement.appendChild(host);
  // Govdeyi asagi kaydir: fixed cocuklar da birlikte iner, gercek tarayicidaki gibi.
  // Cubuk basariyla eklendikten SONRA yapiliyor ki hata halinde siyah bant kalmasin.
  body.style.setProperty('transform', 'translateY(' + cfg.chrome + 'px)', 'important');
  body.style.setProperty('transform-origin', '0 0', 'important');
  return true;
})();"""
    )


# ---------------------------------------------------------------- websocket


class WebSocketError(RuntimeError):
    pass


class WebSocket:
    """Stdlib-only minimal RFC6455 istemcisi (sadece CDP icin, pip bagimliligi yok)."""

    def __init__(self, url: str, timeout: float = 30.0) -> None:
        match = re.match(r"ws://([^:/]+):(\d+)(/.*)", url)
        if not match:
            raise WebSocketError(f"Gecersiz ws adresi: {url}")
        host, port, path = match.group(1), int(match.group(2)), match.group(3)
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        handshake = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        self.sock.sendall(handshake.encode())
        self._buffer = b""
        while b"\r\n\r\n" not in self._buffer:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise WebSocketError("Handshake sirasinda baglanti kapandi")
            self._buffer += chunk
        head, self._buffer = self._buffer.split(b"\r\n\r\n", 1)
        if b" 101 " not in head.split(b"\r\n")[0]:
            raise WebSocketError(f"Upgrade reddedildi: {head.splitlines()[0]!r}")

    def settimeout(self, timeout: float) -> None:
        self.sock.settimeout(timeout)

    def _read_exactly(self, count: int) -> bytes:
        while len(self._buffer) < count:
            chunk = self.sock.recv(1 << 20)
            if not chunk:
                raise WebSocketError("Baglanti beklenmedik sekilde kapandi")
            self._buffer += chunk
        out, self._buffer = self._buffer[:count], self._buffer[count:]
        return out

    def send(self, text: str) -> None:
        payload = text.encode("utf-8")
        header = bytearray([0x81])
        size = len(payload)
        if size < 126:
            header.append(0x80 | size)
        elif size < 65536:
            header.append(0x80 | 126)
            header += struct.pack(">H", size)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", size)
        mask = os.urandom(4)
        header += mask
        masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def recv(self) -> str:
        chunks: list[bytes] = []
        while True:
            first, second = self._read_exactly(2)
            fin = first & 0x80
            opcode = first & 0x0F
            masked = second & 0x80
            size = second & 0x7F
            if size == 126:
                size = struct.unpack(">H", self._read_exactly(2))[0]
            elif size == 127:
                size = struct.unpack(">Q", self._read_exactly(8))[0]
            mask = self._read_exactly(4) if masked else b""
            payload = self._read_exactly(size) if size else b""
            if mask:
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
            if opcode == 0x9:  # ping -> pong
                self.sock.sendall(b"\x8a\x80" + os.urandom(4))
                continue
            if opcode == 0xA:  # pong
                continue
            if opcode == 0x8:
                raise WebSocketError("Karsi taraf baglantiyi kapatti")
            chunks.append(payload)
            if fin:
                return b"".join(chunks).decode("utf-8", errors="replace")

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


# ------------------------------------------------------------------ chrome


class CdpError(RuntimeError):
    pass


class HeadlessChrome:
    """Tek bir headless Chrome surecini ayakta tutar; her URL icin sekmeyi geri kullanir."""

    def __init__(self, binary: str, timeout: float) -> None:
        self.binary = binary
        self.timeout = timeout
        self.profile_dir = Path(tempfile.mkdtemp(prefix="screenshot-search-corpus-"))
        self.process: subprocess.Popen[bytes] | None = None
        self.ws: WebSocket | None = None
        self._message_id = 0

    def __enter__(self) -> "HeadlessChrome":
        args = [
            self.binary,
            "--headless=new",
            "--disable-gpu",
            "--hide-scrollbars",
            "--mute-audio",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-extensions",
            "--disable-background-networking",
            "--disable-sync",
            "--disable-features=Translate,MediaRouter,OptimizationHints",
            "--lang=tr-TR",
            f"--user-data-dir={self.profile_dir}",
            "--remote-debugging-port=0",
            "about:blank",
        ]
        self.process = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        port = self._await_debug_port()
        self.ws = WebSocket(self._page_websocket_url(port), timeout=self.timeout)
        self.call("Page.enable")
        self.call("Runtime.enable")
        self.call("Emulation.setDeviceMetricsOverride", {
            "width": DEVICE_WIDTH,
            "height": DEVICE_HEIGHT,
            "deviceScaleFactor": DEVICE_SCALE,
            "mobile": True,
            "screenOrientation": {"type": "portraitPrimary", "angle": 0},
        })
        self.call("Emulation.setUserAgentOverride", {
            "userAgent": MOBILE_UA,
            "acceptLanguage": "tr-TR,tr;q=0.9,en-US;q=0.8",
            "platform": "Android",
        })
        self.call("Emulation.setTouchEmulationEnabled", {"enabled": True, "maxTouchPoints": 5})
        return self

    def __exit__(self, *_exc: object) -> None:
        if self.ws:
            self.ws.close()
        if self.process:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
        shutil.rmtree(self.profile_dir, ignore_errors=True)

    def _await_debug_port(self) -> int:
        port_file = self.profile_dir / "DevToolsActivePort"
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if port_file.exists():
                lines = port_file.read_text(encoding="utf-8").splitlines()
                if lines and lines[0].strip().isdigit():
                    return int(lines[0].strip())
            if self.process and self.process.poll() is not None:
                raise CdpError("Chrome beklenmedik sekilde kapandi (DevTools portu yok)")
            time.sleep(0.1)
        raise CdpError("Chrome DevTools portu 30 sn icinde acilmadi")

    def _page_websocket_url(self, port: int) -> str:
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            try:
                with urlopen(f"http://127.0.0.1:{port}/json/list", timeout=5) as resp:
                    targets = json.loads(resp.read().decode("utf-8"))
            except OSError:
                time.sleep(0.3)
                continue
            for target in targets:
                if target.get("type") == "page" and target.get("webSocketDebuggerUrl"):
                    return target["webSocketDebuggerUrl"]
            time.sleep(0.3)
        raise CdpError("Chrome'da sayfa hedefi bulunamadi")

    def call(self, method: str, params: dict | None = None, timeout: float | None = None) -> dict:
        if self.ws is None:
            raise CdpError("WebSocket acik degil")
        self._message_id += 1
        message_id = self._message_id
        self.ws.settimeout(timeout or self.timeout)
        self.ws.send(json.dumps({"id": message_id, "method": method, "params": params or {}}))
        while True:
            message = json.loads(self.ws.recv())
            if message.get("id") != message_id:
                continue  # olay bildirimleri bizi ilgilendirmiyor
            if "error" in message:
                raise CdpError(f"{method}: {message['error'].get('message', message['error'])}")
            return message.get("result", {})

    def evaluate(self, expression: str, timeout: float | None = None) -> object:
        result = self.call(
            "Runtime.evaluate",
            {"expression": expression, "returnByValue": True, "awaitPromise": True},
            timeout=timeout,
        )
        if result.get("exceptionDetails"):
            raise CdpError(str(result["exceptionDetails"].get("text", "JS hatasi")))
        return result.get("result", {}).get("value")

    def capture(self, url: str, out_path: Path, settle: float, clock: str, chrome_ui: bool) -> None:
        # Onceki sayfanin kalintisi yeni cekimi kirletmesin.
        self.call("Page.navigate", {"url": "about:blank"})
        time.sleep(0.2)
        self.call("Page.navigate", {"url": url}, timeout=self.timeout)
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            try:
                if self.evaluate("document.readyState", timeout=10) == "complete":
                    break
            except CdpError:
                pass  # navigasyon sirasinda context degisebilir
            time.sleep(0.25)
        time.sleep(settle)
        try:
            self.evaluate(CLEANUP_JS, timeout=15)
        except CdpError as exc:
            print(f"    uyari: banner temizligi calismadi ({exc})")
        if chrome_ui:
            try:
                self.evaluate(browser_chrome_js(url, clock), timeout=15)
            except CdpError as exc:
                print(f"    uyari: tarayici cubugu enjekte edilemedi ({exc})")
        time.sleep(0.4)
        result = self.call("Page.captureScreenshot", {"format": "png", "captureBeyondViewport": False})
        data = result.get("data")
        if not data:
            raise CdpError("Bos ekran goruntusu dondu")
        out_path.write_bytes(base64.b64decode(data))


def find_chrome() -> str:
    for candidate in CHROME_CANDIDATES:
        if not candidate:
            continue
        path = Path(candidate)
        if path.is_file():
            return str(path)
        resolved = shutil.which(candidate)
        if resolved:
            return resolved
    sys.exit("Chrome/Edge bulunamadi. CHROME ortam degiskeniyle tam yol verin.")


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


# ---------------------------------------------------------------- manifest


class Entry:
    __slots__ = ("index", "id", "url", "topic", "intent", "sensitive", "note")

    def __init__(self, index: int, row: list[str]) -> None:
        self.index = index
        self.id, self.url, self.topic, self.intent, sensitive = row[:5]
        self.note = row[5] if len(row) > 5 else ""
        self.sensitive = sensitive.strip().lower() == "true"

    @property
    def filename(self) -> str:
        total = FILENAME_BASE_SECOND + self.index
        stamp = f"{total // 3600:02d}{(total // 60) % 60:02d}{total % 60:02d}"
        return f"Screenshot_{FILENAME_DATE}_{stamp}_{self.id}.png"

    @property
    def clock(self) -> str:
        total = FILENAME_BASE_SECOND + self.index
        return f"{total // 3600:02d}:{(total // 60) % 60:02d}"


def load_manifest(path: Path) -> list[Entry]:
    if not path.exists():
        sys.exit(f"Manifest bulunamadi: {path}")
    entries: list[Entry] = []
    seen: set[str] = set()
    for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        row = line.split("\t")
        if row[0].strip() == "id":
            continue  # baslik satiri
        if len(row) < 5:
            sys.exit(f"{path}:{line_no} en az 5 sutun gerekli, {len(row)} bulundu")
        entry = Entry(len(entries), [cell.strip() for cell in row])
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", entry.id):
            sys.exit(f"{path}:{line_no} gecersiz id: {entry.id!r} (kucuk harf/rakam/tire)")
        if entry.id in seen:
            sys.exit(f"{path}:{line_no} tekrar eden id: {entry.id}")
        if entry.topic not in TOPICS:
            sys.exit(f"{path}:{line_no} gecersiz topic: {entry.topic}")
        if entry.intent not in OPTIONAL_INTENTS:
            sys.exit(f"{path}:{line_no} gecersiz intent: {entry.intent}")
        if not entry.url.startswith("http"):
            sys.exit(f"{path}:{line_no} gecersiz url: {entry.url}")
        seen.add(entry.id)
        entries.append(entry)
    if not entries:
        sys.exit(f"Manifest bos: {path}")
    return entries


# ------------------------------------------------------------------- cikti


def png_size(path: Path) -> tuple[int, int]:
    header = path.read_bytes()[:24]
    if len(header) < 24 or header[:8] != b"\x89PNG\r\n\x1a\n":
        return (0, 0)
    return struct.unpack(">II", header[16:24])


def read_labels(path: Path) -> dict[str, list[str]]:
    if not path.exists():
        return {}
    rows: dict[str, list[str]] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#") or line.startswith("id\t"):
            continue
        cells = line.split("\t")
        if len(cells) >= 7:
            rows[cells[0]] = cells
    return rows


def write_labels(path: Path, rows: dict[str, list[str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as out:
        out.write("# Screenshot Search web korpusu - ground truth etiketleri\n")
        out.write("# Uretici: tools/capture-web-corpus.py  |  Git'e GIRMEZ\n")
        out.write("id\tfile\ttopic\tintent\tsensitive\turl\tcaptured_at\n")
        for key in sorted(rows):
            out.write("\t".join(rows[key]) + "\n")


def push_commands(adb: str, files: list[Path]) -> list[list[str]]:
    commands = [[adb, "shell", "mkdir", "-p", DEVICE_SCREENSHOT_DIR]]
    for path in files:
        commands.append([adb, "push", str(path), f"{DEVICE_SCREENSHOT_DIR}/{path.name}"])
    # MediaStore taramasi: once modern yol, sonra eski broadcast (cihaza gore biri tutuyor).
    commands.append([adb, "shell", "cmd", "media", "rescan", "--path", DEVICE_SCREENSHOT_DIR])
    for path in files:
        commands.append([
            adb, "shell", "am", "broadcast", "-a",
            "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
            "-d", f"file://{DEVICE_SCREENSHOT_DIR}/{path.name}",
        ])
    return commands


# -------------------------------------------------------------------- main


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Etiketli web korpusu yakalar.")
    parser.add_argument("--limit", type=int, default=0, help="En fazla N kayit isle")
    parser.add_argument("--topic", action="append", default=[], help="Sadece bu topic (tekrarlanabilir)")
    parser.add_argument("--only", action="append", default=[], help="Sadece bu id (tekrarlanabilir)")
    parser.add_argument("--force", action="store_true", help="Var olan PNG'leri yeniden cek")
    parser.add_argument("--dry-run", action="store_true", help="Hicbir sey cekme, yapilacaklari bas")
    parser.add_argument("--timeout", type=float, default=30.0, help="URL basina saniye (varsayilan 30)")
    parser.add_argument("--settle", type=float, default=2.5, help="Yukleme sonrasi bekleme (sn)")
    parser.add_argument("--retries", type=int, default=1, help="Basarisiz URL icin ek deneme (varsayilan 1)")
    parser.add_argument("--no-chrome-ui", action="store_true", help="Sahte durum/adres cubugunu ekleme")
    parser.add_argument("--push", action="store_true", help="Cekilenleri adb ile cihaza at")
    parser.add_argument("--manifest", type=Path, default=MANIFEST_PATH)
    parser.add_argument("--out-dir", type=Path, default=CORPUS_DIR)
    return parser.parse_args()


def select_entries(entries: list[Entry], args: argparse.Namespace) -> list[Entry]:
    topics = {topic.upper() for topic in args.topic}
    only = {value.lower() for value in args.only}
    selected = [
        entry for entry in entries
        if (not topics or entry.topic in topics) and (not only or entry.id in only)
    ]
    if only:
        missing = only - {entry.id for entry in selected}
        if missing:
            sys.exit(f"Manifestte yok: {', '.join(sorted(missing))}")
    if args.limit > 0:
        selected = selected[: args.limit]
    return selected


def main() -> None:
    args = parse_args()
    entries = load_manifest(args.manifest)
    selected = select_entries(entries, args)
    out_dir: Path = args.out_dir
    labels_path = out_dir / "labels.tsv"
    labels = read_labels(labels_path)

    pending: list[Entry] = []
    skipped: list[Entry] = []
    for entry in selected:
        existing = list(out_dir.glob(f"*_{entry.id}.png"))
        if existing and not args.force:
            skipped.append(entry)
        else:
            pending.append(entry)

    print(f"Manifest: {len(entries)} kayit | secilen: {len(selected)} | "
          f"cekilecek: {len(pending)} | atlanan(mevcut): {len(skipped)}")

    if args.dry_run:
        for entry in pending:
            print(f"  [cekilecek] {entry.id:28s} {entry.topic:8s} {entry.intent:11s} {entry.url}")
        if args.push:
            adb_path = os.environ.get("ADB") or "adb"
            files = [out_dir / entry.filename for entry in selected]
            print("\nadb komutlari (calistirilmadi):")
            for command in push_commands(adb_path, files[:3]):
                print("  " + subprocess.list2cmdline(command))
            if len(files) > 3:
                print(f"  ... ve {len(files) - 3} dosya daha")
        return

    ok: list[Entry] = []
    failed: list[tuple[Entry, str]] = []

    if pending:
        out_dir.mkdir(parents=True, exist_ok=True)
        binary = find_chrome()
        print(f"Chrome: {binary}")
        print(f"Cihaz profili: {DEVICE_WIDTH}x{DEVICE_HEIGHT} CSS px @ dsf {DEVICE_SCALE} "
              f"-> {DEVICE_WIDTH * DEVICE_SCALE}x{DEVICE_HEIGHT * DEVICE_SCALE} px")
        with HeadlessChrome(binary, args.timeout) as chrome:
            for position, entry in enumerate(pending, start=1):
                target = out_dir / entry.filename
                print(f"[{position}/{len(pending)}] {entry.id} <- {entry.url}")
                last_error = ""
                for attempt in range(args.retries + 1):
                    try:
                        chrome.capture(
                            entry.url, target, args.settle, entry.clock, not args.no_chrome_ui,
                        )
                        width, height = png_size(target)
                        size_kb = target.stat().st_size // 1024
                        if size_kb < 20:
                            raise CdpError(f"Cikti supheli kucuk ({size_kb} KB) - bos sayfa olabilir")
                        print(f"    ok  {width}x{height}  {size_kb} KB  -> {target.name}")
                        labels[entry.id] = [
                            entry.id, target.name, entry.topic, entry.intent,
                            "true" if entry.sensitive else "false", entry.url,
                            datetime.now(timezone.utc).isoformat(timespec="seconds"),
                        ]
                        ok.append(entry)
                        # Her basarili cekimden sonra yaziliyor: uzun kosu ortasinda
                        # cokme olursa o ana kadarki etiketler kaybolmasin.
                        write_labels(labels_path, labels)
                        break
                    except (CdpError, WebSocketError, OSError) as exc:
                        last_error = str(exc)
                        target.unlink(missing_ok=True)
                        if attempt < args.retries:
                            print(f"    tekrar deneniyor ({last_error})")
                            time.sleep(1.5)
                else:
                    print(f"    HATA {last_error}")
                    failed.append((entry, last_error))
        write_labels(labels_path, labels)

    print("\n--- ozet ---")
    print(f"basarili : {len(ok)}")
    print(f"atlanan  : {len(skipped)} (zaten var; yeniden cekmek icin --force)")
    print(f"basarisiz: {len(failed)}")
    for entry, reason in failed:
        print(f"  {entry.id:28s} {reason[:90]}")
    print(f"etiketler: {labels_path} ({len(labels)} satir)")

    if args.push:
        adb = find_adb()
        files = [out_dir / row[1] for row in labels.values() if (out_dir / row[1]).exists()]
        print(f"\n{len(files)} dosya cihaza aktariliyor -> {DEVICE_SCREENSHOT_DIR}")
        for command in push_commands(adb, files):
            result = subprocess.run(command, capture_output=True)
            if result.returncode != 0:
                print(f"  hata: {subprocess.list2cmdline(command)} -> "
                      f"{result.stderr.decode(errors='replace').strip()[:120]}")
        print("Aktarim bitti. Uygulamayi acip taramayi baslatin.")


if __name__ == "__main__":
    main()
