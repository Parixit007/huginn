package app.raven.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.raven.app.ui.RavenRoot

class MainActivity : ComponentActivity() {
    private val runtime get() = (application as RavenApplication).runtime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RavenRoot(runtime) }
    }

    override fun onStart() {
        super.onStart()
        // On screen: scan continuously (D98), and re-check Bluetooth/permissions/Location the user may have changed.
        runtime.radio.setForeground(true)
        runtime.radio.refresh()
    }

    override fun onStop() {
        runtime.radio.setForeground(false)
        super.onStop()
    }
}
