package eu.kanade.tachiyomi.ui.webview

import android.app.assist.AssistContent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.net.toUri
import eu.kanade.presentation.webview.WebViewScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animesource.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.logcat
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import okhttp3.HttpUrl.Companion.toHttpUrl
import uy.kohesive.injekt.injectLazy

class WebViewActivity : BaseActivity() {

    private val sourceManager: SourceManager by injectLazy()
    private val animeSourceManager: AnimeSourceManager by injectLazy()
    private val network: NetworkHelper by injectLazy()

    private var assistUrl: String? = null

    init {
        registerSecureActivity(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        overridePendingTransition(R.anim.shared_axis_x_push_enter, R.anim.shared_axis_x_push_exit)
        super.onCreate(savedInstanceState)

        if (!WebViewUtil.supportsWebView(this)) {
            toast(R.string.information_webview_required, Toast.LENGTH_LONG)
            finish()
            return
        }

        val url = intent.extras!!.getString(URL_KEY) ?: return
        var headers = mutableMapOf<String, String>()
        val source = sourceManager.get(intent.extras!!.getLong(SOURCE_KEY)) as? HttpSource
        val animeSource = animeSourceManager.get(intent.extras!!.getLong(SOURCE_KEY)) as? AnimeHttpSource
        if (source != null) {
            headers = source.headers.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()
        } else if (animeSource != null) {
            headers = animeSource.headers.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()
        }

        setComposeContent {
            WebViewScreen(
                onNavigateUp = { finish() },
                initialTitle = intent.extras?.getString(TITLE_KEY),
                url = url,
                headers = headers,
                onUrlChange = { assistUrl = it },
                onShare = this::shareWebpage,
                onOpenInBrowser = this::openInBrowser,
                onClearCookies = this::clearCookies,
            )
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        assistUrl?.let { outContent.webUri = it.toUri() }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.shared_axis_x_pop_enter, R.anim.shared_axis_x_pop_exit)
    }

    private fun shareWebpage(url: String) {
        try {
            startActivity(url.toUri().toShareIntent(this, type = "text/plain"))
        } catch (e: Exception) {
            toast(e.message)
        }
    }

    private fun openInBrowser(url: String) {
        openInBrowser(url, forceDefaultBrowser = true)
    }

    private fun clearCookies(url: String) {
        val cleared = network.cookieManager.remove(url.toHttpUrl())
        logcat { "Cleared $cleared cookies for: $url" }
    }

    companion object {
        private const val URL_KEY = "url_key"
        private const val SOURCE_KEY = "source_key"
        private const val TITLE_KEY = "title_key"
        private const val ANIME_KEY = "anime_key"

        fun newIntent(context: Context, url: String, sourceId: Long? = null, title: String? = null, isAnime: Boolean = false): Intent {
            return Intent(context, WebViewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(URL_KEY, url)
                putExtra(SOURCE_KEY, sourceId)
                putExtra(TITLE_KEY, title)
                putExtra(ANIME_KEY, isAnime)
            }
        }
    }
}
