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

        val deepLinkSmsLogId = intent?.getLongExtra("sms_log_id", -1L)
        val deepLinkDestination = intent?.getStringExtra("destination")

        setContent {
            FireSMSTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    LaunchedEffect(deepLinkDestination, deepLinkSmsLogId) {
                        if (deepLinkDestination == "edit_transaction" && deepLinkSmsLogId != null && deepLinkSmsLogId > 0) {
                            navController.navigate(Routes.editTransaction(deepLinkSmsLogId))
                        }
                    }

                    // Always start at onboarding — OnboardingScreen auto-skips if done
                    NavGraph(navController = navController, startDestination = Routes.ONBOARDING)
                }
            }
        }
    }

}
