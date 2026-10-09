package app.huginn.app

import android.app.Application
import app.huginn.app.runtime.MeshRuntime
import app.huginn.app.runtime.NoRadioTransport
import app.huginn.app.runtime.Notifier
import app.huginn.data.Storage

/** Creates the app's single [MeshRuntime] (simple manual wiring, D86). */
class HuginnApplication : Application() {
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
