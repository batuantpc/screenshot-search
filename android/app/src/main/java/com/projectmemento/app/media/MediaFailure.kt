package com.projectmemento.app.media

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Bir medya URI'si üzerinde yapılan işin (OCR, görsel etiketleme) başarısızlık SINIFI.
 *
 * Neden ayrı bir kavram: hem OCR hem etiketleme kuyruğu eskiden `error is FileNotFoundException`
 * diye ayrım yapıyordu. Oysa görsel açılamadığında ML Kit
 * `IOException("The image URI couldn't be resolved.")` atıyor — FileNotFoundException DEĞİL.
 * Sonuçları:
 *  - OCR kuyruğu: kalıcı hata "geçici" sayılıp 3 kez denendi, her turda İngilizce ML Kit mesajı
 *    kullanıcının ekranına basıldı, sonunda kayıt BOŞ metinle sınıflandırıldı (kör tahmin).
 *  - Etiketleme kuyruğu: "model henüz inmedi" sanılıp kuyruk tamamen durdu; buluta boşaltılmış
 *    TEK bir görsel bütün backfill'i kalıcı olarak kilitliyordu.
 *
 * Exception mesajına asla bakmıyoruz: metin ML Kit sürümüne ve locale'e bağlı, sessizce değişir.
 * Bunun yerine URI'yi bizzat açmayı deneyip gerçeği ÖLÇÜYORUZ.
 */
sealed interface MediaFailure {
    val cause: Throwable

    /** Telemetri/teşhis için sabit kimlik — kullanıcıya gösterilmez, mesaj metnine bağlı değildir. */
    val kind: String

    /** Bir daha denemenin anlamı var mı? */
    val isPermanent: Boolean

    /**
     * Görsel artık okunamıyor: cihazdan silinmiş, buluta taşınıp yereli boşaltılmış
     * (Samsung Cloud / Google Photos "yer aç"), ya da MediaStore kaydı bayatlamış.
     */
    data class Unreadable(override val cause: Throwable) : MediaFailure {
        override val kind = "unreadable"
        override val isPermanent = true
    }

    /** Erişim izni geri çekilmiş ya da bu URI kısmi erişim kapsamı dışında kalmış. */
    data class AccessDenied(override val cause: Throwable) : MediaFailure {
        override val kind = "access_denied"
        override val isPermanent = true
    }

    /** Bellek darlığı, işleyici meşgul, geçici IO, model henüz inmemiş. Yeniden denenir. */
    data class Transient(override val cause: Throwable) : MediaFailure {
        override val kind = "transient"
        override val isPermanent = false
    }

    companion object {
        private const val LOG_TAG = "MementoMedia"

        /**
         * Hatanın kalıcı mı geçici mi olduğunu ÖLÇER (tahmin etmez).
         *
         * URI'yi açmayı dener: açılıyorsa sorun görselde değil, işlemede -> geçici.
         * Açılmıyorsa sebebini ayırt eder. Yalnızca hata yolunda çağrıldığı için maliyeti önemsiz.
         */
        fun classify(context: Context, contentUri: String, error: Throwable?): MediaFailure {
            val cause = error ?: IOException("İşlem başarısız (sebep bildirilmedi)")
            val probe = probe(context, contentUri)
            val failure = fromProbe(cause, probe)
            // Tam content URI loglanmaz. İçerik taşımasa da MediaStore kimliği kullanıcı
            // kütüphanesi hakkında gereksiz bir iz bırakır.
            Log.w(LOG_TAG, "Medya hatasi [${failure.kind}] probe=$probe", cause)
            return failure
        }

        internal fun fromProbe(cause: Throwable, probe: Probe): MediaFailure =
            when (probe) {
                Probe.READABLE -> Transient(cause)
                Probe.MISSING -> Unreadable(cause)
                Probe.DENIED -> AccessDenied(cause)
                Probe.UNKNOWN -> Transient(cause)
            }

        private fun probe(context: Context, contentUri: String): Probe = try {
            context.contentResolver.openInputStream(contentUri.toUri())
                ?.use { stream ->
                    // URI'nin yalnızca açılması bazı cloud provider'larda yeterli değildir;
                    // ilk okumayı da deneyerek gerçekten erişilebilir olduğunu doğrula.
                    stream.read()
                    Probe.READABLE
                }
                // openInputStream'in null donmesi "provider URI'yi cozemedi" demek: kayit artik yok.
                ?: Probe.MISSING
        } catch (error: FileNotFoundException) {
            Probe.MISSING
        } catch (error: SecurityException) {
            Probe.DENIED
        } catch (error: IllegalArgumentException) {
            // Bozuk/eski bicimli URI - bir daha denemenin anlami yok.
            Probe.MISSING
        } catch (error: IOException) {
            // Provider/network kaynaklı olabilir. Kalıcı olduğuna dair kanıt yok; sınırlı retry.
            Probe.UNKNOWN
        }

        internal enum class Probe { READABLE, MISSING, DENIED, UNKNOWN }
    }
}
