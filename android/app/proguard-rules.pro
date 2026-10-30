# WorkManager, Coil ve ML Kit gerekli R8 kurallarını kendi consumer rule dosyalarıyla sağlıyor.
# Aşağıdaki kurallar yalnızca UYGULAMA KODUNUN yansıma ile erişilen kısımları içindir.

# ViewModel yansıma ile oluşturuluyor:
#     ViewModelProvider(this)[ScreenshotSearchViewModel::class.java]
# R8 sınıf adını kısaltınca (örnek: ScreenshotSearchViewModel -> q11) ViewModelProvider
# (Application) yapıcısını bulamıyor ve uygulama AÇILIŞTA ÇÖKÜYOR:
#     java.lang.RuntimeException: Cannot create an instance of class q11
# Gerçek imzalı release kurulumunda yakalandı; debug build'de görünmüyordu. Dağıtımdan
# önce release APK'yı gerçek cihaza kurup açmadan bu sınıf hatalar görünmez.
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    <init>(...);
}

# ML Kit (OCR + görsel etiketleme) kendi consumer kurallarını sağlıyor ama R8 dahili
# başlatma sınıflarını buduyor ve ImageLabelProcessor yapıcısı NPE atıyordu:
#     NullPointerException: Attempt to read from field 'n64 h54.a' on a null object
#     (mapping: h54 = com.google.mlkit.vision.text.internal.zzo)
# Sonuç: uygulama açılışta çöküyordu. ML Kit iç sınıfları yansımayla kurulduğu için
# budanmamalı. Bu kural olmadan release build kullanılamaz.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**
