@file:OptIn(ExperimentalMaterial3Api::class)

package de.mirranet.ondura.mail.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.mirranet.ondura.core.CoreException
import de.mirranet.ondura.core.MessageSummary
import de.mirranet.ondura.mail.OnduraMailApp
import de.mirranet.ondura.mail.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchState(
    val query: String = "",
    val results: List<MessageSummary> = emptyList(),
    val searchingServer: Boolean = false,
    val serverDone: Boolean = false,
    val error: String? = null,
)

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val core = (application as OnduraMailApp).core
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private var job: Job? = null

    fun setQuery(q: String) {
        _state.update { it.copy(query = q, serverDone = false, error = null) }
        job?.cancel()
        if (q.isBlank()) {
            _state.update { it.copy(results = emptyList()) }
            return
        }
        job = viewModelScope.launch {
            delay(200)
            val local = runCatching { core.searchLocal(q.trim()) }.getOrDefault(emptyList())
            _state.update { if (it.query == q) it.copy(results = local) else it }
        }
    }

    /** Also asks the servers (finds mail older than what is stored on the phone). */
    fun searchServer() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        _state.update { it.copy(searchingServer = true, error = null) }
        viewModelScope.launch {
            try {
                val remote = core.searchServer(q)
                _state.update { s ->
                    val known = s.results.map { it.id }.toHashSet()
                    val merged = (s.results + remote.filter { it.id !in known }).sortedByDescending { it.instant }
                    s.copy(results = merged, searchingServer = false, serverDone = true)
                }
            } catch (e: CoreException) {
                _state.update { it.copy(searchingServer = false, error = e.message) }
            }
        }
    }
}

@Composable
fun SearchScreen(onBack: () -> Unit, onOpen: (MessageSummary) -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: SearchViewModel = viewModel { SearchViewModel(app) }
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                },
                title = {
                    TextField(
                        value = state.query,
                        onValueChange = vm::setQuery,
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.searchServer() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                actions = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Outlined.Close, stringResource(R.string.clear)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.searchingServer) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.results, key = { it.id }) { m ->
                    MessageRow(message = m, onClick = { onOpen(m) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp, modifier = Modifier.padding(start = 72.dp))
                }
                if (state.query.isNotBlank() && !state.serverDone && !state.searchingServer) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            TextButton(onClick = vm::searchServer) {
                                Icon(Icons.Outlined.Cloud, null)
                                Text("  " + stringResource(R.string.search_server))
                            }
                        }
                    }
                }
                state.error?.let { err ->
                    item { Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                }
                if (state.query.isNotBlank() && state.results.isEmpty() && state.serverDone) {
                    item {
                        Text(
                            stringResource(R.string.no_results),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}
