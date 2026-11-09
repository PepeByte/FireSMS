package com.firesms.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.navigation.compose.rememberNavController
import com.firesms.app.ui.navigation.NavGraph
import com.firesms.app.ui.navigation.Routes
import com.firesms.app.service.DESTINATION_EDIT_TRANSACTION
import com.firesms.app.service.DESTINATION_SMS_LOG
import com.firesms.app.service.DESTINATION_UNPARSED
import com.firesms.app.service.EXTRA_SMS_LOG_ID
import com.firesms.app.ui.theme.FireSMSTheme

/** Where a notification asked the app to go. Each instance is handled once. */
private data class DeepLink(val destination: String, val smsLogId: Long?)

private fun Intent.toDeepLink(): DeepLink? {
    val destination = getStringExtra("destination") ?: return null
    val id = getLongExtra(EXTRA_SMS_LOG_ID, -1L).takeIf { it > 0 }
    return DeepLink(destination, id)
}

class MainActivity : ComponentActivity() {

    /** Null until DataStore answers; the splash screen stays up meanwhile instead of a blank frame. */
    private val onboardingCompletedState = MutableStateFlow<Boolean?>(null)

    /** Pending notification deep link; set on launch and by [onNewIntent] when the app is already open. */
    private val deepLinkState = MutableStateFlow<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        splash.setKeepOnScreenCondition { onboardingCompletedState.value == null }

        // Only follow the deep link on first creation, not again after a configuration change.
        if (savedInstanceState == null) deepLinkState.value = intent.toDeepLink()

        val preferences = (application as FireSmsApp).preferencesManager
        lifecycleScope.launch {
            onboardingCompletedState.value = preferences.isOnboardingCompleted()
        }

        setContent {
            val dynamicColor by preferences.dynamicColor.collectAsState(initial = false)
            FireSMSTheme(dynamicColor = dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Pick the start destination before building the graph. Starting at onboarding and
                    // skipping it asynchronously would pop a notification deep link off the back stack.
                    val onboardingCompleted by onboardingCompletedState.collectAsState()
                    onboardingCompleted?.let { completed ->
                        val navController = rememberNavController()

                        val deepLink by deepLinkState.collectAsState()
                        var detailRequestLogId by remember { mutableStateOf<Long?>(null) }

                        LaunchedEffect(deepLink) {
                            val link = deepLink ?: return@LaunchedEffect
                            deepLinkState.value = null
                            if (!completed) return@LaunchedEffect
                            when (link.destination) {
                                DESTINATION_EDIT_TRANSACTION -> link.smsLogId?.let {
                                    navController.navigate(Routes.editTransaction(it))
                                }
                                DESTINATION_UNPARSED -> navController.navigate(Routes.UNPARSED) {
                                    launchSingleTop = true
                                }
                                DESTINATION_SMS_LOG -> link.smsLogId?.let {
                                    navController.popBackStack(Routes.HOME, inclusive = false)
                                    detailRequestLogId = it
                                }
                            }
                        }

                        NavGraph(
                            navController = navController,
                            startDestination = if (completed) Routes.HOME else Routes.ONBOARDING,
                            detailRequestLogId = detailRequestLogId,
                            onDetailRequestHandled = { detailRequestLogId = null }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.toDeepLink()?.let { deepLinkState.value = it }
    }
}
