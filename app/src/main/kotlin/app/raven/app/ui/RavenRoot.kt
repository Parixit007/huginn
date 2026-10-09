package app.raven.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.raven.app.runtime.MeshRuntime
import app.raven.app.runtime.RuntimeState
import app.raven.app.ui.chat.ChatScreen
import app.raven.app.ui.chats.ChatsScreen
import app.raven.app.ui.common.deviceIdFromRoute
import app.raven.app.ui.common.route
import app.raven.app.ui.contact.ContactScreen
import app.raven.app.ui.contact.ProfileScreen
import app.raven.app.ui.onboarding.OnboardingScreen
import app.raven.app.ui.pairing.ScanScreen
import app.raven.app.ui.pairing.ShowQrScreen
import app.raven.app.ui.theme.RavenTheme

/** The whole app UI. Takes the runtime as a parameter so UI tests can run it on the fake transport. */
@Composable
fun RavenRoot(runtime: MeshRuntime) {
    RavenTheme {
        val state by runtime.state.collectAsStateWithLifecycle()
        when (state) {
            RuntimeState.Loading -> {
                Surface(Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }

            RuntimeState.NeedsOnboarding -> {
                OnboardingScreen(runtime)
            }

            is RuntimeState.Ready -> {
                MainNavigation(runtime)
            }
        }
    }
}

@Composable
private fun MainNavigation(runtime: MeshRuntime) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "chats") {
        composable("chats") {
            ChatsScreen(
                runtime,
                openChat = { nav.navigate("chat/${it.route()}") },
                showQr = { nav.navigate("showQr") },
                scanQr = { nav.navigate("scan") },
                openProfile = { nav.navigate("profile") },
            )
        }
        composable("chat/{peer}") { entry ->
            val peer = deviceIdFromRoute(checkNotNull(entry.arguments?.getString("peer")))
            ChatScreen(
                runtime,
                peer,
                back = { nav.popBackStack() },
                openContact = { nav.navigate("contact/${peer.route()}") },
            )
        }
        composable("contact/{peer}") { entry ->
            val peer = deviceIdFromRoute(checkNotNull(entry.arguments?.getString("peer")))
            ContactScreen(
                runtime,
                peer,
                back = { nav.popBackStack() },
                deleted = { nav.popBackStack("chats", inclusive = false) },
                pairAgain = { nav.navigate("showQr") },
            )
        }
        composable("showQr") { ShowQrScreen(runtime, done = { nav.popBackStack("chats", inclusive = false) }) }
        composable("scan") { ScanScreen(runtime, done = { nav.popBackStack("chats", inclusive = false) }) }
        composable("profile") { ProfileScreen(runtime, back = { nav.popBackStack() }) }
    }
}
