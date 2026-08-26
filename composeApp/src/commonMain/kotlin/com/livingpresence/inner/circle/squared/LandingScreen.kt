package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.livingpresence.inner.circle.squared.discord.DiscordApi
import com.livingpresence.inner.circle.squared.discord.DiscordAuthBroker
import com.livingpresence.inner.circle.squared.discord.DiscordConnectionState
import com.livingpresence.inner.circle.squared.discord.DiscordConnectionViewModel
import com.livingpresence.inner.circle.squared.discord.NOT_ON_APOLLO_MESSAGE
import com.livingpresence.inner.circle.squared.discord.rememberDiscordAuthLauncher
import com.livingpresence.inner.circle.squared.discord.rememberDiscordSessionStore

/** Discord's brand "Blurple", so the connect button reads as a Discord action. */
private val DiscordBlurple = Color(0xFF5865F2)
private val ScrimTop = Color(0x33000000)
private val ScrimBottom = Color(0xCC000000)

/**
 * Landing destination: the garden photo, a single "Connect to Discord" button,
 * and whatever the gate has to say about the result.
 *
 * This is the state-holder half — it owns the ViewModel, feeds it redirects
 * arriving from outside composition, and reports success upward. [LandingScreen]
 * below is pure UI over the resulting state.
 *
 * @param onConnected Invoked once the account is verified as an Apollo member.
 */
@Composable
fun LandingRoute(onConnected: () -> Unit) {
    val viewModel = rememberDiscordConnectionViewModel()
    val state by viewModel.state.collectAsState()
    val launchAuthorization = rememberDiscordAuthLauncher()

    // The redirect is published by a platform entry point (Android deep link, the
    // wasm page URL) rather than by this screen, so it is collected here instead
    // of inside the ViewModel — no long-lived collector on a scope that is never
    // cleared, and the token is consumed exactly once.
    val pendingRedirect by DiscordAuthBroker.pendingRedirect.collectAsState()
    LaunchedEffect(pendingRedirect) {
        pendingRedirect?.let(viewModel::onRedirect)
    }

    // Resume a previous sign-in, if there is one. Idempotent in the ViewModel, so
    // a recomposition cannot start a second refresh.
    LaunchedEffect(viewModel) {
        viewModel.restoreSession()
    }

    LaunchedEffect(state) {
        if (state is DiscordConnectionState.Connected) {
            onConnected()
        }
    }

    LandingScreen(
        state = state,
        onConnect = { viewModel.beginConnect()?.let(launchAuthorization) },
        onTryAgain = viewModel::reset,
    )
}

@Composable
private fun rememberDiscordConnectionViewModel(): DiscordConnectionViewModel {
    // Plain `remember` for the same reason as `rememberMainViewModel`: the
    // lifecycle-viewmodel-compose factory requires a SavedStateRegistryOwner,
    // which the app's manual ViewModelStoreOwner does not provide (fatal under
    // Kotlin/Wasm). This ViewModel holds no SavedState either.
    val api = remember { DiscordApi(createHttpClient()) }
    val sessionStore = rememberDiscordSessionStore()
    return remember(api, sessionStore) { DiscordConnectionViewModel(api, sessionStore) }
}

/**
 * The landing page: [loginBackgroundModifier] paints `background_image` behind a
 * legibility scrim, with the Discord gate stacked in the lower half.
 *
 * @param state What the gate currently shows.
 * @param onConnect Start (or restart) Discord authorization.
 * @param onTryAgain Clear a failed/denied result and return to the connect button.
 * @param modifier Layout modifier from the caller.
 */
@Composable
fun LandingScreen(
    state: DiscordConnectionState,
    onConnect: () -> Unit,
    onTryAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .then(loginBackgroundModifier()),
    ) {
        // The photo is bright and busy at the top; without a scrim the white
        // title and the status text below are unreadable over the foliage.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(ScrimTop, ScrimBottom))),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(
                text = "Inner Circle Squared",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Live events for the Apollo community",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(32.dp))

            DiscordGate(
                state = state,
                onConnect = onConnect,
                onTryAgain = onTryAgain,
            )
        }
    }
}

/** The part of the landing page that changes as authorization progresses. */
@Composable
private fun DiscordGate(
    state: DiscordConnectionState,
    onConnect: () -> Unit,
    onTryAgain: () -> Unit,
) {
    when (state) {
        DiscordConnectionState.Disconnected -> ConnectButton(
            text = "Connect to Discord",
            onClick = onConnect,
        )

        is DiscordConnectionState.RestoringSession -> ProgressStatus(
            message = if (state.displayName.isBlank()) {
                "Signing you back in…"
            } else {
                "Welcome back, ${state.displayName}…"
            },
            action = null,
            onAction = onTryAgain,
        )

        DiscordConnectionState.AwaitingAuthorization -> ProgressStatus(
            message = "Waiting for Discord…",
            action = "Cancel",
            onAction = onTryAgain,
        )

        DiscordConnectionState.Verifying -> ProgressStatus(
            message = "Checking your Apollo membership…",
            action = null,
            onAction = onTryAgain,
        )

        is DiscordConnectionState.Connected -> ProgressStatus(
            message = "Connected as ${state.displayName}. Opening the feed…",
            action = null,
            onAction = onTryAgain,
        )

        DiscordConnectionState.NotOnApolloServer -> {
            StatusCard(text = NOT_ON_APOLLO_MESSAGE, emphasis = true)
            Spacer(modifier = Modifier.height(16.dp))
            ConnectButton(text = "Try another account", onClick = onConnect)
        }

        is DiscordConnectionState.Failed -> {
            StatusCard(text = state.message, emphasis = false)
            Spacer(modifier = Modifier.height(16.dp))
            ConnectButton(text = "Try again", onClick = onConnect)
        }
    }
}

@Composable
private fun ConnectButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        // widthIn *before* fillMaxWidth: the other order hands the child a fixed
        // parent-width constraint that widthIn can no longer narrow.
        modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = DiscordBlurple,
            contentColor = Color.White,
        ),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Text(text = text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ProgressStatus(message: String, action: String?, onAction: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = Color.White,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            TextButton(onClick = onAction) {
                Text(text = action, color = Color.White)
            }
        }
    }
}

@Composable
private fun StatusCard(text: String, emphasis: Boolean) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
