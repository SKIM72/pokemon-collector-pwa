package com.pokebinder.scanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pokebinder.scanner.ui.ScannerScreen
import com.pokebinder.scanner.ui.theme.PokeBinderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PokeBinderTheme {
                val scannerViewModel: ScannerViewModel = viewModel()
                val state by scannerViewModel.state.collectAsState()

                ScannerScreen(
                    state = state,
                    onLanguageSelected = scannerViewModel::selectLanguage,
                    onFrameProbe = scannerViewModel::onFrameProbe,
                    onStableFrame = scannerViewModel::onStableFrame,
                    onQuantityChanged = scannerViewModel::changeQuantity,
                    onClearSession = scannerViewModel::clearSession,
                    onClose = ::finish,
                )
            }
        }
    }
}
