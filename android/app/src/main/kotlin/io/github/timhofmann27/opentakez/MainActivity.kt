package io.github.timhofmann27.opentakez

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.timhofmann27.opentakez.ui.AppRoot
import io.github.timhofmann27.opentakez.ui.ShareIntents
import io.github.timhofmann27.opentakez.ui.ports.AppServices
import io.github.timhofmann27.opentakez.ui.ports.AppServicesProvider
import io.github.timhofmann27.opentakez.ui.ports.ShareInbox
import io.github.timhofmann27.opentakez.ui.theme.OpenTakezTheme

/** Survives rotation, so a share that arrived once is not read again. */
class MainViewModel : ViewModel() {
    val shareInbox = ShareInbox()
}

// AppCompatActivity (not ComponentActivity) so per-app locales also work before Android 13.
class MainActivity : AppCompatActivity() {
    private lateinit var shareInbox: ShareInbox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        shareInbox = ViewModelProvider(this)[MainViewModel::class.java].shareInbox
        // After a rotation the intent is the one already handled; only a fresh launch reads it.
        if (savedInstanceState == null) ShareIntents.handle(intent, shareInbox)

        // The application must provide real engines and storage; UI fakes are for tests/previews.
        val services: AppServices = checkNotNull((application as? AppServicesProvider)?.services) {
            "OpenTakez application must provide AppServices"
        }
        setContent {
            OpenTakezTheme {
                AppRoot(services = services, shareInbox = shareInbox)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ShareIntents.handle(intent, shareInbox)
    }
}
