@file:OptIn(ExperimentalMaterial3Api::class)

package de.mirranet.ondura.mail.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Window
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.mirranet.ondura.core.CertificateInfo
import de.mirranet.ondura.core.CoreException
import de.mirranet.ondura.core.EwsConfig
import de.mirranet.ondura.core.ImapConfig
import de.mirranet.ondura.core.NewEwsAccount
import de.mirranet.ondura.core.NewImapAccount
import de.mirranet.ondura.core.Security
import de.mirranet.ondura.mail.Browser
import de.mirranet.ondura.mail.OnduraMailApp
import de.mirranet.ondura.mail.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SetupKind { CHOOSE, MICROSOFT, EXCHANGE, IMAP }

data class SetupState(
    val kind: SetupKind = SetupKind.CHOOSE,
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val username: String = "",
    val ewsUrl: String = "",
    val imapHost: String = "",
    val imapPort: String = "993",
    val imapSecurity: String = Security.TLS,
    val smtpHost: String = "",
    val smtpPort: String = "465",
    val smtpSecurity: String = Security.TLS,
    /** Server settings found (or entered): the second step of IMAP. */
    val serversKnown: Boolean = false,
    val discoveredFrom: String? = null,
    val suggestMicrosoft: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val certificate: CertificateInfo? = null,
    val done: Boolean = false,
)

class SetupViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OnduraMailApp
    private val core = app.core
    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    val microsoftAvailable: Boolean get() = core.hasBuiltinGraphClientId

    fun update(change: (SetupState) -> SetupState) = _state.update { change(it).copy(error = null) }

    fun choose(kind: SetupKind) = _state.update { it.copy(kind = kind, error = null, certificate = null) }

    fun back(): Boolean {
        val s = _state.value
        return when {
            s.busy -> true
            s.kind == SetupKind.IMAP && s.serversKnown -> {
                _state.update { it.copy(serversKnown = false, error = null) }
                true
            }
            s.kind != SetupKind.CHOOSE -> {
                _state.update { it.copy(kind = SetupKind.CHOOSE, error = null) }
                true
            }
            else -> false
        }
    }

    fun signInMicrosoft() {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                core.addMicrosoftAccount { url -> Browser.open(app, url) }
                Browser.returnToApp(app)
                _state.update { it.copy(busy = false, done = true) }
            } catch (e: CoreException) {
                Browser.returnToApp(app)
                _state.update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    /** IMAP step 1: find the servers for the address. */
    fun discover() {
        val s = _state.value
        if (!s.email.contains('@') || s.password.isEmpty()) {
            _state.update { it.copy(error = app.getString(R.string.setup_need_email_password)) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val found = runCatching { core.discover(s.email.trim()) }.getOrNull()
            _state.update { st ->
                var n = st.copy(busy = false, serversKnown = true, username = found?.username ?: st.email.trim())
                found?.imap?.let { n = n.copy(imapHost = it.host, imapPort = it.port.toString(), imapSecurity = it.security) }
                found?.smtp?.let { n = n.copy(smtpHost = it.host, smtpPort = it.port.toString(), smtpSecurity = it.security) }
                if (found?.imap == null) {
                    val domain = st.email.substringAfter('@').trim()
                    n = n.copy(imapHost = "imap.$domain", smtpHost = "smtp.$domain")
                }
                n.copy(
                    discoveredFrom = found?.provider ?: found?.imap?.host,
                    suggestMicrosoft = found?.suggest == "microsoft365",
                    ewsUrl = found?.ewsUrl ?: st.ewsUrl,
                )
            }
        }
    }

    fun addImap() {
        val s = _state.value
        val imapPort = s.imapPort.toIntOrNull()
        val smtpPort = s.smtpPort.toIntOrNull()
        if (s.imapHost.isBlank() || s.smtpHost.isBlank() || imapPort == null || smtpPort == null) {
            _state.update { it.copy(error = app.getString(R.string.setup_check_servers)) }
            return
        }
        _state.update { it.copy(busy = true, error = null, certificate = null) }
        viewModelScope.launch {
            try {
                core.addImapAccount(
                    NewImapAccount(
                        displayName = s.name.ifBlank { s.email.substringBefore('@') },
                        email = s.email.trim(),
                        password = s.password,
                        config = ImapConfig(
                            imapHost = s.imapHost.trim(),
                            imapPort = imapPort,
                            imapSecurity = s.imapSecurity,
                            smtpHost = s.smtpHost.trim(),
                            smtpPort = smtpPort,
                            smtpSecurity = s.smtpSecurity,
                            username = s.username.ifBlank { s.email }.trim(),
                        ),
                    ),
                )
                _state.update { it.copy(busy = false, done = true) }
            } catch (e: CoreException) {
                _state.update { it.copy(busy = false, error = e.message) }
                if (looksLikeCertificateProblem(e.message)) {
                    val mode = if (s.imapSecurity == Security.STARTTLS) "imapStartTls" else "direct"
                    val cert = runCatching { core.probeCertificate(s.imapHost.trim(), imapPort, mode) }.getOrNull()
                    if (cert?.problem != null && !cert.trusted) _state.update { it.copy(certificate = cert) }
                }
            }
        }
    }

    fun addExchange() {
        val s = _state.value
        if (!s.email.contains('@') || s.password.isEmpty() || s.ewsUrl.isBlank()) {
            _state.update { it.copy(error = app.getString(R.string.setup_need_email_password)) }
            return
        }
        _state.update { it.copy(busy = true, error = null, certificate = null) }
        viewModelScope.launch {
            try {
                core.addEwsAccount(
                    NewEwsAccount(
                        displayName = s.name.ifBlank { s.email.substringBefore('@') },
                        email = s.email.trim(),
                        password = s.password,
                        config = EwsConfig(url = s.ewsUrl.trim(), username = s.username.ifBlank { s.email }.trim()),
                    ),
                )
                _state.update { it.copy(busy = false, done = true) }
            } catch (e: CoreException) {
                _state.update { it.copy(busy = false, error = e.message) }
                if (looksLikeCertificateProblem(e.message)) {
                    val host = runCatching { android.net.Uri.parse(s.ewsUrl.trim()).host }.getOrNull()
                    val cert = host?.let { runCatching { core.probeCertificate(it, 443, "direct") }.getOrNull() }
                    if (cert?.problem != null && !cert.trusted) _state.update { it.copy(certificate = cert) }
                }
            }
        }
    }

    /** The user trusts the server's certificate: remember it and try again. */
    fun trustCertificate() {
        val cert = _state.value.certificate ?: return
        viewModelScope.launch {
            core.setCertificateTrust(cert.fingerprint, true)
            _state.update { it.copy(certificate = null) }
            if (_state.value.kind == SetupKind.EXCHANGE) addExchange() else addImap()
        }
    }

    fun prefillExchange() {
        val s = _state.value
        if (s.ewsUrl.isBlank() && s.email.contains('@')) {
            _state.update { it.copy(ewsUrl = "https://mail.${s.email.substringAfter('@').trim()}/EWS/Exchange.asmx") }
        }
    }

    private fun looksLikeCertificateProblem(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return listOf("certificate", "zertifikat", "certificat", "unknownissuer", "invalid peer", "tls").any { m.contains(it) }
    }
}

@Composable
fun SetupScreen(canGoBack: Boolean, onBack: () -> Unit, onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: SetupViewModel = viewModel { SetupViewModel(app) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.done) { if (state.done) onDone() }
    BackHandler(enabled = state.kind != SetupKind.CHOOSE || state.busy) { vm.back() }

    SetupLayout(
        showBack = canGoBack || state.kind != SetupKind.CHOOSE,
        onBack = { if (!vm.back()) onBack() },
    ) {
        when (state.kind) {
            SetupKind.CHOOSE -> Choose(vm::choose)
            SetupKind.MICROSOFT -> Microsoft(state, vm)
            SetupKind.EXCHANGE -> Exchange(state, vm)
            SetupKind.IMAP -> Imap(state, vm)
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        state.certificate?.let { cert -> CertificateCard(cert, onTrust = vm::trustCertificate) }
    }
}

@Composable
private fun SetupLayout(showBack: Boolean, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** First step of the account setup (also used for the screenshots). */
@Composable
fun SetupWelcome(onChoose: (SetupKind) -> Unit = {}) {
    SetupLayout(showBack = false, onBack = {}) { Choose(onChoose) }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Image(
        painter = painterResource(R.drawable.ondura_logo),
        contentDescription = null,
        modifier = Modifier.size(72.dp),
    )
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Choose(onChoose: (SetupKind) -> Unit) {
    Header(stringResource(R.string.setup_welcome), stringResource(R.string.setup_choose))
    ChoiceCard(Icons.Outlined.Window, stringResource(R.string.setup_microsoft), stringResource(R.string.setup_microsoft_hint)) {
        onChoose(SetupKind.MICROSOFT)
    }
    ChoiceCard(Icons.Outlined.Business, stringResource(R.string.setup_exchange), stringResource(R.string.setup_exchange_hint)) {
        onChoose(SetupKind.EXCHANGE)
    }
    ChoiceCard(Icons.Outlined.Email, stringResource(R.string.setup_imap), stringResource(R.string.setup_imap_hint)) {
        onChoose(SetupKind.IMAP)
    }
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Microsoft(state: SetupState, vm: SetupViewModel) {
    Header(stringResource(R.string.setup_microsoft), stringResource(R.string.setup_microsoft_explain))
    if (!vm.microsoftAvailable) {
        InfoCard(stringResource(R.string.setup_no_client_id))
        return
    }
    if (state.busy) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.setup_waiting_browser))
        }
    } else {
        Button(onClick = vm::signInMicrosoft, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.setup_sign_in_microsoft))
        }
    }
}

@Composable
private fun Exchange(state: SetupState, vm: SetupViewModel) {
    Header(stringResource(R.string.setup_exchange), stringResource(R.string.setup_exchange_explain))
    CommonFields(state, vm)
    Field(state.username, { v -> vm.update { it.copy(username = v) } }, stringResource(R.string.setup_username), stringResource(R.string.setup_username_hint))
    Field(
        state.ewsUrl,
        { v -> vm.update { it.copy(ewsUrl = v) } },
        stringResource(R.string.setup_ews_url),
        keyboardType = KeyboardType.Uri,
    )
    if (state.ewsUrl.isBlank() && state.email.contains('@')) {
        TextButton(onClick = vm::prefillExchange) { Text(stringResource(R.string.setup_ews_guess)) }
    }
    ActionButton(state.busy, stringResource(R.string.sign_in), vm::addExchange)
}

@Composable
private fun Imap(state: SetupState, vm: SetupViewModel) {
    Header(stringResource(R.string.setup_imap), stringResource(R.string.setup_imap_explain))
    CommonFields(state, vm)
    if (!state.serversKnown) {
        ActionButton(state.busy, stringResource(R.string.next), vm::discover)
        return
    }
    if (state.suggestMicrosoft) {
        InfoCard(stringResource(R.string.setup_use_microsoft)) {
            TextButton(onClick = { vm.choose(SetupKind.MICROSOFT) }) { Text(stringResource(R.string.setup_microsoft)) }
        }
    }
    state.discoveredFrom?.let {
        Text(stringResource(R.string.setup_found, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(stringResource(R.string.setup_incoming), style = MaterialTheme.typography.titleSmall)
    ServerFields(
        host = state.imapHost, port = state.imapPort, security = state.imapSecurity,
        onHost = { v -> vm.update { it.copy(imapHost = v) } },
        onPort = { v -> vm.update { it.copy(imapPort = v) } },
        onSecurity = { v -> vm.update { it.copy(imapSecurity = v, imapPort = if (v == Security.TLS) "993" else "143") } },
    )
    Text(stringResource(R.string.setup_outgoing), style = MaterialTheme.typography.titleSmall)
    ServerFields(
        host = state.smtpHost, port = state.smtpPort, security = state.smtpSecurity,
        onHost = { v -> vm.update { it.copy(smtpHost = v) } },
        onPort = { v -> vm.update { it.copy(smtpPort = v) } },
        onSecurity = { v -> vm.update { it.copy(smtpSecurity = v, smtpPort = if (v == Security.TLS) "465" else "587") } },
    )
    Field(state.username, { v -> vm.update { it.copy(username = v) } }, stringResource(R.string.setup_username))
    ActionButton(state.busy, stringResource(R.string.sign_in), vm::addImap)
}

@Composable
private fun CommonFields(state: SetupState, vm: SetupViewModel) {
    Field(state.name, { v -> vm.update { it.copy(name = v) } }, stringResource(R.string.setup_name), stringResource(R.string.setup_name_hint))
    Field(state.email, { v -> vm.update { it.copy(email = v, serversKnown = false) } }, stringResource(R.string.setup_email), keyboardType = KeyboardType.Email)
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = state.password,
        onValueChange = { v -> vm.update { it.copy(password = v) } },
        label = { Text(stringResource(R.string.setup_password)) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Lock, null) },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null)
            }
        },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ServerFields(
    host: String,
    port: String,
    security: String,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onSecurity: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = host, onValueChange = onHost, label = { Text(stringResource(R.string.setup_server)) },
            singleLine = true, modifier = Modifier.weight(2f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = port, onValueChange = { v -> onPort(v.filter(Char::isDigit).take(5)) }, label = { Text(stringResource(R.string.setup_port)) },
            singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = security == Security.TLS, onClick = { onSecurity(Security.TLS) }, label = { Text("SSL/TLS") })
        FilterChip(selected = security == Security.STARTTLS, onClick = { onSecurity(Security.STARTTLS) }, label = { Text("STARTTLS") })
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = if (supporting != null) {
            { Text(supporting) }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun ActionButton(busy: Boolean, label: String, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(label)
    }
}

@Composable
private fun InfoCard(text: String, action: (@Composable () -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(text, color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium)
            }
            action?.invoke()
        }
    }
}

@Composable
private fun CertificateCard(cert: CertificateInfo, onTrust: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.cert_title, cert.host), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            cert.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
            Text(stringResource(R.string.cert_fingerprint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(cert.fingerprint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(stringResource(R.string.cert_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            OutlinedButton(onClick = onTrust) { Text(stringResource(R.string.cert_trust)) }
        }
    }
}
