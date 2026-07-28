# Project Memento 0.2.0 — Search-first karar ve kabul raporu

Tarih: 28 Temmuz 2026

Durum: fiziksel cihaz kabulü geçti

## Neden yön değişti?

Memento'nun önceki yaklaşımı screenshot içeriğinden hem konu hem de kaydetme niyeti
üretmeye çalışıyordu. 300 ekranlık Enrico/RICO native benchmark'ı, OCR/metin tabanlı
politikanın gerçek telefon ekranlarında yeterince genellenmediğini gösterdi. MobileCLIP ve
metin füzyonu doğruluğu artırsa da Kategorisiz ekranlara fazla yanlış etiket verdi.

Bu nedenle 0.2.0'ın ürün sözleşmesi:

> Screenshot'ı bulmak zorunlu; otomatik kategori yalnız eminsek yardımcıdır.

## Yapılan değişiklikler

- Doğal dil sorgusundaki konu kelimeleri artık otomatik `topic = ...` filtresi olmaz.
- Arama indeksi OCR yanında çıkarılan başlığı ve kaynak ipucunu da içerir.
- Tarih ifadeleri bugün/dün, bu-geçen hafta/ay/yıl ve son 7/30 günü kapsar.
- Ham konu adayının üstüne yüksek kesinlikli öneri/abstention politikası eklendi.
- Instagram, Reddit, YouTube, video ve sosyal format sinyalleri koleksiyon sayılmaz.
- Görsel etiket tek başına koleksiyon üretemez.
- Otomatik `Git / Dene / Al / Oku / Hatırla` niyeti kaldırıldı.
- Manuel konu ve niyet düzeltmeleri korunur.
- Düzeltme hafızası ve benzer ekranlara otomatik yayılım kaldırıldı.
- Kullanılmayan text embedding/vectorizer kodu kaldırıldı.
- Deney konu modeli ürün assets klasöründen çıkarıldı; public Git'te model artefaktı tutulmaz.
- Ana ekran ve arama metinleri arama-first sözleşmesine göre yenilendi.
- Kategorisiz kayıtlar koleksiyon menüsünden gizlendi.

## Otomatik doğrulama

- JVM: 92/92 başarılı.
- Lint: 0 hata, 3 sürüm uyarısı.
- Debug APK: başarılı.
- İmzalı ve R8 ile küçültülmüş release APK: başarılı.
- Release APK boyutu: 44.640.216 bayt.

## Öneri politikası ölçümü

| Set | Kesinlik | Kapsam | Kategorisiz yanlış pozitif |
|---|---:|---:|---:|
| device-verified, n=19 | 9/9 — %100 | %47,4 | 0/1 |
| en-v1-holdout, n=48 | 26/27 — %96,3 | %56,3 | 0/1 |
| web-corpus-test, n=45 | 29/29 — %100 | %64,4 | n/a |
| web-holdout-v1, n=224 | 82/93 — %88,2 | %41,5 | 1/12 — %8,3 |
| native-ui-v1, n=300 | 13/19 — %68,4 | %6,3 | 1/96 — %1,0 |

Native setin düşük kapsamı bilinçlidir. Ürün, kanıtsız bir kategori göstermektense
Kategorisiz kalır. Native gold etiketlerinin bir bölümü uygulama mağazası kategorisidir ve
modern kişisel screenshot konusu ile birebir aynı değildir; sonuç ürün iddiası olarak değil,
regresyon sınırı olarak kullanılmalıdır.

## Galaxy A23 / Android 14 kabulü

- Cihaz: Samsung SM-A235F, Android 14.
- Önceki kurulum: 0.1.2 / versionCode 3.
- Kurulum: aynı sertifikayla `adb install -r`.
- Sonuç: başarılı, veri silinmedi.
- Yeni kurulum: 0.2.0 / versionCode 4.
- Kayıt: 19 öncesi, 19 sonrası.
- Soğuk açılış: 1.753 ms.
- `ayarlar`: 1 doğru sonuç.
- `bugün ayarlar`: 1 doğru sonuç.
- Film koleksiyonu: 1 etiketli kayıt.
- Ana ekran: arama hero'su, son eklenenler, yalnız dolu koleksiyonlar.
- Çökme gözlenmedi.

## Kalan risk

En önemli sıradaki iş yeni kategori/model eklemek değildir. 3–5 kişilik kapalı alfada
gerçek arama görev başarısını ölçmek ve çok sonuçlu sorgular için FTS/alaka sıralamasına
ihtiyaç olup olmadığını kanıtlamaktır.
