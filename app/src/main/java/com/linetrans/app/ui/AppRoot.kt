package com.linetrans.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.linetrans.app.data.SettingsRepository
import com.linetrans.app.ui.settings.SettingsAnchor
import com.linetrans.app.ui.settings.SettingsScreen

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val animate = SettingsRepository.settings.animations

    val enter: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
        if (animate) {
            slideInHorizontally(animationSpec = Motion.enter(Motion.SLOW)) { it / 3 } +
                fadeIn(animationSpec = Motion.enter(Motion.SLOW)) +
                scaleIn(initialScale = 0.98f, animationSpec = Motion.enter(Motion.SLOW))
        } else EnterTransition.None
    }
    val exit: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
        if (animate) {
            slideOutHorizontally(animationSpec = Motion.exit(Motion.MEDIUM)) { -it / 6 } +
                fadeOut(animationSpec = Motion.exit(Motion.MEDIUM)) +
                scaleOut(targetScale = 0.99f, animationSpec = Motion.exit(Motion.MEDIUM))
        } else ExitTransition.None
    }
    val popEnter: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
        if (animate) {
            slideInHorizontally(animationSpec = Motion.enter(Motion.SLOW)) { -it / 3 } +
                fadeIn(animationSpec = Motion.enter(Motion.SLOW)) +
                scaleIn(initialScale = 0.98f, animationSpec = Motion.enter(Motion.SLOW))
        } else EnterTransition.None
    }
    val popExit: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
        if (animate) {
            slideOutHorizontally(animationSpec = Motion.exit(Motion.MEDIUM)) { it / 6 } +
                fadeOut(animationSpec = Motion.exit(Motion.MEDIUM)) +
                scaleOut(targetScale = 0.99f, animationSpec = Motion.exit(Motion.MEDIUM))
        } else ExitTransition.None
    }

    NavHost(
        navController = nav,
        startDestination = "home",
        enterTransition = enter,
        exitTransition = exit,
        popEnterTransition = popEnter,
        popExitTransition = popExit
    ) {
        composable("home") {
            HomeScreen(
                onOpenDoc = { id, start, viewOnly ->
                    nav.navigate("translate/" + id + "?start=" + start + "&view=" + viewOnly)
                },
                onOpenSettings = { nav.navigate("settings") },
                onOpenWebServer = { nav.navigate("settings?anchor=" + SettingsAnchor.WEB_SERVER.name) }
            )
        }
        composable(
            route = "translate/{docId}?start={start}&view={view}",
            arguments = listOf(
                navArgument("docId") { type = NavType.StringType },
                navArgument("start") {
                    type = NavType.IntType
                    defaultValue = 0
                },
                navArgument("view") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { entry ->
            val docId = entry.arguments?.getString("docId") ?: ""
            val start = entry.arguments?.getInt("start") ?: 0
            val viewOnly = entry.arguments?.getBoolean("view") ?: false
            TranslationScreen(
                docId = docId,
                startIndex = start,
                viewOnly = viewOnly,
                onBack = { nav.popBackStack() }
            )
        }
        composable(
            route = "settings?anchor={anchor}",
            arguments = listOf(
                navArgument("anchor") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { entry ->
            SettingsScreen(
                onBack = { nav.popBackStack() },
                anchor = SettingsAnchor.fromParam(entry.arguments?.getString("anchor"))
            )
        }
    }
}
