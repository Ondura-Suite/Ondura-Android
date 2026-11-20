package de.mirranet.ondura.mail

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import de.mirranet.ondura.core.CoreEvent
import de.mirranet.ondura.core.MailCore
import de.mirranet.ondura.core.MobileSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Process-wide state: the Rust core, the app settings, notifications and the
 * background sync schedule.
 */
class OnduraMailApp : Application() {

    lateinit var core: MailCore
        private set

    /** Scope for work that outlives screens (notifications, settings). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _settings = MutableStateFlow(MobileSettings())
    val settings: StateFlow<MobileSettings> = _settings.asStateFlow()

    private var currentActivity: WeakReference<Activity>? = null

    /** The activity on screen, used to open the Microsoft sign-in page in our task. */
    val foregroundActivity: Activity? get() = currentActivity?.get()

    val isInForeground: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(ActivityTracker())

        core = MailCore.create(this, coreLanguage())
        core.start()
        Notifications.createChannels(this)

        appScope.launch {
            _settings.value = runCatching { core.settings() }.getOrDefault(MobileSettings())
            SyncWorker.schedule(this@OnduraMailApp, _settings.value.syncIntervalMinutes)
        }
        appScope.launch {
            core.events.collect { event ->
                when (event) {
                    is CoreEvent.NewMail -> if (settings.value.notifications && !isInForeground) {
                        Notifications.newMail(this@OnduraMailApp, event)
                    }
                    is CoreEvent.SendFailed -> Notifications.sendFailed(this@OnduraMailApp, event)
                    else -> Unit
                }
            }
        }
    }

    suspend fun updateSettings(transform: (MobileSettings) -> MobileSettings) {
        val old = _settings.value
        val new = transform(old)
        core.saveSettings(new)
        _settings.value = core.settings()
        if (new.syncIntervalMinutes != old.syncIntervalMinutes) {
            SyncWorker.schedule(this, _settings.value.syncIntervalMinutes)
        }
        if (new.language != old.language) {
            applyLanguage(new.language)
        }
    }

    /** "" follows the system; otherwise "de", "en" or "fr". */
    fun applyLanguage(code: String) {
        val locales = if (code.isBlank()) {
            androidx.core.os.LocaleListCompat.getEmptyLocaleList()
        } else {
            androidx.core.os.LocaleListCompat.forLanguageTags(code)
        }
        AppCompatDelegate.setApplicationLocales(locales)
        core.setLanguage(coreLanguage())
    }

    /** Language for texts produced by the core (errors, quotes). */
    fun coreLanguage(): String {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        val locale = if (!appLocales.isEmpty) appLocales[0] ?: Locale.getDefault() else Locale.getDefault()
        return when (locale.language) {
            "de", "fr" -> locale.language
            else -> "en"
        }
    }

    private inner class ActivityTracker : ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            currentActivity = WeakReference(activity)
        }
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            if (currentActivity?.get() === activity) currentActivity = null
        }
    }

    companion object {
        lateinit var instance: OnduraMailApp
            private set
    }
}
