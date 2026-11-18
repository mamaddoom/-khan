package com.mozkhan.shop

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mozkhan.shop.ui.AppRoot
import com.mozkhan.shop.ui.MainVm
import com.mozkhan.shop.ui.ShopTheme
import com.mozkhan.shop.util.Notifier
import com.mozkhan.shop.util.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) routeFrom(intent)
        setContent {
            val vm: MainVm = viewModel()
            val mode by vm.prefs.theme.collectAsState()
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            ShopTheme(dark = dark) { AppRoot(vm, this) }
        }
    }

    /** a tapped notification (cheques due, low stock) while the app is already open */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeFrom(intent)
    }

    private fun routeFrom(i: Intent?) {
        i?.getStringExtra(Notifier.EXTRA_ROUTE)?.let { Notifier.pendingRoute.value = it }
    }
}
