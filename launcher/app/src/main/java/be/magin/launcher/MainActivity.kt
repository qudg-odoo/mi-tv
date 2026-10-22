package be.magin.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import be.magin.launcher.ui.LauncherApp

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)
    private var homeTick by mutableIntStateOf(0)
    private var foreground by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcher = AppLauncher(this)
        setContent { LauncherApp(resumeTick, homeTick, foreground, launcher) }
    }

    /** Home pressed while we're already in front: back to the home screen and the slideshow. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homeTick++
    }

    override fun onStart() {
        super.onStart()
        foreground = true
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    override fun onStop() {
        foreground = false
        super.onStop()
    }
}
