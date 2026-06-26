package com.pokebinder.scanner

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pokebinder.scanner.data.AuthOutcome
import com.pokebinder.scanner.data.CardRecognitionRepository
import com.pokebinder.scanner.data.CardScanImageRepository
import com.pokebinder.scanner.data.EdgeFunctionCardRecognitionRepository
import com.pokebinder.scanner.data.ExchangeRateRepository
import com.pokebinder.scanner.data.SupabaseAuthRepository
import com.pokebinder.scanner.data.SupabaseCollectionRepository
import com.pokebinder.scanner.data.SupabaseSession
import com.pokebinder.scanner.data.TcgDexRepository
import com.pokebinder.scanner.model.AuthStatus
import com.pokebinder.scanner.model.CardDetection
import com.pokebinder.scanner.model.CardLanguage
import com.pokebinder.scanner.model.CollectionSortField
import com.pokebinder.scanner.model.FrameProbe
import com.pokebinder.scanner.model.RecognitionOutcome
import com.pokebinder.scanner.model.RecognizedCard
import com.pokebinder.scanner.model.ScanDebugCandidate
import com.pokebinder.scanner.model.ScanDebugSnapshot
import com.pokebinder.scanner.model.ScanPhase
import com.pokebinder.scanner.model.SearchSortField
import com.pokebinder.scanner.model.ScannerUiState
import com.pokebinder.scanner.model.SessionCard
import com.pokebinder.scanner.model.SortDirection
import com.pokebinder.scanner.scanner.CardImageEmbedder
import com.pokebinder.scanner.scanner.CardTextRecognizer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt

class ScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val imageEmbedder = CardImageEmbedder(application)
    private val cardTextRecognizer = CardTextRecognizer()
    private val recognitionRepository: CardRecognitionRepository =
        EdgeFunctionCardRecognitionRepository(imageEmbedder)
    private val authRepository = SupabaseAuthRepository(application)
    private val collectionRepository = SupabaseCollectionRepository()
    private val scanImageRepository = CardScanImageRepository(application)
    private val cardSearchRepository = TcgDexRepository()
    private val exchangeRateRepository = ExchangeRateRepository()

    private val mutableState = MutableStateFlow(
        ScannerUiState(
            isEndpointConfigured = BuildConfig.SUPABASE_URL.isNotBlank() &&
                BuildConfig.SUPABASE_ANON_KEY.isNotBlank(),
        ),
    )
    val state: StateFlow<ScannerUiState> = mutableState.asStateFlow()

    private var authSession: SupabaseSession? = null
    private var requestInFlight = false
    private var pendingScanJpeg: ByteArray? = null

    init {
        viewModelScope.launch {
            val rates = exchangeRateRepository.loadRates()
            mutableState.update { it.copy(currencyRates = rates) }
        }
        restoreSession()
    }

    fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            setAuthMessage("이메일과 비밀번호를 입력해 주세요.")
            return
        }
        runAuthRequest {
            authRepository.signIn(email, password)
        }
    }

    fun signUp(
        email: String,
        username: String,
        password: String,
        confirmPassword: String,
    ) {
        when {
            username.isBlank() -> setAuthMessage("아이디를 입력해 주세요.")
            email.isBlank() -> setAuthMessage("이메일을 입력해 주세요.")
            password.length < 6 -> setAuthMessage("비밀번호는 6자 이상 입력해 주세요.")
            password != confirmPassword -> setAuthMessage("비밀번호가 서로 다릅니다.")
            else -> runAuthRequest {
                authRepository.signUp(email, password, username)
            }
        }
    }

    fun sendPasswordReset(email: String) {
        if (email.isBlank()) {
            setAuthMessage("이메일을 입력해 주세요.")
            return
        }
        runAuthRequest {
            authRepository.sendPasswordReset(email)
        }
    }

    fun updateProfile(username: String) {
        if (username.isBlank()) {
            setAuthMessage("아이디를 입력해 주세요.")
            return
        }
        runAuthenticatedRequest { session ->
            authRepository.updateProfile(session, username)
        }
    }

    fun updatePassword(
        password: String,
        confirmPassword: String,
    ) {
        when {
            password.length < 6 -> setAuthMessage("비밀번호는 6자 이상 입력해 주세요.")
            password != confirmPassword -> setAuthMessage("비밀번호가 서로 다릅니다.")
            else -> runAuthenticatedRequest { session ->
                authRepository.updatePassword(session, password)
            }
        }
    }

    fun signOut() {
        val session = authSession
        viewModelScope.launch {
            mutableState.update { it.copy(authBusy = true, authMessage = "") }
            authRepository.signOut(session)
            authSession = null
            mutableState.update {
                ScannerUiState(
                    authStatus = AuthStatus.SIGNED_OUT,
                    isEndpointConfigured = it.isEndpointConfigured,
                )
            }
        }
    }

    fun refreshCollection() {
        viewModelScope.launch {
            val session = activeSession() ?: return@launch
            loadCollection(session, refreshAllDetails = true)
        }
    }

    fun searchCards(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            mutableState.update { it.copy(searchMessage = "검색어를 입력해 주세요") }
            return
        }
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    searchBusy = true,
                    searchResults = emptyList(),
                    searchMessage = "일본판·영문판·한국판을 함께 검색 중",
                )
            }
            runCatching {
                cardSearchRepository.searchAllLanguages(trimmed)
            }.onSuccess { cards ->
                mutableState.update {
                    it.copy(
                        searchBusy = false,
                        searchResults = cards,
                        searchMessage = if (cards.isEmpty()) {
                            "검색 결과가 없습니다"
                        } else {
                            "${cards.size}개 카드를 찾았습니다"
                        },
                    )
                }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        searchBusy = false,
                        searchMessage = error.message ?: "카드 검색에 실패했습니다",
                    )
                }
            }
        }
    }

    fun setSearchSortField(field: SearchSortField) {
        mutableState.update { it.copy(searchSortField = field) }
    }

    fun toggleSearchSortDirection() {
        mutableState.update {
            it.copy(searchSortDirection = it.searchSortDirection.toggled())
        }
    }

    fun setCollectionSortField(field: CollectionSortField) {
        mutableState.update { it.copy(collectionSortField = field) }
    }

    fun toggleCollectionSortDirection() {
        mutableState.update {
            it.copy(collectionSortDirection = it.collectionSortDirection.toggled())
        }
    }

    fun setDisplayCurrency(currency: String) {
        if (currency !in setOf("JPY", "KRW", "USD")) return
        mutableState.update { it.copy(displayCurrency = currency) }
        viewModelScope.launch {
            val session = activeSession() ?: return@launch
            runCatching { collectionRepository.saveDisplayCurrency(session, currency) }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(syncMessage = error.message ?: "통화 설정 저장에 실패했습니다")
                    }
                }
        }
    }

    fun setScanDebugEnabled(enabled: Boolean) {
        mutableState.update {
            it.copy(
                scanDebugEnabled = enabled,
                lastScanDebug = if (enabled) it.lastScanDebug else null,
            )
        }
        showNotice(if (enabled) "스캔 디버그가 켜졌습니다." else "스캔 디버그가 꺼졌습니다.")
    }

    fun clearScanDebug() {
        scanImageRepository.clearDebugCrops()
        mutableState.update { it.copy(lastScanDebug = null) }
        showNotice("스캔 디버그 기록을 지웠습니다.")
    }

    fun addSearchCard(card: RecognizedCard) {
        viewModelScope.launch {
            mutableState.update {
                it.copy(searchBusy = true, searchMessage = "카드 상세 정보 확인 중")
            }
            runCatching {
                cardSearchRepository.fetchCard(card)
            }.onSuccess { detailed ->
                mutableState.update { current ->
                    current.copy(
                        sessionCards = incrementCard(current.sessionCards, detailed),
                        searchBusy = true,
                        searchMessage = "Supabase에 저장 중",
                    )
                }
                val saved = mutableState.value.sessionCards
                    .firstOrNull { matchesRecognized(it, detailed) }
                    ?.let { persistCard(it) }
                    ?: false
                mutableState.update {
                    it.copy(
                        searchBusy = false,
                        searchMessage = if (saved) {
                            "${detailed.name} 1장 추가됨"
                        } else {
                            "카드는 추가했지만 클라우드 저장에 실패했습니다"
                        },
                    )
                }
                showNotice(
                    if (saved) {
                        "${detailed.name} 카드가 컬렉션에 추가되었습니다."
                    } else {
                        "카드는 추가했지만 클라우드 저장에 실패했습니다."
                    },
                )
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        searchBusy = false,
                        searchMessage = error.message ?: "카드 추가에 실패했습니다",
                    )
                }
            }
        }
    }

    fun selectLanguage(language: CardLanguage) {
        pendingScanJpeg = null
        mutableState.update {
            it.copy(
                language = language,
                phase = ScanPhase.WAITING,
                currentMatch = null,
                candidates = emptyList(),
                scanAwaitingConfirmation = false,
                scanAdded = false,
                scanSaving = false,
                statusMessage = "화면 안에 카드를 보여 주세요",
            )
        }
    }

    fun onFrameProbe(probe: FrameProbe) {
        if (requestInFlight || mutableState.value.scanAwaitingConfirmation ||
            mutableState.value.scanAdded
        ) {
            return
        }
        mutableState.update { current ->
            val phase = when {
                probe.detection == null -> ScanPhase.WAITING
                probe.brightness !in 35.0..235.0 -> ScanPhase.WAITING
                probe.stableFrames > 0 -> ScanPhase.STABILIZING
                else -> ScanPhase.WAITING
            }
            val message = when {
                probe.detection == null -> "카드 전체가 보이도록 화면 안에 비춰 주세요"
                probe.brightness < 35.0 -> "조금 더 밝은 곳에서 비춰 주세요"
                probe.brightness > 235.0 -> "빛 반사를 줄여 주세요"
                probe.detection.confidence <= 0.35 && probe.stableFrames > 0 ->
                    "카드 위치 추정됨 · 잠시 고정해 주세요"
                probe.stableFrames > 0 -> "카드 영역 감지됨 · 잠시 고정해 주세요"
                else -> "카드 모서리를 추적하고 있습니다"
            }
            val updated = current.copy(probe = probe, phase = phase, statusMessage = message)
            if (updated.scanDebugEnabled) {
                updated.copy(
                    lastScanDebug = buildProbeDebugSnapshot(
                        language = updated.language,
                        probe = probe,
                        phase = phase,
                        message = message,
                    ),
                )
            } else {
                updated
            }
        }
    }

    fun onStableFrame(jpegBytes: ByteArray) {
        if (requestInFlight || mutableState.value.scanAwaitingConfirmation ||
            mutableState.value.scanAdded
        ) {
            return
        }
        requestInFlight = true
        val language = mutableState.value.language
        mutableState.update {
            it.copy(phase = ScanPhase.ANALYZING, statusMessage = "카드를 찾는 중...")
        }

        viewModelScope.launch {
            val debugCropUrl = if (mutableState.value.scanDebugEnabled) {
                runCatching { scanImageRepository.saveDebugCrop(jpegBytes) }.getOrNull()
            } else {
                null
            }
            val primaryOutcome = recognitionRepository.recognize(jpegBytes, language)
            var recognitionPath = "image-match"
            val resolvedOutcome = when (primaryOutcome) {
                is RecognitionOutcome.Match -> primaryOutcome
                RecognitionOutcome.NoMatch -> {
                    recognitionPath = "ocr-fallback"
                    recognizeFromCardText(jpegBytes, language)
                }
                is RecognitionOutcome.Unavailable -> {
                    recognitionPath = "image-match-unavailable"
                    recognizeFromCardText(jpegBytes, language).let { fallback ->
                        if (fallback is RecognitionOutcome.Match) {
                            recognitionPath = "ocr-fallback-after-unavailable"
                            fallback
                        } else {
                            primaryOutcome
                        }
                    }
                }
            }

            when (resolvedOutcome) {
                is RecognitionOutcome.Match -> {
                    val prepared = prepareScannedMatch(resolvedOutcome, jpegBytes)
                    pendingScanJpeg = jpegBytes
                    mutableState.update {
                        it.copy(
                            currentMatch = prepared.card,
                            candidates = prepared.candidates,
                            phase = ScanPhase.MATCHED,
                            scanAwaitingConfirmation = true,
                            scanAdded = false,
                            scanSaving = false,
                            statusMessage = "후보를 확인한 뒤 컬렉션에 추가해 주세요",
                            isEndpointConfigured = true,
                            lastScanDebug = it.scanDebugSnapshotForResult(
                                language = language,
                                phase = ScanPhase.MATCHED,
                                message = "후보를 확인한 뒤 컬렉션에 추가해 주세요",
                                recognitionPath = recognitionPath,
                                cropImageUrl = debugCropUrl,
                                match = prepared.card,
                                candidates = prepared.candidates,
                            ),
                        )
                    }
                }
                RecognitionOutcome.NoMatch -> mutableState.update {
                    it.copy(
                        phase = ScanPhase.WAITING,
                        statusMessage = "카드명이나 번호를 읽지 못했습니다. 잠시 고정해 주세요",
                        lastScanDebug = it.scanDebugSnapshotForResult(
                            language = language,
                            phase = ScanPhase.WAITING,
                            message = "카드명이나 번호를 읽지 못했습니다. 잠시 고정해 주세요",
                            recognitionPath = recognitionPath,
                            cropImageUrl = debugCropUrl,
                            errorMessage = "no-match",
                        ),
                    )
                }
                is RecognitionOutcome.Unavailable -> mutableState.update {
                    it.copy(
                        phase = ScanPhase.ERROR,
                        statusMessage = resolvedOutcome.message,
                        isEndpointConfigured = BuildConfig.SUPABASE_URL.isNotBlank() &&
                            BuildConfig.SUPABASE_ANON_KEY.isNotBlank(),
                        lastScanDebug = it.scanDebugSnapshotForResult(
                            language = language,
                            phase = ScanPhase.ERROR,
                            message = resolvedOutcome.message,
                            recognitionPath = recognitionPath,
                            cropImageUrl = debugCropUrl,
                            errorMessage = resolvedOutcome.message,
                        ),
                    )
                }
            }
            requestInFlight = false
        }
    }

    fun confirmScannedCard() {
        val card = mutableState.value.currentMatch ?: return
        if (!mutableState.value.scanAwaitingConfirmation ||
            mutableState.value.scanSaving
        ) {
            return
        }
        val jpegBytes = pendingScanJpeg
        viewModelScope.launch {
            mutableState.update {
                it.copy(scanSaving = true, statusMessage = "컬렉션에 저장 중...")
            }
            var cardToSave = card
            var imageUploaded = card.imageUrl?.startsWith("file:") != true
            if (jpegBytes != null && (
                    card.imageUrl == null ||
                        card.imageUrl.startsWith("file:")
                    )
            ) {
                val localUrl = scanImageRepository.saveLocal(card, jpegBytes)
                cardToSave = card.copy(
                    imageUrl = localUrl,
                    imageHighUrl = localUrl,
                )
                val session = activeSession()
                val uploadedUrl = session?.let {
                    runCatching {
                        scanImageRepository.upload(it, card, jpegBytes)
                    }.getOrNull()
                }
                if (uploadedUrl != null) {
                    cardToSave = card.copy(
                        imageUrl = uploadedUrl,
                        imageHighUrl = uploadedUrl,
                    )
                    imageUploaded = true
                }
            }

            mutableState.update { current ->
                val updatedCollection = incrementCard(current.sessionCards, cardToSave)
                current.copy(
                    currentMatch = cardToSave,
                    candidates = current.candidates.map {
                        if (sameCard(it, cardToSave)) cardToSave else it
                    },
                    sessionCards = updatedCollection,
                    recentScanCards = incrementCard(current.recentScanCards, cardToSave),
                    scanAwaitingConfirmation = false,
                    scanAdded = true,
                    scanSaving = true,
                    statusMessage = "${cardToSave.name} 1장 추가 완료",
                )
            }
            val item = mutableState.value.sessionCards
                .firstOrNull { matchesRecognized(it, cardToSave) }
            val saved = item?.let { persistCard(it) } ?: false
            mutableState.update {
                it.copy(
                    scanSaving = false,
                    statusMessage = when {
                        saved && imageUploaded -> "${cardToSave.name} 1장 추가 완료"
                        saved -> "${cardToSave.name} 1장 추가 완료 · 사진은 이 기기에 저장됨"
                        else -> "${cardToSave.name}은 추가했지만 클라우드 저장에 실패했습니다"
                    },
                )
            }
            showNotice(
                if (saved) {
                    "${cardToSave.name} 카드가 컬렉션에 추가되었습니다."
                } else {
                    "카드는 추가했지만 클라우드 저장에 실패했습니다."
                },
            )
        }
    }

    fun startNextScan() {
        pendingScanJpeg = null
        requestInFlight = false
        mutableState.update {
            it.copy(
                phase = ScanPhase.WAITING,
                probe = FrameProbe(),
                currentMatch = null,
                candidates = emptyList(),
                scanAwaitingConfirmation = false,
                scanAdded = false,
                scanSaving = false,
                statusMessage = "다음 카드를 화면 안에 보여 주세요",
            )
        }
    }

    fun saveQuantity(collectionKey: String, quantity: Int) {
        if (quantity <= 0) return
        val before = mutableState.value.sessionCards.firstOrNull {
            it.collectionKey == collectionKey
        }
            ?: return
        if (before.quantity == quantity) return
        viewModelScope.launch {
            val saved = persistCard(before.copy(quantity = quantity))
            showNotice(
                if (saved) {
                    "${before.card.name} 수량을 ${quantity}장으로 저장했습니다."
                } else {
                    "수량 저장에 실패했습니다. 다시 시도해 주세요."
                },
            )
        }
    }

    fun deleteCollectionCard(collectionKey: String) {
        val item = mutableState.value.sessionCards.firstOrNull {
            it.collectionKey == collectionKey
        } ?: return
        viewModelScope.launch {
            val session = activeSession() ?: return@launch
            val deleted = item.cloudId?.let { cloudId ->
                runSync { collectionRepository.delete(session, cloudId) }
            } ?: true
            if (deleted) {
                mutableState.update { current ->
                    current.copy(
                        sessionCards = current.sessionCards.filterNot {
                            it.collectionKey == collectionKey
                        },
                        recentScanCards = current.recentScanCards.filterNot {
                            it.collectionKey == collectionKey
                        },
                        favoriteCardIds = current.favoriteCardIds - collectionKey,
                    )
                }
            }
            showNotice(
                if (deleted) {
                    "${item.card.name} 카드를 컬렉션에서 삭제했습니다."
                } else {
                    "카드 삭제에 실패했습니다. 다시 시도해 주세요."
                },
            )
        }
    }

    fun toggleFavorite(collectionKey: String) {
        mutableState.update { current ->
            val nextFavorite = collectionKey !in current.favoriteCardIds
            val favorites = current.favoriteCardIds.toMutableSet().apply {
                if (nextFavorite) add(collectionKey) else remove(collectionKey)
            }
            current.copy(
                favoriteCardIds = favorites,
                sessionCards = current.sessionCards.map { item ->
                    if (item.collectionKey == collectionKey) {
                        item.copy(isFavorite = nextFavorite)
                    } else {
                        item
                    }
                },
                recentScanCards = current.recentScanCards.map { item ->
                    if (item.collectionKey == collectionKey) {
                        item.copy(isFavorite = nextFavorite)
                    } else {
                        item
                    }
                },
            )
        }
        viewModelScope.launch {
            mutableState.value.sessionCards.firstOrNull {
                it.collectionKey == collectionKey
            }
                ?.let { persistCard(it) }
        }
    }

    fun clearSession() {
        pendingScanJpeg = null
        mutableState.update {
            it.copy(
                recentScanCards = emptyList(),
                currentMatch = null,
                candidates = emptyList(),
                phase = ScanPhase.WAITING,
                scanAwaitingConfirmation = false,
                scanAdded = false,
                scanSaving = false,
                statusMessage = "새 스캔을 시작합니다",
            )
        }
    }

    fun selectCandidate(candidate: RecognizedCard) {
        mutableState.update { current ->
            val selected = if (candidate.imageUrl == null) {
                val localUrl = current.currentMatch?.imageUrl
                    ?.takeIf { it.startsWith("file:") }
                candidate.copy(
                    imageUrl = localUrl,
                    imageHighUrl = localUrl,
                )
            } else {
                candidate
            }
            current.copy(
                currentMatch = selected,
                scanAwaitingConfirmation = true,
                scanAdded = false,
                statusMessage = "${candidate.name} 선택됨 · 추가 버튼을 눌러 주세요",
                lastScanDebug = current.scanDebugSnapshotForResult(
                    language = current.language,
                    phase = ScanPhase.MATCHED,
                    message = "${candidate.name} 선택됨 · 추가 버튼을 눌러 주세요",
                    recognitionPath = current.lastScanDebug?.recognitionPath
                        ?.ifBlank { "manual-candidate" }
                        ?: "manual-candidate",
                    cropImageUrl = current.lastScanDebug?.cropImageUrl,
                    match = selected,
                    candidates = current.candidates,
                ),
            )
        }
    }

    override fun onCleared() {
        imageEmbedder.close()
        cardTextRecognizer.close()
        super.onCleared()
    }

    private fun restoreSession() {
        viewModelScope.launch {
            if (!authRepository.isConfigured) {
                mutableState.update {
                    it.copy(
                        authStatus = AuthStatus.SIGNED_OUT,
                        authMessage = "Supabase 설정이 필요합니다.",
                    )
                }
                return@launch
            }
            val session = authRepository.restoreSession()
            if (session == null) {
                mutableState.update { it.copy(authStatus = AuthStatus.SIGNED_OUT) }
            } else {
                applySession(session)
                loadCollection(session)
            }
        }
    }

    private fun runAuthRequest(
        keepSignedIn: Boolean = false,
        request: suspend () -> AuthOutcome,
    ) {
        viewModelScope.launch {
            mutableState.update { it.copy(authBusy = true, authMessage = "") }
            when (val outcome = request()) {
                is AuthOutcome.SignedIn -> {
                    applySession(outcome.session)
                    if (!keepSignedIn || mutableState.value.sessionCards.isEmpty()) {
                        loadCollection(outcome.session)
                    } else {
                        mutableState.update {
                            it.copy(authBusy = false, authMessage = "저장되었습니다.")
                        }
                    }
                }
                is AuthOutcome.Notice -> mutableState.update {
                    it.copy(authBusy = false, authMessage = outcome.message)
                }
                is AuthOutcome.Failure -> mutableState.update {
                    it.copy(authBusy = false, authMessage = outcome.message)
                }
            }
        }
    }

    private fun runAuthenticatedRequest(
        request: suspend (SupabaseSession) -> AuthOutcome,
    ) {
        viewModelScope.launch {
            mutableState.update { it.copy(authBusy = true, authMessage = "") }
            val session = activeSession()
            if (session == null) {
                mutableState.update { it.copy(authBusy = false) }
                return@launch
            }
            when (val outcome = request(session)) {
                is AuthOutcome.SignedIn -> {
                    applySession(outcome.session)
                    mutableState.update {
                        it.copy(authBusy = false, authMessage = "저장되었습니다.")
                    }
                }
                is AuthOutcome.Notice -> mutableState.update {
                    it.copy(authBusy = false, authMessage = outcome.message)
                }
                is AuthOutcome.Failure -> mutableState.update {
                    it.copy(authBusy = false, authMessage = outcome.message)
                }
            }
        }
    }

    private fun applySession(session: SupabaseSession) {
        authSession = session
        mutableState.update {
            it.copy(
                authStatus = AuthStatus.SIGNED_IN,
                authUser = session.user,
                authBusy = false,
                authMessage = "",
            )
        }
        viewModelScope.launch {
            val currency = runCatching {
                collectionRepository.loadDisplayCurrency(session)
            }.getOrDefault("JPY")
            mutableState.update { it.copy(displayCurrency = currency) }
        }
    }

    private suspend fun loadCollection(
        session: SupabaseSession,
        refreshAllDetails: Boolean = false,
    ) {
        mutableState.update {
            it.copy(isSyncing = true, syncMessage = "Supabase 동기화 중")
        }
        val cards = runCatching {
            collectionRepository.loadCollection(session)
        }.getOrElse { error ->
            mutableState.update {
                it.copy(
                    isSyncing = false,
                    syncMessage = error.message ?: "컬렉션 동기화에 실패했습니다.",
                    authBusy = false,
                )
            }
            return
        }
        val cardsWithLocalImages = cards.map { item ->
            if (item.card.imageUrl != null) {
                item
            } else {
                scanImageRepository.localImageUrl(item.card)?.let { localUrl ->
                    item.copy(
                        card = item.card.copy(
                            imageUrl = localUrl,
                            imageHighUrl = localUrl,
                        ),
                    )
                } ?: item
            }
        }
        mutableState.update {
            it.copy(
                sessionCards = cardsWithLocalImages,
                favoriteCardIds = cardsWithLocalImages.filter { card -> card.isFavorite }
                    .map { card -> card.collectionKey }
                    .toSet(),
                isSyncing = false,
                syncMessage = "${cardsWithLocalImages.size}종류 동기화 완료",
                authBusy = false,
            )
        }
        refreshCardDetails(session, cardsWithLocalImages, refreshAllDetails)
    }

    private suspend fun refreshCardDetails(
        session: SupabaseSession,
        cards: List<SessionCard>,
        refreshAll: Boolean,
    ) {
        val targets = cards.filter { item ->
            val needsJapaneseMarket = item.card.language == CardLanguage.JAPANESE &&
                item.card.priceSource != "yuyu-tei"
            item.card.source == "tcgdex" &&
                (
                    refreshAll ||
                        needsJapaneseMarket ||
                        item.card.marketPrice == null ||
                        item.card.priceSource == "estimated-rarity" ||
                        isScanFallbackImage(item.card.imageUrl)
                    )
        }
        if (targets.isEmpty()) return

        mutableState.update {
            it.copy(
                isSyncing = true,
                syncMessage = if (refreshAll) {
                    "최신 가격과 이미지 확인 중"
                } else {
                    "누락된 카드 정보 확인 중"
                },
            )
        }
        val refreshed = targets.chunked(4).flatMap { chunk ->
            coroutineScope {
                chunk.map { item ->
                    async {
                        item to runCatching {
                            cardSearchRepository.fetchCard(item.card)
                        }.getOrDefault(item.card)
                    }
                }.awaitAll()
            }
        }

        var updatedCount = 0
        for ((item, card) in refreshed) {
            if (card == item.card) continue
            val updatedItem = item.copy(card = card)
            val saved = runCatching {
                collectionRepository.saveExact(
                    session = session,
                    item = updatedItem,
                    isFavorite = item.isFavorite,
                )
            }.getOrDefault(updatedItem)
            val localImage = updatedItem.card.imageUrl?.takeIf { it.startsWith("file:") }
            val merged = if (saved.card.imageUrl == null && localImage != null) {
                saved.copy(
                    card = saved.card.copy(
                        imageUrl = localImage,
                        imageHighUrl = localImage,
                    ),
                )
            } else {
                saved
            }
            mutableState.update { current ->
                current.copy(
                    sessionCards = current.sessionCards.map {
                        if (it.collectionKey == item.collectionKey) merged else it
                    },
                    recentScanCards = current.recentScanCards.map {
                        if (it.collectionKey == item.collectionKey) merged else it
                    },
                )
            }
            updatedCount += 1
        }
        mutableState.update {
            it.copy(
                isSyncing = false,
                syncMessage = if (updatedCount > 0) {
                    "${updatedCount}종류 가격·이미지 갱신 완료"
                } else {
                    "최신 카드 정보입니다"
                },
            )
        }
    }

    private suspend fun recognizeFromCardText(
        jpegBytes: ByteArray,
        language: CardLanguage,
    ): RecognitionOutcome {
        mutableState.update {
            it.copy(phase = ScanPhase.ANALYZING, statusMessage = "카드명과 번호 확인 중...")
        }
        val hints = runCatching {
            cardTextRecognizer.recognize(jpegBytes, language)
        }.getOrElse { return RecognitionOutcome.NoMatch }
        val cards = runCatching {
            cardSearchRepository.matchScanText(hints, language)
        }.getOrElse { return RecognitionOutcome.NoMatch }
        val best = cards.firstOrNull { it.confidence >= MIN_TEXT_MATCH_CONFIDENCE }
            ?: return RecognitionOutcome.NoMatch
        return RecognitionOutcome.Match(best, cards)
    }

    private suspend fun prepareScannedMatch(
        outcome: RecognitionOutcome.Match,
        jpegBytes: ByteArray,
    ): RecognitionOutcome.Match {
        val detailedCandidates = outcome.candidates
            .distinctBy { "${it.language.code}:${it.id}" }
            .take(6)
            .chunked(3)
            .flatMap { chunk ->
                coroutineScope {
                    chunk.map { candidate ->
                        async {
                            runCatching {
                                cardSearchRepository.fetchCard(candidate)
                            }.getOrDefault(candidate)
                        }
                    }.awaitAll()
                }
            }
        var card = detailedCandidates.firstOrNull {
            sameCard(it, outcome.card)
        } ?: runCatching {
            cardSearchRepository.fetchCard(outcome.card)
        }.getOrDefault(outcome.card)
        if (card.imageUrl == null) {
            val localUrl = scanImageRepository.saveLocal(card, jpegBytes)
            card = card.copy(imageUrl = localUrl, imageHighUrl = localUrl)
        }
        val candidates = detailedCandidates.map {
            if (it.id == card.id && it.language == card.language) card else it
        }.let { current ->
            if (current.none { it.id == card.id && it.language == card.language }) {
                listOf(card) + current
            } else {
                current
            }
        }
        return RecognitionOutcome.Match(card, candidates)
    }

    private suspend fun persistCard(item: SessionCard): Boolean {
        val session = activeSession() ?: return false
        return runSync {
            val saved = collectionRepository.saveExact(
                session = session,
                item = item,
                isFavorite = item.collectionKey in mutableState.value.favoriteCardIds,
            )
            val localImage = item.card.imageUrl?.takeIf { it.startsWith("file:") }
            val merged = if (saved.card.imageUrl == null && localImage != null) {
                saved.copy(
                    card = saved.card.copy(
                        imageUrl = localImage,
                        imageHighUrl = localImage,
                    ),
                )
            } else {
                saved
            }
            mutableState.update { current ->
                current.copy(
                    sessionCards = current.sessionCards.map {
                        if (it.collectionKey == merged.collectionKey) merged else it
                    },
                    recentScanCards = current.recentScanCards.map {
                        if (it.collectionKey == merged.collectionKey) merged else it
                    },
                )
            }
        }
    }

    private suspend fun runSync(block: suspend () -> Unit): Boolean {
        mutableState.update { it.copy(isSyncing = true, syncMessage = "Supabase 저장 중") }
        return runCatching { block() }
            .fold(
                onSuccess = {
                    mutableState.update {
                        it.copy(isSyncing = false, syncMessage = "동기화 완료")
                    }
                    true
                },
                onFailure = { error ->
                    mutableState.update {
                        it.copy(
                            isSyncing = false,
                            syncMessage = error.message ?: "동기화에 실패했습니다.",
                        )
                    }
                    false
                },
            )
    }

    private suspend fun activeSession(): SupabaseSession? {
        val current = authSession ?: return null
        val fresh = authRepository.ensureFresh(current)
        if (fresh == null) {
            authSession = null
            mutableState.update {
                it.copy(
                    authStatus = AuthStatus.SIGNED_OUT,
                    authUser = null,
                    authMessage = "세션이 만료되었습니다. 다시 로그인해 주세요.",
                    sessionCards = emptyList(),
                    recentScanCards = emptyList(),
                    favoriteCardIds = emptySet(),
                )
            }
            return null
        }
        if (fresh.accessToken != current.accessToken) {
            authSession = fresh
            mutableState.update { it.copy(authUser = fresh.user) }
        }
        return fresh
    }

    private fun setAuthMessage(message: String) {
        mutableState.update { it.copy(authMessage = message) }
    }

    private fun showNotice(message: String) {
        mutableState.update {
            it.copy(
                noticeMessage = message,
                noticeId = it.noticeId + 1L,
            )
        }
    }

    private fun incrementCard(
        cards: List<SessionCard>,
        card: RecognizedCard,
    ): List<SessionCard> {
        val existing = cards.firstOrNull { matchesRecognized(it, card) }
        return if (existing == null) {
            listOf(SessionCard(card, addedAt = Instant.now().toString())) + cards
        } else {
            cards.map {
                if (matchesRecognized(it, card)) it.copy(quantity = it.quantity + 1) else it
            }
        }
    }

    private fun matchesRecognized(
        item: SessionCard,
        card: RecognizedCard,
    ): Boolean = item.card.id == card.id &&
        item.card.source == card.source &&
        item.card.language == card.language &&
        item.condition == "NM" &&
        item.finish == "normal"

    private fun buildProbeDebugSnapshot(
        language: CardLanguage,
        probe: FrameProbe,
        phase: ScanPhase,
        message: String,
    ): ScanDebugSnapshot = ScanDebugSnapshot(
        capturedAt = Instant.now().toString(),
        language = language,
        phase = phase,
        statusMessage = message,
        brightness = probe.brightness,
        motion = probe.motion,
        stableFrames = probe.stableFrames,
        detectionConfidence = probe.detection?.confidence ?: 0.0,
        cornerSummary = cornerSummary(probe.detection),
    )

    private fun ScannerUiState.scanDebugSnapshotForResult(
        language: CardLanguage,
        phase: ScanPhase,
        message: String,
        recognitionPath: String,
        cropImageUrl: String?,
        match: RecognizedCard? = null,
        candidates: List<RecognizedCard> = emptyList(),
        errorMessage: String = "",
    ): ScanDebugSnapshot? {
        if (!scanDebugEnabled) return lastScanDebug
        return ScanDebugSnapshot(
            capturedAt = Instant.now().toString(),
            language = language,
            phase = phase,
            statusMessage = message,
            brightness = probe.brightness,
            motion = probe.motion,
            stableFrames = probe.stableFrames,
            detectionConfidence = probe.detection?.confidence ?: 0.0,
            cornerSummary = cornerSummary(probe.detection),
            cropImageUrl = cropImageUrl ?: lastScanDebug?.cropImageUrl,
            recognizedCardName = match?.name.orEmpty(),
            recognizedSetName = match?.setName.orEmpty(),
            recognizedNumber = match?.number.orEmpty(),
            recognizedConfidence = match?.confidence ?: 0.0,
            recognitionPath = recognitionPath,
            errorMessage = errorMessage,
            candidates = candidates.take(6).map { it.toDebugCandidate(this) },
        )
    }

    private fun RecognizedCard.toDebugCandidate(
        state: ScannerUiState,
    ): ScanDebugCandidate = ScanDebugCandidate(
        name = name,
        setName = setName,
        number = number,
        confidence = confidence,
        source = source,
        priceSource = priceSource,
        priceText = marketPrice?.let {
            "${state.displayCurrency} ${
                String.format(Locale.US, "%.2f", state.convertedPrice(this@toDebugCandidate))
            }"
        }.orEmpty(),
    )

    private fun cornerSummary(detection: CardDetection?): String {
        if (detection == null || detection.corners.isEmpty()) return ""
        return detection.corners.joinToString(" / ") { point ->
            "${(point.x * 100).roundToInt()},${(point.y * 100).roundToInt()}"
        }
    }

    private companion object {
        const val MIN_TEXT_MATCH_CONFIDENCE = 0.52
    }
}

private fun SortDirection.toggled(): SortDirection =
    if (this == SortDirection.ASCENDING) {
        SortDirection.DESCENDING
    } else {
        SortDirection.ASCENDING
    }

private fun sameCard(
    first: RecognizedCard,
    second: RecognizedCard,
): Boolean = first.id == second.id &&
    first.language == second.language &&
    first.source == second.source

private fun isScanFallbackImage(value: String?): Boolean =
    value.isNullOrBlank() ||
        value.startsWith("file:") ||
        value.contains("/card-scans/")
