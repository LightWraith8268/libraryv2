package com.inknironapps.libraryiq.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inknironapps.libraryiq.data.update.AppUpdateManager
import com.inknironapps.libraryiq.data.update.WhatsNewInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WhatsNewViewModel @Inject constructor(
    private val appUpdateManager: AppUpdateManager
) : ViewModel() {

    private val _whatsNew = MutableStateFlow<WhatsNewInfo?>(null)
    val whatsNew: StateFlow<WhatsNewInfo?> = _whatsNew.asStateFlow()

    init {
        if (appUpdateManager.isFirstLaunch()) {
            // First ever launch — just record the version, don't show dialog
            appUpdateManager.markVersionSeen()
        } else if (appUpdateManager.shouldShowWhatsNew()) {
            viewModelScope.launch {
                val info = appUpdateManager.getWhatsNew()
                if (info != null) {
                    _whatsNew.value = info
                } else {
                    // Couldn't fetch release notes, still mark as seen
                    appUpdateManager.markVersionSeen()
                }
            }
        }
    }

    fun dismissWhatsNew() {
        _whatsNew.value = null
        appUpdateManager.markVersionSeen()
    }
}
