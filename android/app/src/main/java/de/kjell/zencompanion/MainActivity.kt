package de.kjell.zencompanion

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import de.kjell.zencompanion.data.KeptPage
import de.kjell.zencompanion.data.KeptPagesStore
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.review.PlayInAppReview
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.AppViewModel
import de.kjell.zencompanion.ui.browser.KeptPageHistory
import de.kjell.zencompanion.ui.components.PinDestination
import de.kjell.zencompanion.ui.components.openURLExternally
import de.kjell.zencompanion.ui.screens.MiniBrowserScreen
import de.kjell.zencompanion.ui.screens.MozillaSignInScreen
import de.kjell.zencompanion.ui.screens.SignInLandingView
import de.kjell.zencompanion.ui.screens.SpacesBrowserScreen
import de.kjell.zencompanion.ui.screens.shareUrl
import de.kjell.zencompanion.ui.sheets.KeptPagesSheet
import de.kjell.zencompanion.ui.sheets.SettingsSheet
import de.kjell.zencompanion.ui.sheets.SignInHelpSheet
import de.kjell.zencompanion.ui.sheets.SyncedActivitySheet
import de.kjell.zencompanion.ui.sheets.WhatsNewSheet
import de.kjell.zencompanion.ui.sheets.ZenSheet
import de.kjell.zencompanion.ui.theme.ZenTheme
import kotlinx.coroutines.delay

/**
 * One mini-browser launch; [id] keys the ViewModel so a new open starts clean.
 * [keptPageId] reopens a page from "On this phone"; [fromSpace] marks a space's tab.
 */
private data class BrowserLaunch(
    val url: String?,
    val title: String?,
    val id: Int,
    val keptPageId: String? = null,
    val fromSpace: Boolean = false,
)

/** Keeps an open browser across configuration changes (theme, font size, locale). */
private val BrowserLaunchSaver = listSaver<BrowserLaunch?, Any?>(
    save = { launch ->
        if (launch == null) emptyList() else listOf(launch.url, launch.title, launch.id, launch.keptPageId, launch.fromSpace)
    },
    restore = { values ->
        if (values.isEmpty()) {
            null
        } else {
            BrowserLaunch(
                url = values[0] as String?,
                title = values[1] as String?,
                id = values[2] as Int,
                keptPageId = values[3] as String?,
                fromSpace = values[4] as Boolean,
            )
        }
    },
)

/**
 * Entry point: sign-in landing or the spaces browser (port of `HomeView`).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZenTheme {
                val viewModel: AppViewModel = viewModel(factory = AppViewModel.Factory(applicationContext))
                LaunchedEffect(Unit) { viewModel.bootstrap() }

                HomeContent(viewModel)
            }
        }
    }
}

@Composable
private fun HomeContent(viewModel: AppViewModel) {
    val session by viewModel.session.collectAsState()

    if (session != null) {
        SpacesBrowserRoot(viewModel = viewModel)
    } else {
        LandingRoot(viewModel = viewModel)
    }
}

@Composable
private fun LandingRoot(viewModel: AppViewModel) {
    val context = LocalContext.current
    var showSignIn by remember { mutableStateOf(false) }
    var showSignInHelp by remember { mutableStateOf(false) }

    SignInLandingView(
        onOpenSignIn = { showSignIn = true },
        onOpenHelp = { showSignInHelp = true },
        onEnterDemo = { viewModel.enterDemo() },
    )

    if (showSignIn) {
        // Modal sheet with #FBFBFC background matching the Mozilla sign-in page
        ZenSheet(
            onDismiss = { showSignIn = false },
            skipPartiallyExpanded = true,
            containerColor = Color(0xFFFBFBFC),
        ) {
            MozillaSignInScreen(
                onFinishLogin = { login ->
                    // Completes FxA + persists the session; failures rethrow so
                    // the sign-in sheet keeps its error bubble and stays open.
                    viewModel.completeWebLogin(login)
                    showSignIn = false
                },
                onCancel = { showSignIn = false },
            )
        }
    }

    if (showSignInHelp) {
        SignInHelpSheet(
            onOpenMozilla = {
                showSignInHelp = false
                openURLExternally(context, "https://accounts.firefox.com")
            },
            onDone = { showSignInHelp = false },
        )
    }
}

@Composable
private fun SpacesBrowserRoot(viewModel: AppViewModel) {
    val browser by viewModel.browser.collectAsState()
    val session by viewModel.session.collectAsState()
    val preferences by viewModel.preferences.collectAsState()
    val activityState by viewModel.activity.collectAsState()
    val whatsNewVisible by viewModel.whatsNewVisible.collectAsState()
    val account = session ?: return

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var activeBrowserLaunch by rememberSaveable(stateSaver = BrowserLaunchSaver) { mutableStateOf<BrowserLaunch?>(null) }
    var browserLaunchCounter by rememberSaveable { mutableIntStateOf(0) }
    var showActivity by rememberSaveable { mutableStateOf(false) }
    var showKeptPages by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val keptPages = remember { KeptPagesStore.shared(context) }
    val kept by keptPages.pages.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.reviewRequests.collect {
            (context as? Activity)?.let { PlayInAppReview.request(it) }
        }
    }

    // Once per app version, after the first frame has had a moment to settle.
    // Only the main activity reaches this: sharing from another app opens
    // ShareActivity, which never builds AppViewModel, so a share cannot
    // trigger the sheet.
    LaunchedEffect(Unit) {
        delay(450)
        viewModel.presentWhatsNewIfNeeded()
    }

    val spaces = browser.snapshot.spaces
    val currentSpace = spaces.getOrNull(browser.selectedIndex) ?: spaces.firstOrNull() ?: ZenSpaces.ZenSpace(
        id = "",
        name = "",
        icon = null,
        containerGuid = null,
        theme = null,
        pinned = emptyList(),
    )

    fun openBrowser(url: String?, title: String?, fromSpace: Boolean = false) {
        if (url != null && preferences.alwaysOpenExternally) {
            openURLExternally(context, url)
        } else {
            browserLaunchCounter += 1
            activeBrowserLaunch = BrowserLaunch(url, title, browserLaunchCounter, fromSpace = fromSpace)
        }
    }

    /** A kept page always opens here: it lives in this app, not in a browser. */
    fun openKeptPage(page: KeptPage?) {
        if (page == null) {
            openBrowser(null, null)
            return
        }
        browserLaunchCounter += 1
        activeBrowserLaunch = BrowserLaunch(page.url, page.title, browserLaunchCounter, keptPageId = page.id)
    }

    fun pinKeptPage(page: KeptPage, destination: PinDestination) {
        viewModel.pinKeptPage(page, destination) { messageRes ->
            Toast.makeText(
                context,
                context.getString(R.string.browser_pin_failed) + " " + context.getString(messageRes),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // A new observer replays ON_START, so this also covers launch. Returning
    // to the foreground (e.g. after sharing from another app) refreshes what's
    // on screen, like iOS. Stops for a configuration change (dark mode, window
    // resize) are not a trip away.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    viewModel.reload()
                    viewModel.appCameToForeground()
                }
                Lifecycle.Event.ON_STOP ->
                    if ((context as? Activity)?.isChangingConfigurations != true) {
                        viewModel.appWentToBackground()
                    }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val searchOnOpenPending by viewModel.searchOnOpenPending.collectAsState()
    LaunchedEffect(searchOnOpenPending) {
        if (!searchOnOpenPending) return@LaunchedEffect
        // Leave an open browser, settings or activity sheet where the user left it.
        if (activeBrowserLaunch == null && !showSettings && !showActivity && !showKeptPages) openBrowser(null, null)
        viewModel.consumeSearchOnOpen()
    }

    Box(Modifier.fillMaxSize()) {
        SpacesBrowserScreen(
            isDemo = account.isDemo,
            state = browser,
            essentialsGrouping = preferences.essentialsGrouping,
            essentialsColumns = preferences.essentialsColumns,
            toolbarPlacement = preferences.toolbarPlacement,
            onSelectSpace = { viewModel.selectSpace(it) },
            onRefresh = { viewModel.refresh() },
            onDeleteTab = { id -> viewModel.deleteTab(id) },
            onRenameTab = { id, label -> viewModel.renameTab(id, label) },
            onMoveTab = { id, spaceId, folderId -> viewModel.moveTab(id, spaceId, folderId) },
            onPinTab = { id, spaceId -> viewModel.pinTab(id, spaceId) },
            onOpenAccount = { showSettings = true },
            onOpenActivity = { showActivity = true },
            // With an address it is one of the space's own tabs.
            onOpenBrowser = { url, title -> openBrowser(url, title, fromSpace = url != null) },
            onDismissShareTip = { viewModel.dismissShareTip() },
            onDismissSyncSetupHint = { viewModel.dismissSyncSetupHint() },
            keptCount = kept.size,
            onOpenKeptPages = { showKeptPages = true },
        )

        val launch = activeBrowserLaunch
        if (launch != null) {
            // A switch between kept pages is a new launch: a new WebView with
            // that page's own history.
            key(launch.id) {
                MiniBrowserScreen(
                    initialUrl = launch.url,
                    initialTitle = launch.title,
                    launchKey = launch.id,
                    currentSpace = currentSpace,
                    allSpaces = spaces,
                    browserRepository = viewModel.browserRepository,
                    onDismiss = { activeBrowserLaunch = null },
                    keptPageId = launch.keptPageId,
                    openedFromSpace = launch.fromSpace,
                    onSwitchPage = { page -> openKeptPage(page) },
                    onPinKeptPage = { page, destination -> pinKeptPage(page, destination) },
                )
            }
        }
    }

    if (showKeptPages) {
        KeptPagesSheet(
            pages = kept,
            currentPageId = null,
            spaces = spaces,
            pinsIntoFolders = browser.snapshot.normalTabsCapability != ZenSpaces.NormalTabsCapability.ENABLED ||
                preferences.saveKind == SaveKind.PINNED,
            onOpen = { page ->
                showKeptPages = false
                openKeptPage(page)
            },
            onNewPage = {
                showKeptPages = false
                openKeptPage(null)
            },
            onClose = { page ->
                keptPages.remove(page.id)
                KeptPageHistory.forget(page.id)
            },
            onCloseAll = {
                keptPages.clear()
                KeptPageHistory.clear()
            },
            onPin = { page, destination -> pinKeptPage(page, destination) },
            onShare = { page -> shareUrl(context, page.url, page.title) },
            onDismiss = { showKeptPages = false },
        )
    }

    if (showActivity) {
        SyncedActivitySheet(
            state = activityState,
            onLoad = { viewModel.loadActivity() },
            onDismiss = { showActivity = false },
            onOpenUrl = { url, title ->
                showActivity = false
                openBrowser(url, title)
            },
        )
    }

    if (showSettings) {
        SettingsSheet(
            email = account.email,
            isDemo = account.isDemo,
            preferences = preferences,
            normalTabsCapability = browser.snapshot.normalTabsCapability,
            onSetSearchEngine = { viewModel.setSearchEngine(it) },
            onAddSearchEngine = { name, template -> viewModel.addSearchEngine(name, template) },
            onDeleteSearchEngine = { viewModel.deleteSearchEngine(it) },
            onSetAlwaysOpenExternally = { viewModel.setAlwaysOpenExternally(it) },
            onSetEssentialsGrouping = { viewModel.setEssentialsGrouping(it) },
            onSetEssentialsColumns = { viewModel.setEssentialsColumns(it) },
            onSetSaveKind = { viewModel.setSaveKind(it) },
            onSetToolbarPlacement = { viewModel.setToolbarPlacement(it) },
            onSetOpenIntoSearch = { viewModel.setOpenIntoSearch(it) },
            onSetKeepEveryPage = { viewModel.setKeepEveryPage(it) },
            onSignOut = {
                showSettings = false
                // Clears encrypted store + sync cache + wipes WebView data.
                de.kjell.zencompanion.ui.screens.FxAWeb.wipeWebSession()
                viewModel.signOut()
            },
            onOpenWhatsNew = {
                showSettings = false
                viewModel.openWhatsNew()
            },
            onDone = { showSettings = false },
        )
    }

    if (whatsNewVisible) {
        WhatsNewSheet(onDismiss = { viewModel.dismissWhatsNew() })
    }
}
