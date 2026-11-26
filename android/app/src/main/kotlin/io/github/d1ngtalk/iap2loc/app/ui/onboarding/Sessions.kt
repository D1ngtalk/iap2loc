package io.github.d1ngtalk.iap2loc.app.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.d1ngtalk.iap2loc.app.engine.EngineHost
import io.github.d1ngtalk.iap2loc.engine.RunSession
import io.github.d1ngtalk.iap2loc.protocol.IdentificationState
import kotlinx.coroutines.delay

/** An immutable copy of a session for the UI. */
data class SessionView(
    val connecting: Boolean,
    val running: Boolean,
    val link: String,
    val identification: IdentificationState?,
    val authenticated: Boolean,
    val authenticationFailed: Boolean,
    val hasIdentity: Boolean,
    val subscribed: Boolean,
    val sent: Int,
    val error: String?,
) {
    /** 0 nothing, 1 RFCOMM up, 2 iAP2 link, 3 identified, 4 authenticated, 5 iPhone subscribed. */
    val progress: Int
        get() = when {
            subscribed -> 5
            authenticated -> 4
            identification == IdentificationState.ACCEPTED -> 3
            link == "Normal" || identification == IdentificationState.REJECTED -> 2
            link != "Idle" -> 1
            else -> 0
        }

    companion object {
        fun of(session: RunSession) = SessionView(
            session.isConnecting, session.isRunning, session.linkState, session.identification, session.authenticated,
            session.authenticationFailed, session.hasIdentity, session.subscribed, session.sent, session.lastError,
        )
    }
}

/** The newest session for this iPhone, from the console's engine. */
fun findSession(address: String?): RunSession? =
    address?.let { mac -> EngineHost.engine?.manager?.list()?.lastOrNull { it.request.mac.equals(mac, ignoreCase = true) } }

/** Polls the session for [address] a few times per second while the step is on screen. */
@Composable
fun rememberSessionView(address: String?): SessionView? {
    var view by remember { mutableStateOf<SessionView?>(null) }
    LaunchedEffect(address) {
        while (true) {
            view = findSession(address)?.let(SessionView::of)
            delay(400)
        }
    }

    return view
}
