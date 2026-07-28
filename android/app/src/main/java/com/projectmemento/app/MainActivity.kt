package com.projectmemento.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.core.content.ContextCompat
import com.projectmemento.app.permissions.PhotoAccess
import com.projectmemento.app.ui.MementoApp
import com.projectmemento.app.ui.MementoViewModel
import com.projectmemento.app.ui.theme.ProjectMementoTheme

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MementoViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.onAppResumed()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.setWeeklyReminderEnabled(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel = ViewModelProvider(this)[MementoViewModel::class.java]

        setContent {
            ProjectMementoTheme {
                MementoApp(
                    state = viewModel.uiState,
                    onRequestPermission = {
                        permissionLauncher.launch(PhotoAccess.requestPermissions())
                    },
                    onRefresh = viewModel::refresh,
                    onSelectTab = viewModel::selectTab,
                    onSelectTopic = viewModel::selectTopic,
                    onClearTopic = viewModel::clearTopic,
                    onQueryChange = viewModel::updateQuery,
                    onSubmitSearch = viewModel::submitSearch,
                    onSelectItem = viewModel::selectItem,
                    onCloseDetail = viewModel::closeDetail,
                    onUpdateStatus = viewModel::updateStatus,
                    onUpdateIntent = viewModel::updateIntent,
                    onUpdateTopic = viewModel::updateTopic,
                    onQuickUpdateTopic = viewModel::quickUpdateTopic,
                    onLoadMore = viewModel::loadMore,
                    onSetWeeklyReminderEnabled = ::setWeeklyReminderEnabled,
                    onDismissError = viewModel::dismissError,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::viewModel.isInitialized) viewModel.onAppResumed()
    }

    private fun setWeeklyReminderEnabled(enabled: Boolean) {
        if (!enabled) {
            viewModel.setWeeklyReminderEnabled(false)
            return
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.setWeeklyReminderEnabled(true)
        }
    }
}
