# Screenshot Search — Proje Durumu

Son güncelleme: 28 Temmuz 2026

Kanonik dal: `main`

Uygulama sürümü: **0.2.0 (versionCode 4)**

Ürün aşaması: **çalışan Android MVP / kapalı alfa adayı**

Bu dosya projenin tek güncel durum kaynağıdır. Günlük değildir; yalnız mevcut ürün
sözleşmesini, doğrulanmış kanıtı, açık riski ve sıradaki çıkış kriterlerini tutar. Tarihsel
araştırmalar `outputs/` altındadır.

## 1. Yönetici özeti

Screenshot Search, telefondaki screenshot'ları otomatik keşfedip cihaz üzerinde OCR ile aranabilir
hale getiren kişisel bir screenshot hafızasıdır.

0.2.0 ile ürünün merkezi kararını değiştirdik:

> Ana vaat otomatik kategori değil, screenshot'ı hatırladığın parçayla bulmaktır.

Önceki sürüm her ekranı konu ve “neden kaydettin?” niyetiyle açıklamaya çalışıyordu. Gerçek
native ekran benchmark'ı bunun güvenilir olmadığını gösterdi. Yeni sürüm:

- OCR metni, çıkarılan başlık, kaynak ve tarihle arar;
- doğal dilde geçen “film”, “tarif”, “spor” gibi kelimeleri gizli kategori filtresine
  dönüştürmez;
- yalnız açık içerik kanıtı veya güvenilir dikey kaynak varsa koleksiyon önerir;
- düşük güvenli ekranı **Kategorisiz** bırakır;
- screenshot içeriğinden kullanıcının “al / git / izle / dene” niyetini uydurmaz;
- kullanıcının elle verdiği konu ve niyet etiketlerini korur fakat bu düzeltmeyi benzer
  kayıtlara otomatik yaymaz.

Bu karar projeyi mümkün olmayan “her screenshot'ı kusursuz sınıflandır” hedefinden çıkarıp
ölçülebilir ve faydalı bir arama ürününe dönüştürür.

## 2. Güncel ürün sözleşmesi

### Çekirdek döngü

> screenshot keşfi → cihazda OCR → aranabilir indeks → arama → isteğe bağlı etiket

### Çalışan MVP

- Android MediaStore üzerinden screenshot keşfi ve uygulama süreci yaşarken ContentObserver.
- Android 13+ tam veya seçili fotoğraf erişimi; Android 8–12 uyumluluğu.
- Bundled ML Kit OCR; başlık ve satır yerleşimi çıkarımı.
- Başlık + kaynak + OCR metni + tarih üzerinde yerel arama.
- “bugün”, “dün”, “bu/geçen hafta”, “bu/geçen ay”, “bu/geçen yıl” ve son 7/30 gün
  tarih ifadeleri.
- Güçlü kanıt bulunan ekranlarda otomatik konu önerisi; diğerlerinde Kategorisiz.
- Yalnız gerçekten kayıt içeren koleksiyonların ana ekran ve menüde gösterilmesi.
- Kullanıcının konu/niyet düzeltmesi, durum işlemleri ve geri alma.
- Hassas içerikte görsel ve OCR türevi metin maskeleme.
- 60 kayıtlık keyset sayfalama, tam ekran görüntüleme ve yakınlaştırma.
- İsteğe bağlı haftalık yerel hatırlatma.

### Bilerek kapsam dışında

- iOS istemcisi.
- Hesap, backend, bulut senkronizasyonu ve çoklu cihaz.
- Sunucuya screenshot/OCR gönderimi.
- Her screenshot için zorunlu otomatik kategori veya niyet.
- Mağaza yayını, ödeme ve kesin marka/paket kimliği.

## 3. Teknik gerçeklik

| Alan | Durum | Kanıt / sınır |
|---|---|---|
| Android uygulama | Çalışıyor | Kotlin + Compose, minSdk 26, targetSdk 37 |
| Yerel veritabanı | Şema v10 | OCR, arama metni, kullanıcı düzeltmesi ve hata durumu |
| Screenshot keşfi | Çalışıyor | Galaxy A23: arka planda 18→19 keşif daha önce doğrulandı |
| Arama | Ürünün ana yolu | OCR + başlık + kaynak + tarih; kategoriye bağımlı değil |
| Otomatik konu | Seçici öneri | Açık kanıt yoksa Kategorisiz |
| Otomatik niyet | Kapalı | Niyet yalnız kullanıcı seçerse anlamlı kabul edilir |
| Görsel etiketleme | Yardımcı sinyal | Tek başına koleksiyon açmaz |
| Deney konu modeli | Ürün/Git dışında | Yerelde üretilebilir; artefakt ignore edilir |
| Kullanıcı düzeltmesi | Kalıcı | Elle verilen alan yeniden sınıflandırmada korunur |
| Düzeltme yayılımı | Kaldırıldı | Yanlış örneğin kütüphaneye yayılma riski kapatıldı |
| iOS / backend | Yok | MVP sınırı |

Sınıflandırma akışı:

1. OCR metni yerleşim bilgisiyle arayüz gürültüsünden temizlenir.
2. Kural motoru ham bir konu adayı çıkarır.
3. Kaynak profili ve açık içerik kanıtı adayı doğrular.
4. Kanıt yetersizse sonuç Kategorisiz olur.
5. Arama, konu sonucundan bağımsız çalışır.

## 4. Gizlilik ve güven sınırı

- Uygulamanın `INTERNET` izni yoktur.
- Screenshot, OCR metni, arama indeksi ve etiketler uygulama tarafından dışarı yüklenmez.
- OCR cihaz üzerinde çalışır.
- Yardımcı görsel etiketleme modeli Play Services tarafından bir kez sağlanabilir; görsel
  işleme cihaz üzerindedir.
- Android backup kapalıdır (`allowBackup=false`).
- E-posta geri bildirimi yalnız kullanıcı eylemiyle taslak açar; screenshot/OCR eklemez.
- İmzalama anahtarı, ham kullanıcı verisi, özel eval setleri ve APK'lar Git dışındadır.

Kullanıcıya söylenebilecek doğru cümle:

> Screenshot Search screenshot'larını telefonunda aranabilir hale getirir. Açık kanıt varsa bir
> koleksiyon önerir; emin değilse etiket uydurmaz.

## 5. Doğrulama durumu

### Otomatik

- JVM birim testleri: **92/92 başarılı**.
- Android Lint: **0 hata, 3 sürüm uyarısı**.
- Debug build: başarılı.
- R8/resource shrinking uygulanmış imzalı release build: başarılı.
- Release APK: yaklaşık **44,64 MB**.
- Daha önce Pixel 7 / Android 14 AVD'de şema, migration, OCR retry ve 1.205 kayıt keyset
  sayfalama testleri **4/4** geçti.

### Galaxy A23 / Android 14 — 0.2.0 fiziksel kabul

- 0.1.2 üzerine aynı sertifikayla `install -r`: başarılı.
- Kayıt sayısı: **19 → 19**, veri kaybı yok.
- Soğuk açılış: **1.753 ms**, çökme yok.
- Paket: `versionName=0.2.0`, `versionCode=4`.
- `ayarlar`: 1 sonuç, doğru Ayarlar screenshot'ı.
- `bugün ayarlar`: tarih + OCR birlikte 1 doğru sonuç.
- Film koleksiyonu: yalnız etikete sahip 1 kaydı açtı.
- Ana ekran: arama vaadi önce, son eklenenler ikinci, koleksiyonlar aşağıda.
- Mevcut manuel konu/niyet düzeltmeleri korundu.

## 6. Sınıflandırma için dürüst ölçüm

Yeni ürün metriği “bütün ekranlarda doğruluk” değil, uygulamanın **etiket önerdiği
ekranlardaki kesinlik** ve Kategorisiz ekranlara yanlış etiket verme oranıdır.

| Sabit set | Öneri kesinliği | Öneri kapsamı | Kategorisiz yanlış pozitif |
|---|---:|---:|---:|
| 19 cihaz örneği | 9/9 — **%100** | %47,4 | 0/1 |
| İngilizce holdout | 26/27 — **%96,3** | %56,3 | 0/1 |
| Web corpus test | 29/29 — **%100** | %64,4 | n/a |
| Web holdout v1 | 82/93 — **%88,2** | %41,5 | 1/12 — %8,3 |
| 300 native Android UI | 13/19 — **%68,4** | **%6,3** | 1/96 — **%1,0** |

Native set eski Enrico/RICO ekranlarından ve kısmen uygulama mağazası kategorilerinden
türetilmiştir; modern kullanıcı arşivinin son sözü değildir. Yine de önceki politikanın
Kategorisiz yanlış pozitif oranı yaklaşık %13,5 iken yeni politika %1,0'a indi. Bedeli,
uygulamanın native ekranların çoğunda otomatik etiket önermemesidir; bu bilinçli davranıştır.

Başarısız model deneyleri ürüne alınmadı:

- char n-gram model native ekranda yeterli değildi;
- MobileCLIP zero-shot %44,3, OCR füzyonu %47,1'e ulaştı;
- füzyonun Kategorisiz yanlış pozitif oranı %44,4 olduğu için reddedildi.

## 7. Kapanan riskler

- Doğal dil kategori kelimesinin gizli ve hatalı filtreye dönüşmesi.
- Her ekrana zorunlu konu/niyet uydurma.
- Kişi adının karakter benzerliğiyle Spor gibi ilgisiz kategoriye gitmesi.
- Tek kullanıcı düzeltmesinin benzer görünen başka ekranlara otomatik yayılması.
- Kullanılmayan konu modeli ve embedding altyapısının ürün kodu/APK ile büyümesi.
- 250 kayıt tavanı, OCR retry döngüsü ve silinme/OCR hatasının karışması.
- Test verisi ile kişisel arşivin Git'e veya teslim paketine karışması.

## 8. Açık riskler ve sıradaki sıra

### P1 — kapalı alfa arama başarısı

3–5 hedef kullanıcıyla, kendi telefonlarında şu görevler ölçülmeli:

- belirli eski bir screenshot'ı serbest metinle bulma;
- tarih + hatırlanan kelimeyle bulma;
- yanlış/eksik OCR olduğunda ikinci sorguyu kurma;
- otomatik öneri ile manuel etiket ayrımını anlama;
- fotoğraf izni ve yerel işleme açıklamasına güvenme.

Ana metrikler: arama görev başarısı, ilk doğru sonuca süre, sorgu tekrar sayısı, yanlış öneri
oranı ve kullanıcı tarafından eklenen etiket oranı.

### P1 — arama sıralaması

Mevcut arama ayırt edici kelimeleri kaçırmamak için anlamlı terimleri OR ile eşleştirir ve
sonuçları tarihe göre sıralar. Büyük kütüphanede çok sonuçlu sorgular için FTS tabanlı
alaka sıralaması bir sonraki teknik iyileştirmedir; kapalı alfa sorgu günlükleri olmadan
eşik/heuristic eklenmemelidir.

### P1 — modern ekran doğrulaması

Profil, mesaj, reklam, ayarlar ve sosyal yüzeyler için özel screenshot toplamadan cihazda
yalnız anonim başarı/düzeltme sayısı çıkaran bir değerlendirme akışı gerekir. Yeni model,
native öneri kesinliği anlamlı kapsamda ≥%80 olmadan APK'ye eklenmemelidir.

### P2 — yayın hazırlığı

`Screenshot Search` düz çalışma adıdır (eski kod adı: Memento, 2026-10-30'da değişti). Marka araştırması, kesin paket kimliği, gizlilik politikası,
mağaza metinleri ve dağıtım kanalı kapalı alfa sinyali geldikten sonra tamamlanır.

Şimdilik yapılmaması gerekenler:

- kategori sayısını büyüterek hatayı örtmek;
- test kapısını geçmeyen embedding/modeli APK'ye eklemek;
- arama doğrulanmadan backend, hesap, bulut veya iOS yatırımı;
- web skorunu gerçek kullanıcı doğruluğu gibi sunmak.

## 9. Çalışma ve teslim düzeni

- GitHub: `batuantpc/screenshot-search`
- Kanonik dal: `main`
- `.private-data/`: ham/katılımcı veri, cihaz ekranları ve özel holdout; Git dışında.
- `android/keystore.properties`: imza sırları; Git dışında.
- `outputs/`: tarihli karar, araştırma ve kabul raporları.

## 10. İlgili kanıtlar

- `outputs/screenshot-search-0.2.0-search-first-kabul-2026-07-28.md`
