package ru.begunok.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import ru.begunok.BegunokApp
import ru.begunok.ui.theme.BegunokTheme

class MainActivity : ComponentActivity() {
    private var stopRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setOnExitAnimationListener { splash ->
            splash.view.animate()
                .alpha(0f)
                .scaleX(1.15f)
                .scaleY(1.15f)
                .setDuration(SPLASH_EXIT_MS)
                .withEndAction { splash.remove() }
                .start()
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val container = (application as BegunokApp).container
        setContent {
            BegunokTheme {
                BegunokNavigation(
                    container = container,
                    stopRequested = stopRequested,
                    onStopRequestHandled = { stopRequested = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_CONFIRM_STOP, false) == true) {
            stopRequested = true
            intent.removeExtra(EXTRA_CONFIRM_STOP)
        }
    }

    companion object {
        private const val EXTRA_CONFIRM_STOP = "confirm_stop"
        private const val SPLASH_EXIT_MS = 300L

        fun intent(context: Context, confirmStop: Boolean): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_CONFIRM_STOP, confirmStop)
    }
}
