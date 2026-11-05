package ph.weatherapp.app

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import ph.weatherapp.core.engine.Lang
import java.util.Locale

/**
 * Premium status. Until the app is on the Play Store, the only way in is the
 * "Premium preview" switch (testing). Google Play Billing will set [isActive] later.
 */
object Premium {
    private const val FILE = "premium"

    /** Premium is on. Until Google Play Billing is connected, only test builds can turn it on (preview switch). */
    fun isActive(ctx: Context): Boolean =
        BuildConfig.PREMIUM_PREVIEW && ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("preview", false)

    fun setPreview(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("preview", on).apply()
    }
}

/**
 * Banner ad for the free version (main screen only, never on the alarm card).
 * Uses Google's TEST banner ID. Replace with your own AdMob ad unit before release.
 */
object Ads {
    const val BANNER_UNIT_ID = "ca-app-pub-3940256099942544/6300978111" // Google test banner
}

@Composable
fun BannerAd() {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(50.dp),
        factory = { ctx ->
            AdView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setAdSize(AdSize.BANNER)
                adUnitId = Ads.BANNER_UNIT_ID
                loadAd(AdRequest.Builder().build())
            }
        },
    )
}

/**
 * Reads the forecast aloud (premium) at alarm volume.
 * Filipino uses the phone's Filipino voice if installed; otherwise it falls back to English.
 */
class Speaker(ctx: Context) {
    private var ready = false
    private var pending: (() -> Unit)? = null
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        pending?.invoke()
        pending = null
    }

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
    }

    /** True if Filipino can be spoken; otherwise the caller should pass English text. */
    fun canSpeakFilipino(): Boolean {
        val r = tts.isLanguageAvailable(Locale("fil", "PH"))
        return r >= TextToSpeech.LANG_AVAILABLE
    }

    /** [script] builds the text for the language actually used (English if no Filipino voice is installed). */
    fun speak(lang: Lang, script: (Lang) -> String, whenDone: () -> Unit) {
        val go: () -> Unit = {
            if (!ready) whenDone() else {
                val useLang = if (lang == Lang.FIL && canSpeakFilipino()) Lang.FIL else Lang.EN
                tts.language = if (useLang == Lang.FIL) Locale("fil", "PH") else Locale.US
                val text = script(useLang)
                tts.setSpeechRate(0.95f)
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { main.post { whenDone() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { main.post { whenDone() } }
                })
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "forecast")
            }
        }
        if (ready) go() else pending = go
    }

    fun stop() = tts.stop()

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
