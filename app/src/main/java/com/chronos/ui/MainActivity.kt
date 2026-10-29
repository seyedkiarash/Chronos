package com.chronos.ui

import com.chronos.ui.common.*
import com.chronos.ui.setup.*
import com.chronos.ui.horizon.*
import com.chronos.ui.day.*
import com.chronos.ui.log.*
import com.chronos.ui.navigator.*

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    private val vm: ChronosViewModel by viewModels()
    private val nav: NavigatorViewModel by viewModels()

    /** Text another app shared with Chronos: a chat's reply for the question waiting for it. */
    private fun received(intent: android.content.Intent?) {
        // A log reminder: open the Log.
        if (intent?.getStringExtra("open") == "log") { vm.openTab = "log"; intent.removeExtra("open") }
        val text = when (intent?.action) {
            android.content.Intent.ACTION_SEND -> intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
            android.content.Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(android.content.Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        if (!text.isNullOrBlank()) nav.receiveShared(text)
        // Handled once: turning the phone must not deliver it again.
        intent?.action = null
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        received(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) received(intent)
        com.chronos.auto.Reminders.schedule(this)
        com.chronos.auto.QuickLog.onChange = { runOnUiThread { vm.refreshQuick() } }
        setContent {
            val vm = this.vm
            // Persian is written right to left, so the whole tree is mirrored:
            // labels, the navigation bar, the direction text flows in a Row.
            CompositionLocalProvider(
                LocalLang provides vm.lang,
                LocalLayoutDirection provides
                    if (vm.lang.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                ChronosTheme(vm.themeMode) { ChronosApp(vm, nav) }
            }
        }
    }

    /** A day may have ended while the app sat in the background. */
    override fun onResume() {
        super.onResume()
        vm.onResume()
        vm.refreshQuick()
        vm.notificationsAllowed = com.chronos.auto.Reminders.allowed(this)
    }
}

private enum class Tab(val label: String) {
    Horizon("Horizon"), Day("Day"), Priorities("Priorities"), Setup("Setup"), Log("Log"),
    Navigator("Navigator")
}

/** The step bar and the day plot are drawn left to right whatever the language. */
@Composable
fun Ltr(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)

@Composable
fun ChronosApp(vm: ChronosViewModel = viewModel(), nav: NavigatorViewModel = viewModel()) {
    var tab by remember { mutableStateOf(Tab.Setup) }
    val snackbar = remember { SnackbarHostState() }

    // The first thing a new user sees is Setup, because nothing is seeded and
    // there is nothing to look at until they have filled it in. A returning
    // user with a ready configuration lands on Horizon instead. This runs once,
    // when loading finishes — never while the user is editing, or the last
    // answer that unlocks the solver would throw them out of Setup.
    LaunchedEffect(vm.loading) { if (!vm.loading && vm.ready && tab == Tab.Setup) tab = Tab.Horizon }
    LaunchedEffect(vm.openTab) {
        when (vm.openTab) { "log" -> tab = Tab.Log; "setup" -> tab = Tab.Setup; "priorities" -> tab = Tab.Priorities }
        vm.openTab = null
    }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(translate(vm.lang, it)); vm.message = null }
    }
    // A chat's reply shared to Chronos: show the Navigator, where its package appears.
    LaunchedEffect(nav.showNavigator) {
        if (nav.showNavigator) { tab = Tab.Navigator; nav.page = NavPage.HOME; nav.openProposal = null; nav.showNavigator = false }
    }
    LaunchedEffect(nav.message) {
        nav.message?.let { snackbar.showSnackbar(translate(vm.lang, it)); nav.message = null }
    }

    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val pending = nav.proposals.count { it.status() == "PENDING" } + nav.syncDiffs.size

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(260.dp)) {
                Text("Chronos", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp))
                Tab.entries.forEach { t ->
                    val locked = !vm.ready && (t == Tab.Horizon || t == Tab.Day)
                    NavigationDrawerItem(
                        label = {
                            Text(t.label, color = if (locked) MaterialTheme.colorScheme.outline
                                                  else MaterialTheme.colorScheme.onSurface)
                        },
                        selected = tab == t,
                        onClick = {
                            if (!locked) tab = t
                            // The Navigator opens on its home, whatever page was left open.
                            if (t == Tab.Navigator) { nav.page = NavPage.HOME; nav.openProposal = null }
                            scope.launch { drawer.close() }
                        },
                        badge = if (t == Tab.Navigator && pending > 0) {
                            { Badge { androidx.compose.material3.Text(pending.toString()) } }
                        } else null,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                if (!vm.ready) Text("Horizon and Day open once Setup is complete.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            }
        }
    ) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface(tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { drawer.open() } }) { MenuGlyph() }
                    Text(tab.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (pending > 0 && tab != Tab.Navigator)
                        TextButton(onClick = { tab = Tab.Navigator }) {
                            Text(translate(vm.lang, "Navigator") + " · " + pending,
                                style = MaterialTheme.typography.labelMedium)
                        }
                }
            }
        }
    ) { padding ->
        val scroll = rememberScrollState()
        // A proposal opened on its own page, or a page closed again, starts at the top.
        LaunchedEffect(nav.openProposal, nav.page) { scroll.scrollTo(0) }
        Column(
            // The keyboard pushes the page up, so the field you type in stays above it.
            Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()
                .padding(horizontal = 14.dp)
                .verticalScroll(scroll)
        ) {
            if (vm.loading) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }
            // Fixed-height slot: every edit saves in the background, and a bar
            // that appears and disappears would nudge the whole page each time.
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (vm.busy || nav.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            when (tab) {
                Tab.Horizon -> HorizonScreen(vm)
                Tab.Day -> DayScreen(vm)
                Tab.Priorities -> WeightsScreen(vm)
                Tab.Setup -> SetupScreen(vm) { tab = Tab.Priorities }
                Tab.Log -> LogScreen(vm)
                Tab.Navigator -> NavigatorScreen(nav)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    }
}


/** Three bars: the menu button, drawn rather than taken from an icon library. */
@Composable
private fun MenuGlyph() {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        listOf(0.25f, 0.5f, 0.75f).forEach { y ->
            drawLine(color, Offset(w * 0.1f, size.height * y), Offset(w * 0.9f, size.height * y),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}
