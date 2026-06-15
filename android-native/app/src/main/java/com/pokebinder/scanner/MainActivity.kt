package com.pokebinder.scanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pokebinder.scanner.ui.PokeBinderApp
import com.pokebinder.scanner.ui.ThemeMode
import com.pokebinder.scanner.ui.theme.PokeBinderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themeMode by rememberSaveable { mutableStateOf(ThemeMode.SYSTEM) }
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            PokeBinderTheme(darkTheme = darkTheme) {
                val scannerViewModel: ScannerViewModel = viewModel()
                val state by scannerViewModel.state.collectAsState()

                PokeBinderApp(
                    state = state,
                    themeMode = themeMode,
                    onThemeModeChanged = { themeMode = it },
                    onLanguageSelected = scannerViewModel::selectLanguage,
                    onFrameProbe = scannerViewModel::onFrameProbe,
                    onStableFrame = scannerViewModel::onStableFrame,
                    onCandidateSelected = scannerViewModel::selectCandidate,
                    onConfirmScan = scannerViewModel::confirmScannedCard,
                    onNextScan = scannerViewModel::startNextScan,
                    onQuantitySaved = scannerViewModel::saveQuantity,
                    onCardDeleted = scannerViewModel::deleteCollectionCard,
                    onFavoriteToggle = scannerViewModel::toggleFavorite,
                    onClearSession = scannerViewModel::clearSession,
                    onSignIn = scannerViewModel::signIn,
                    onSignUp = scannerViewModel::signUp,
                    onPasswordReset = scannerViewModel::sendPasswordReset,
                    onSearchCards = scannerViewModel::searchCards,
                    onAddSearchCard = scannerViewModel::addSearchCard,
                    onSearchSortFieldChanged = scannerViewModel::setSearchSortField,
                    onSearchSortDirectionToggle =
                    scannerViewModel::toggleSearchSortDirection,
                    onCollectionSortFieldChanged = scannerViewModel::setCollectionSortField,
                    onCollectionSortDirectionToggle =
                    scannerViewModel::toggleCollectionSortDirection,
                    onDisplayCurrencyChanged = scannerViewModel::setDisplayCurrency,
                    onRefreshCollection = scannerViewModel::refreshCollection,
                    onProfileUpdate = scannerViewModel::updateProfile,
                    onPasswordUpdate = scannerViewModel::updatePassword,
                    onSignOut = scannerViewModel::signOut,
                )
            }
        }
    }
}
