package de.mirranet.ondura.mail

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.mirranet.ondura.mail.ui.AccountScreen
import de.mirranet.ondura.mail.ui.ComposeMode
import de.mirranet.ondura.mail.ui.ComposeRequest
import de.mirranet.ondura.mail.ui.ComposeScreen
import de.mirranet.ondura.mail.ui.InboxScreen
import de.mirranet.ondura.mail.ui.MailView
import de.mirranet.ondura.mail.ui.MailViewModel
import de.mirranet.ondura.mail.ui.ReaderScreen
import de.mirranet.ondura.mail.ui.SearchScreen
import de.mirranet.ondura.mail.ui.SettingsScreen
import de.mirranet.ondura.mail.ui.SetupScreen
import de.mirranet.ondura.ui.OnduraTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Something another app or a notification asked us to show. */
sealed interface LaunchRequest {
    data class OpenMessage(val messageId: String) : LaunchRequest
    data class OpenFolder(val folderId: String) : LaunchRequest
    data class Compose(val request: ComposeRequest) : LaunchRequest
}

class MainActivity : AppCompatActivity() {
    private val launches = MutableStateFlow<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        val app = application as OnduraMailApp
        setContent {
            val settings by app.settings.collectAsStateWithLifecycle()
            OnduraTheme(theme = settings.theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MailApp(launches)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(Notifications.EXTRA_MESSAGE_ID)?.let {
            launches.value = LaunchRequest.OpenMessage(it)
            return
        }
        intent.getStringExtra(Notifications.EXTRA_FOLDER_ID)?.let {
            launches.value = LaunchRequest.OpenFolder(it)
            return
        }
        when (intent.action) {
            Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "mailto" }?.let {
                launches.value = LaunchRequest.Compose(ComposeRequest.fromMailto(it))
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val streams = if (intent.action == Intent.ACTION_SEND) {
                    listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                } else {
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                }
                launches.value = LaunchRequest.Compose(
                    ComposeRequest(
                        to = intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList().orEmpty(),
                        cc = intent.getStringArrayExtra(Intent.EXTRA_CC)?.toList().orEmpty(),
                        bcc = intent.getStringArrayExtra(Intent.EXTRA_BCC)?.toList().orEmpty(),
                        subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(),
                        body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty(),
                        attachments = streams,
                    ),
                )
            }
        }
    }
}

private object Routes {
    const val LOADING = "loading"
    const val SETUP = "setup"
    const val ADD_ACCOUNT = "add-account"
    const val INBOX = "inbox"
    const val MESSAGE = "message/{id}"
    const val COMPOSE = "compose?mode={mode}&ref={ref}&account={account}&shared={shared}"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val ACCOUNT = "account/{id}"

    fun message(id: String) = "message/${Uri.encode(id)}"
    fun account(id: String) = "account/${Uri.encode(id)}"
    fun compose(mode: ComposeMode, ref: String? = null, account: String? = null, shared: Boolean = false) =
        "compose?mode=${mode.name}&ref=${Uri.encode(ref ?: "")}&account=${Uri.encode(account ?: "")}&shared=$shared"
}

/** Content shared into the compose screen, handed over outside the nav arguments. */
private object SharedCompose {
    var request: ComposeRequest? = null
}

@Composable
private fun MailApp(launches: MutableStateFlow<LaunchRequest?>) {
    val nav = rememberNavController()
    val mail: MailViewModel = viewModel()
    val state by mail.state.collectAsStateWithLifecycle()
    val launch by launches.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Android 13+: ask once for permission to show new-mail notifications.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(state.accounts.isNotEmpty()) {
        if (state.accounts.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // First screen once the accounts are known; back to setup when the last one is removed.
    LaunchedEffect(state.loaded, state.accounts.isEmpty()) {
        if (!state.loaded) return@LaunchedEffect
        val route = nav.currentDestination?.route
        if (state.accounts.isEmpty() && route != Routes.SETUP) {
            nav.navigate(Routes.SETUP) { popUpTo(0) }
        } else if (state.accounts.isNotEmpty() && (route == Routes.LOADING || route == Routes.SETUP)) {
            nav.navigate(Routes.INBOX) { popUpTo(0) }
        }
    }

    LaunchedEffect(launch, state.loaded) {
        val request = launch ?: return@LaunchedEffect
        if (!state.loaded || state.accounts.isEmpty()) return@LaunchedEffect
        launches.value = null
        when (request) {
            is LaunchRequest.OpenMessage -> nav.navigate(Routes.message(request.messageId))
            is LaunchRequest.OpenFolder -> state.folder(request.folderId)?.let { mail.select(MailView.InFolder(it.accountId, it.id)) }
            is LaunchRequest.Compose -> {
                SharedCompose.request = request.request
                nav.navigate(Routes.compose(ComposeMode.NEW, shared = true))
            }
        }
    }

    NavHost(navController = nav, startDestination = Routes.LOADING) {
        composable(Routes.LOADING) {
            Box(Modifier.fillMaxSize())
        }
        composable(Routes.SETUP) {
            SetupScreen(canGoBack = false, onBack = {}, onDone = {
                scope.launch { mail.reloadAccounts() }
            })
        }
        composable(Routes.ADD_ACCOUNT) {
            SetupScreen(canGoBack = true, onBack = { nav.popBackStack() }, onDone = {
                scope.launch {
                    mail.reloadAccounts()
                    nav.popBackStack(Routes.INBOX, inclusive = false)
                }
            })
        }
        composable(Routes.INBOX) {
            InboxScreen(
                vm = mail,
                onOpenMessage = { m, isDraft ->
                    if (isDraft) nav.navigate(Routes.compose(ComposeMode.DRAFT, ref = m.id)) else nav.navigate(Routes.message(m.id))
                },
                onCompose = { nav.navigate(Routes.compose(ComposeMode.NEW, account = state.currentAccount?.id)) },
                onSearch = { nav.navigate(Routes.SEARCH) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onAddAccount = { nav.navigate(Routes.ADD_ACCOUNT) },
                onAccountSettings = { nav.navigate(Routes.account(it)) },
            )
        }
        composable(Routes.MESSAGE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            ReaderScreen(
                messageId = id,
                mail = mail,
                onBack = { nav.popBackStack() },
                onReply = { nav.navigate(Routes.compose(ComposeMode.REPLY, ref = it)) },
                onReplyAll = { nav.navigate(Routes.compose(ComposeMode.REPLY_ALL, ref = it)) },
                onForward = { nav.navigate(Routes.compose(ComposeMode.FORWARD, ref = it)) },
                onMailto = { uri ->
                    SharedCompose.request = ComposeRequest.fromMailto(uri)
                    nav.navigate(Routes.compose(ComposeMode.NEW, shared = true))
                },
            )
        }
        composable(
            Routes.COMPOSE,
            arguments = listOf(
                navArgument("mode") { type = NavType.StringType; defaultValue = ComposeMode.NEW.name },
                navArgument("ref") { type = NavType.StringType; defaultValue = "" },
                navArgument("account") { type = NavType.StringType; defaultValue = "" },
                navArgument("shared") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val args = entry.arguments
            val mode = runCatching { ComposeMode.valueOf(args?.getString("mode").orEmpty()) }.getOrDefault(ComposeMode.NEW)
            val request = if (args?.getBoolean("shared") == true) SharedCompose.request else null
            ComposeScreen(
                mode = mode,
                refId = args?.getString("ref")?.takeIf { it.isNotEmpty() },
                preferredAccountId = args?.getString("account")?.takeIf { it.isNotEmpty() },
                request = request,
                onClose = { draftSaved ->
                    SharedCompose.request = null
                    if (draftSaved) mail.notify(nav.context.getString(R.string.draft_saved))
                    nav.popBackStack()
                },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                onBack = { nav.popBackStack() },
                onOpen = { m -> nav.navigate(Routes.message(m.id)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                mail = mail,
                onBack = { nav.popBackStack() },
                onAccount = { nav.navigate(Routes.account(it)) },
                onAddAccount = { nav.navigate(Routes.ADD_ACCOUNT) },
            )
        }
        composable(Routes.ACCOUNT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            AccountScreen(
                accountId = entry.arguments?.getString("id").orEmpty(),
                mail = mail,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
