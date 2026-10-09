package app.raven.app

import android.app.Application
import app.raven.app.runtime.MeshRuntime
import app.raven.app.runtime.NoRadioTransport
import app.raven.app.runtime.Notifier
import app.raven.data.Storage

/** Creates the app's single [MeshRuntime] (simple manual wiring, D86). */
class RavenApplication : Application() {
    lateinit var runtime: MeshRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime =
            MeshRuntime(
                openStorage = { Storage.open(this) },
                // Bluetooth arrives in Phase 5; until then messages stay pending.
                transportFactory = { NoRadioTransport() },
                notifier = Notifier(this),
            )
        runtime.start()
    }
}
