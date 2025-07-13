package me.ash.reader.infrastructure.android

import android.Manifest
import android.content.Intent
import android.database.CursorWindow
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.DisposableEffect
import androidx.core.app.NotificationManagerCompat
import androidx.core.util.Consumer
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.profileinstaller.ProfileInstallerInitializer
import coil.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import java.lang.reflect.Field
import javax.inject.Inject
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.compose.ProvideCompositionLocals
import me.ash.reader.infrastructure.preference.AccountSettingsProvider
import me.ash.reader.infrastructure.preference.LanguagesPreference
import me.ash.reader.infrastructure.preference.LocalDarkTheme
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.ui.ext.languages
import me.ash.reader.ui.page.nav3.AppEntry
import me.ash.reader.ui.theme.AppTheme

/** The Single-Activity Architecture. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var imageLoader: ImageLoader

    @Inject lateinit var settingsProvider: SettingsProvider

    @Inject lateinit var accountService: AccountService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("RLog", "onCreate: ${ProfileInstallerInitializer().create(this)}")

        enableEdgeToEdge()

        // Set the language
        if (Build.VERSION.SDK_INT < 33) {
            LanguagesPreference.fromValue(languages).let { LanguagesPreference.setLocale(it) }
        }

        // Workaround for https://github.com/Ashinch/ReadYou/issues/312: increase cursor window size
        try {
            val field: Field = CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
            field.isAccessible = true
            field.set(null, 100 * 1024 * 1024) // 100MB is the new cursor window size
        } catch (e: Exception) {
            Log.e("RLog", "Unable to increase cursor window size: ${e.printStackTrace()}")
        }

        val requestPermissionLauncher =
            this.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted
                ->
                if (isGranted) {} else { // Permission denied }
                }
            }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !NotificationManagerCompat.from(this).areNotificationsEnabled()
        ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            AccountSettingsProvider(accountService = accountService) {
                settingsProvider.ProvidesSettings {
                    val subscribeViewModel: SubscribeViewModel = hiltViewModel()
                    ProvideCompositionLocals {
                        AppTheme(useDarkTheme = LocalDarkTheme.current.isDarkTheme()) {
                            AppEntry()
                        }
                    }

                    DisposableEffect(this) {
                        val listener =
                            Consumer<Intent> { intent ->
                                intent.getLaunchAction()?.let { action ->
                                    when (action) {
                                        is LaunchAction.OpenArticle -> {
                                            // TODO
                                        }

                                        is LaunchAction.Subscribe -> {
                                            subscribeViewModel.handleSharedUrlFromIntent(action.url)
                                            // TODO
                                        }
                                    }
                                }
                            }
                        listener.accept(intent) // consume the launch intent as well
                        addOnNewIntentListener(listener)
                        onDispose { removeOnNewIntentListener(listener) }
                    }
                }
            }
        }
    }
}
