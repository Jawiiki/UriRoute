package com.uriroute

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.uriroute.data.JsRepository
import com.uriroute.model.JsScript
import com.uriroute.ui.screen.EditorScreen
import com.uriroute.ui.screen.OverviewScreen
import com.uriroute.ui.screen.SettingsScreen

enum class Tab(val label: String, val icon: String) {
    OVERVIEW("概览", "◉"),
    EDITOR("JS编辑器", "✎"),
    SETTINGS("设置", "⚙")
}

@Composable
fun MainScreen(repository: JsRepository) {
    var selectedTab by remember { mutableStateOf(Tab.OVERVIEW) }
    var hideBottomNav by remember { mutableStateOf(false) }

    // Script to navigate to from overview -> editor
    var navigateToScript by remember { mutableStateOf<JsScript?>(null) }

    // Handle navigation: overview -> editor tab with specific script
    LaunchedEffect(navigateToScript) {
        if (navigateToScript != null) {
            selectedTab = Tab.EDITOR
        }
    }

    // System back button: non-概览 tabs → go to 概览; 概览 tab → exit app
    BackHandler(enabled = selectedTab != Tab.OVERVIEW) {
        selectedTab = Tab.OVERVIEW
    }

    Scaffold(
        bottomBar = {
            if (!hideBottomNav) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            icon = {
                                androidx.compose.material3.Text(
                                    text = tab.icon,
                                    fontSize = 18.sp
                                )
                            },
                            label = { Text(tab.label) },
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                Tab.OVERVIEW -> OverviewScreen(
                    repository = repository,
                    onNavigateToEditor = { script ->
                        navigateToScript = script
                    }
                )
                Tab.EDITOR -> {
                    // Consume navigateToScript after passing it to EditorScreen
                    val initialScript = navigateToScript
                    if (navigateToScript != null) {
                        // Clear after reading so re-selecting EDITOR tab shows the listing
                        LaunchedEffect(Unit) {
                            navigateToScript = null
                        }
                    }
                    EditorScreen(
                        repository = repository,
                        initialScript = initialScript,
                        onEditorFullscreenChanged = { fullscreen ->
                            hideBottomNav = fullscreen
                        }
                    )
                }
                Tab.SETTINGS -> SettingsScreen(repository)
            }
        }
    }
}
