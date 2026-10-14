package com.firesms.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.firesms.app.ui.navigation.NavGraph
import com.firesms.app.ui.navigation.Routes
import com.firesms.app.ui.theme.FireSMSTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Only follow the deep link on first creation, not again after a configuration change.
        val launchIntent = intent.takeIf { savedInstanceState == null }
        val deepLinkSmsLogId = launchIntent?.getLongExtra("sms_log_id", -1L)
        val deepLinkDestination = launchIntent?.getStringExtra("destination")

        val preferences = (application as FireSmsApp).preferencesManager

        setContent {
            FireSMSTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Pick the start destination before building the graph. Starting at onboarding and
                    // skipping it asynchronously would pop a notification deep link off the back stack.
                    val onboardingCompleted by produceState<Boolean?>(null) {
                        value = preferences.isOnboardingCompleted()
                    }
                    onboardingCompleted?.let { completed ->
                        val navController = rememberNavController()

                        LaunchedEffect(deepLinkDestination, deepLinkSmsLogId) {
                            if (completed && deepLinkDestination == "edit_transaction" &&
                                deepLinkSmsLogId != null && deepLinkSmsLogId > 0
                            ) {
                                navController.navigate(Routes.editTransaction(deepLinkSmsLogId))
                            }
                        }

                        NavGraph(
                            navController = navController,
                            startDestination = if (completed) Routes.HOME else Routes.ONBOARDING
                        )
                    }
                }
            }
        }
    }

}
