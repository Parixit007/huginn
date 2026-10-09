package app.raven.app

import android.app.Application
import app.raven.app.runtime.AndroidRadioHost
import app.raven.app.runtime.MeshRuntime
import app.raven.app.runtime.Notifier
import app.raven.data.Storage
import app.raven.transport.ble.BleTransport
import java.security.SecureRandom

/** Creates the app's single [MeshRuntime] (simple manual wiring, D86). */
class RavenApplication : Application() {
    lateinit var runtime: MeshRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime =
            MeshRuntime(
                openStorage = { Storage.open(this) },
                transportFactory = { scheduler, post ->
                    val random = SecureRandom()
                    BleTransport(
                        context = this,
                        scheduler = scheduler,
                        post = post,
                        randomBytes = { size -> ByteArray(size).also(random::nextBytes) },
                        onStatus = { runtime.radio.onStatus(it) },
                    )
                },
                notifier = Notifier(this),
                host = AndroidRadioHost(this),
            )
        runtime.start()
    }
}
