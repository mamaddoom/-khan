package com.mozkhan.shop.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PointOfSale
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import kotlinx.coroutines.launch
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mozkhan.shop.util.CrashLog

private data class Tab(val route: String, val label: String, val on: ImageVector, val off: ImageVector)

private val tabs = listOf(
    Tab("home", "خانه", Icons.Filled.Home, Icons.Outlined.Home),
    Tab("sell", "فروش", Icons.Filled.PointOfSale, Icons.Outlined.PointOfSale),
    Tab("products", "کالاها", Icons.Filled.Inventory2, Icons.Outlined.Inventory2),
    Tab("reports", "گزارش", Icons.Filled.QueryStats, Icons.Outlined.QueryStats),
    Tab("more", "بیشتر", Icons.Filled.MoreHoriz, Icons.Outlined.MoreHoriz),
)

fun editRoute(id: String? = null, barcode: String? = null, preset: String? = null, mode: String? = null) =
    "edit?id=${Uri.encode(id ?: "")}&barcode=${Uri.encode(barcode ?: "")}&preset=${Uri.encode(preset ?: "")}&mode=${Uri.encode(mode ?: "")}"

/** One navigation function for every tab switch (bottom bar AND in-page shortcuts) so state is saved/restored consistently. */
private fun NavHostController.goTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainVm, activity: Activity) {
    val ctx = LocalContext.current
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = route == null || tabs.any { it.route == route }

    // a "sales only" phone cannot define or edit goods (the main phone does that)
    val salesOnly by vm.prefs.salesOnly.collectAsState()
    val deny = { Toast.makeText(ctx, "این گوشی در حالت «فقط فروش» است؛ کالا را روی گوشی اصلی تعریف یا ویرایش کنید", Toast.LENGTH_LONG).show() }

    // ---- surface errors instead of dying silently
    val error by vm.error.collectAsState()
    LaunchedEffect(error) {
        error?.let { Toast.makeText(ctx, "خطا: $it", Toast.LENGTH_LONG).show(); vm.clearError() }
    }
    var crash by remember { mutableStateOf(CrashLog.read(ctx)) }
    crash?.let { text ->
        AlertDialog(
            onDismissRequest = { CrashLog.clear(ctx); crash = null },
            title = { Text("برنامه بار قبل با خطا بسته شد") },
            text = {
                androidx.compose.foundation.layout.Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text("متن زیر را کپی کنید و برای پشتیبان بفرستید تا مشکل رفع شود:", style = MaterialTheme.typography.bodySmall)
                    Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("crash", text))
                    Toast.makeText(ctx, "کپی شد", Toast.LENGTH_SHORT).show()
                }) { Text("کپی") }
            },
            dismissButton = { TextButton(onClick = { CrashLog.clear(ctx); crash = null }) { Text("بستن") } },
        )
    }

    // ---- new version found on GitHub Releases: offer it once per launch
    val upd by vm.updater.state.collectAsState()
    var updDismissed by remember { mutableStateOf(false) }
    if (upd.available && !updDismissed) {
        AlertDialog(
            onDismissRequest = { updDismissed = true },
            title = { Text("نسخهٔ جدید آماده است") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("نسخهٔ ${upd.latestName} منتشر شده است. اطلاعات شما با به‌روزرسانی حفظ می‌شود.")
                    if (upd.downloading) Text("در حال دانلود… ${(upd.progress * 100).toInt()}٪", Modifier.padding(top = 8.dp))
                    upd.message?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !upd.downloading, onClick = {
                    if (upd.downloaded != null) vm.updater.install()?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
                    else vm.updater.download()
                }) { Text(if (upd.downloaded != null) "نصب" else "دانلود و نصب") }
            },
            dismissButton = { TextButton(onClick = { updDismissed = true }) { Text("بعداً") } },
        )
    }
    LaunchedEffect(upd.downloaded) {
        if (upd.downloaded != null) vm.updater.install()?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
    }

    // pull down on any screen to sync the database now (like Instagram / YouTube)
    var refreshing by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val onPullRefresh: () -> Unit = {
        if (!refreshing) {
            refreshing = true
            scope.launch {
                // waits for the real upload (max ~25 s) instead of a fixed delay; never starts sync that was switched off
                val msg = try { vm.sync.refresh() } finally { refreshing = false }
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                tabs.forEach { t ->
                    val sel = route == t.route
                    NavigationBarItem(
                        selected = sel,
                        onClick = { nav.goTab(t.route) },
                        icon = { Icon(if (sel) t.on else t.off, t.label) },
                        label = { Text(t.label, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal) },
                    )
                }
            }
        },
    ) { pad ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onPullRefresh, modifier = Modifier.padding(pad).statusBarsPadding()) {
        NavHost(
            nav, startDestination = "home", modifier = Modifier,
            // no 700ms cross-fade between screens: switching tabs is instant
            enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None },
        ) {
            composable("home") {
                HomeScreen(vm, onGo = { nav.goTab(it) }, onNew = { if (salesOnly) deny() else nav.navigate(editRoute()) })
            }
            composable("sell") {
                SellScreen(vm, onDefine = { code -> if (salesOnly) deny() else nav.navigate(editRoute(barcode = code)) }, onDefinePreset = { p -> if (salesOnly) deny() else nav.navigate(editRoute(preset = p)) })
            }
            composable("products") {
                ProductsScreen(vm, onEdit = { id -> if (salesOnly) deny() else nav.navigate(editRoute(id = id)) }, onNew = { if (salesOnly) deny() else nav.navigate(editRoute()) }, canManage = !salesOnly)
            }
            composable("reports") { ReportsScreen(vm) }
            composable("more") { MoreScreen(vm, onGo = { r -> if (salesOnly && (r == "cafe" || r == "exits")) deny() else nav.navigate(r) { launchSingleTop = true } }) }
            composable("settings") { SettingsScreen(vm, activity, onBack = { nav.popBackStack() }) }
            composable("credit") { CreditScreen(vm, onBack = { nav.popBackStack() }) }
            composable("inventory") { InventoryScreen(vm, onBack = { nav.popBackStack() }, onEdit = { if (salesOnly) deny() else nav.navigate(editRoute(id = it)) }) }
            composable("exits") { ExitScreen(vm, onBack = { nav.popBackStack() }) }
            composable("cafe") { CafeScreen(vm, onBack = { nav.popBackStack() }) }
            composable(
                "edit?id={id}&barcode={barcode}&preset={preset}&mode={mode}",
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType; defaultValue = "" },
                    navArgument("barcode") { type = NavType.StringType; defaultValue = "" },
                    navArgument("preset") { type = NavType.StringType; defaultValue = "" },
                    navArgument("mode") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { e ->
                ProductEditScreen(
                    vm,
                    id = e.arguments?.getString("id").orEmpty().ifBlank { null },
                    barcode = e.arguments?.getString("barcode").orEmpty().ifBlank { null },
                    preset = e.arguments?.getString("preset").orEmpty().ifBlank { null },
                    mode = e.arguments?.getString("mode").orEmpty().ifBlank { null },
                    onClose = { nav.popBackStack() },
                    onOpen = { pid -> nav.popBackStack(); nav.navigate(editRoute(id = pid, mode = "restock")) },
                )
            }
        }
        // tiny server-status dot: green = connected, red = error, gray = sync off
        val ss by vm.sync.state.collectAsState()
        val dot = when {
            ss.error != null -> Color(0xFFE53935)
            ss.running -> Color(0xFF2ECC71)
            else -> Color(0xFF9E9E9E)
        }
        Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 6.dp).size(7.dp).clip(CircleShape).background(dot))
        }
    }
}
