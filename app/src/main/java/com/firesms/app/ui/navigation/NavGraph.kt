package com.firesms.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.firesms.app.ui.screens.*

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val RULES = "rules"
    const val RULE_EDIT = "rule_edit/{ruleId}?smsLogId={smsLogId}"
    const val UNPARSED = "unparsed"
    const val SETTINGS = "settings"
    const val EDIT_TRANSACTION = "edit_transaction/{smsLogId}"
    const val TITLE_MAPPINGS = "title_mappings"

    fun ruleEdit(ruleId: String) = "rule_edit/$ruleId"
    fun newRule() = "rule_edit/new"
    fun newRuleFromSms(smsLogId: Long) = "rule_edit/new?smsLogId=$smsLogId"
    fun editTransaction(smsLogId: Long) = "edit_transaction/$smsLogId"
}

@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String = Routes.ONBOARDING
) {
    val navigateTopLevel: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(Routes.HOME) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToRules = { navigateTopLevel(Routes.RULES) },
                onNavigateToSettings = { navigateTopLevel(Routes.SETTINGS) },
                onNavigateToUnparsed = { navController.navigate(Routes.UNPARSED) },
                onNavigateToEditTransaction = { smsLogId ->
                    navController.navigate(Routes.editTransaction(smsLogId))
                }
            )
        }
        composable(Routes.RULES) {
            RulesScreen(
                onNavigateToEdit = { ruleId -> navController.navigate(Routes.ruleEdit(ruleId)) },
                onNavigateToNew = { navController.navigate(Routes.newRule()) },
                onNavigateToActivity = { navigateTopLevel(Routes.HOME) },
                onNavigateToSettings = { navigateTopLevel(Routes.SETTINGS) }
            )
        }
        composable(
            route = Routes.RULE_EDIT,
            arguments = listOf(
                navArgument("smsLogId") {
                    type = NavType.LongType
                    defaultValue = -1L
                }
            )
        ) { backStackEntry ->
            val ruleId = backStackEntry.arguments?.getString("ruleId")
            val sourceSmsLogId = backStackEntry.arguments?.getLong("smsLogId")?.takeIf { it > 0 }
            RuleEditScreen(
                ruleId = if (ruleId == "new") null else ruleId,
                sourceSmsLogId = sourceSmsLogId,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.UNPARSED) {
            UnparsedScreen(
                onNavigateToRule = { smsLogId ->
                    navController.navigate(Routes.newRuleFromSms(smsLogId))
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateToActivity = { navigateTopLevel(Routes.HOME) },
                onNavigateToAutomation = { navigateTopLevel(Routes.RULES) }
            )
        }
        composable(Routes.EDIT_TRANSACTION) { backStackEntry ->
            val smsLogId = backStackEntry.arguments?.getString("smsLogId")?.toLongOrNull()
            EditTransactionScreen(
                smsLogId = smsLogId,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.TITLE_MAPPINGS) {
            TitleMappingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
