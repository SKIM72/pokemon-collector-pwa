package com.pokebinder.scanner

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pokebinder.scanner.data.AuthOutcome
import com.pokebinder.scanner.data.CardRecognitionRepository
import com.pokebinder.scanner.data.CardScanImageRepository
import com.pokebinder.scanner.data.EdgeFunctionCardRecognitionRepository
import com.pokebinder.scanner.data.SupabaseAuthRepository
import com.pokebinder.scanner.data.SupabaseCollectionRepository
import com.pokebinder.scanner.data.SupabaseSession
import com.pokebinder.scanner.data.TcgDexRepository
import com.pokebinder.scanner.model.AuthStatus
import com.pokebinder.scanner.model.CardLanguage
import com.pokebinder.scanner.model.FrameProbe
import com.pokebinder.scanner.model.RecognitionOutcome
import com.pokebinder.scanner.model.RecognizedCard
import com.pokebinder.scanner.model.ScanPhase
import com.pokebinder.scanner.model.ScannerUiState
import com.pokebinder.scanner.model.SessionCard
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

class ScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val imageEmbedder = CardImageEmbedder(application)
    private val cardTextRecognizer = CardTextRecognizer()
    private val recognitionRepository: CardRecognitionRepository =
        EdgeFunctionCardRecognitionRepository(imageEmbedder)
    private val authRepository = SupabaseAuthRepository(application)
    private val collectionRepository = SupabaseCollectionRepository()
    private val scanImageRepository = CardScanImageRepository()
    private val cardSearchRepository = TcgDexRepository()

    private val mutableState = MutableStateFlow(
        ScannerUiState(
            isEndpointConfigured = BuildConfig.SUPABASE_URL.isNotBlank() &&
                BuildConfig.SUPABASE_ANON_KEY.isNotBlank(),
        ),
    )
    val state: StateFlow<ScannerUiState> = mutableState.asStateFlow()

    private var authSession: SupabaseSession? = null
    private var requestInFlight = false
    private var lastMatchedId: String? = null
    private var lastMatchedAt = 0L

    init {
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

    fun selectSearchLanguage(language: CardLanguage) {
        mutableState.update {
            it.copy(
                searchLanguage = language,
                searchResults = emptyList(),
                searchMessage = when (language) {
                    CardLanguage.JAPANESE -> "일본판 카드를 우선 검색합니다"
                    CardLanguage.ENGLISH -> "영문판 카드를 검색합니다"
                    CardLanguage.KOREAN -> "한국판 데이터는 아직 제한적입니다"
                },
            )
        }
    }

    fun searchCards(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            mutableState.update { it.copy(searchMessage = "검색어를 입력해 주세요") }
            return
        }
        val language = mutableState.value.searchLanguage
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    searchBusy = true,
                    searchResults = emptyList(),
                    searchMessage = if (language == CardLanguage.JAPANESE) {
                        "일본판 카드 검색 중"
                    } else {
                        "${language.label} 카드 검색 중"
                    },
                )
            }
            runCatching {
                cardSearchRepository.search(trimmed, language)
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
        mutableState.update {
            it.copy(
                language = language,
                phase = ScanPhase.WAITING,
                currentMatch = null,
                candidates = emptyList(),
                statusMessage = "화면 안에 카드를 보여 주세요",
            )
        }
    }

    fun onFrameProbe(probe: FrameProbe) {
        if (requestInFlight) return
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
            current.copy(probe = probe, phase = phase, statusMessage = message)
        }
    }

    fun onStableFrame(jpegBytes: ByteArray) {
        if (requestInFlight) return
        requestInFlight = true
        val language = mutableState.value.language
        mutableState.update {
            it.copy(phase = ScanPhase.ANALYZING, statusMessage = "카드를 찾는 중...")
        }

        viewModelScope.launch {
            val primaryOutcome = recognitionRepository.recognize(jpegBytes, language)
            val resolvedOutcome = when (primaryOutcome) {
                is RecognitionOutcome.Match -> primaryOutcome
                RecognitionOutcome.NoMatch -> recognizeFromCardText(jpegBytes, language)
                is RecognitionOutcome.Unavailable -> {
                    recognizeFromCardText(jpegBytes, language).let { fallback ->
                        if (fallback is RecognitionOutcome.Match) fallback else primaryOutcome
                    }
                }
            }

            when (resolvedOutcome) {
                is RecognitionOutcome.Match -> {
                    val prepared = prepareScannedMatch(resolvedOutcome, jpegBytes)
                    val shouldPersist = applyMatch(prepared)
                    if (shouldPersist) syncCard(prepared.card)
                }
                RecognitionOutcome.NoMatch -> mutableState.update {
                    it.copy(
                        phase = ScanPhase.WAITING,
                        statusMessage = "카드명이나 번호를 읽지 못했습니다. 잠시 고정해 주세요",
                    )
                }
                is RecognitionOutcome.Unavailable -> mutableState.update {
                    it.copy(
                        phase = ScanPhase.ERROR,
                        statusMessage = resolvedOutcome.message,
                        isEndpointConfigured = BuildConfig.SUPABASE_URL.isNotBlank() &&
                            BuildConfig.SUPABASE_ANON_KEY.isNotBlank(),
                    )
                }
            }
            requestInFlight = false
        }
    }

    fun changeQuantity(collectionKey: String, delta: Int) {
        val before = mutableState.value.sessionCards.firstOrNull {
            it.collectionKey == collectionKey
        }
            ?: return
        mutableState.update { current ->
            val nextCards = current.sessionCards.mapNotNull { item ->
                if (item.collectionKey != collectionKey) return@mapNotNull item
                val next = item.quantity + delta
                if (next <= 0) null else item.copy(quantity = next)
            }
            current.copy(
                sessionCards = nextCards,
                recentScanCards = current.recentScanCards.mapNotNull { item ->
                    if (item.collectionKey != collectionKey) return@mapNotNull item
                    val collectionItem = nextCards.firstOrNull {
                        it.collectionKey == collectionKey
                    }
                    collectionItem?.copy()
                },
                favoriteCardIds = current.favoriteCardIds.intersect(
                    nextCards.map { it.collectionKey }.toSet(),
                ),
            )
        }
        viewModelScope.launch {
            val after = mutableState.value.sessionCards.firstOrNull {
                it.collectionKey == collectionKey
            }
            persistChange(before, after)
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
        mutableState.update {
            it.copy(
                recentScanCards = emptyList(),
                currentMatch = null,
                candidates = emptyList(),
                phase = ScanPhase.WAITING,
                statusMessage = "새 스캔을 시작합니다",
            )
        }
    }

    fun selectCandidate(candidate: RecognizedCard) {
        val beforeState = mutableState.value
        val previous = beforeState.currentMatch ?: return
        if (previous.id == candidate.id) return
        val previousItem = beforeState.sessionCards.firstOrNull {
            matchesRecognized(it, previous)
        }

        mutableState.update { current ->
            val withoutPrevious = decrementRecognized(current.sessionCards, previous)
            val candidateItem = withoutPrevious.firstOrNull {
                matchesRecognized(it, candidate)
            }
            val correctedCollection = if (candidateItem == null) {
                listOf(SessionCard(candidate)) + withoutPrevious
            } else {
                withoutPrevious.map {
                    if (matchesRecognized(it, candidate)) {
                        it.copy(quantity = it.quantity + 1)
                    } else {
                        it
                    }
                }
            }
            val correctedRecent = replaceRecentCandidate(
                current.recentScanCards,
                previous,
                candidate,
            )

            lastMatchedId = candidate.id
            lastMatchedAt = System.currentTimeMillis()
            current.copy(
                currentMatch = candidate,
                sessionCards = correctedCollection,
                recentScanCards = correctedRecent,
                favoriteCardIds = current.favoriteCardIds.intersect(
                    correctedCollection.map { it.collectionKey }.toSet(),
                ),
                statusMessage = "${candidate.name}(으)로 후보를 변경했습니다",
            )
        }

        viewModelScope.launch {
            val afterState = mutableState.value
            persistChange(
                previousItem,
                afterState.sessionCards.firstOrNull { matchesRecognized(it, previous) },
            )
            afterState.sessionCards.firstOrNull { matchesRecognized(it, candidate) }
                ?.let { persistCard(it) }
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
        mutableState.update {
            it.copy(
                sessionCards = cards,
                favoriteCardIds = cards.filter { card -> card.isFavorite }
                    .map { card -> card.collectionKey }
                    .toSet(),
                isSyncing = false,
                syncMessage = "${cards.size}종류 동기화 완료",
                authBusy = false,
            )
        }
        refreshCardDetails(session, cards, refreshAllDetails)
    }

    private suspend fun refreshCardDetails(
        session: SupabaseSession,
        cards: List<SessionCard>,
        refreshAll: Boolean,
    ) {
        val targets = cards.filter { item ->
            item.card.source == "tcgdex" &&
                (refreshAll || item.card.marketPrice == null || item.card.imageUrl == null)
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
            mutableState.update { current ->
                current.copy(
                    sessionCards = current.sessionCards.map {
                        if (it.collectionKey == item.collectionKey) saved else it
                    },
                    recentScanCards = current.recentScanCards.map {
                        if (it.collectionKey == item.collectionKey) saved else it
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
        var card = runCatching {
            cardSearchRepository.fetchCard(outcome.card)
        }.getOrDefault(outcome.card)
        if (card.imageUrl == null) {
            val session = activeSession()
            val uploadedUrl = session?.let {
                runCatching {
                    scanImageRepository.upload(it, card, jpegBytes)
                }.getOrNull()
            }
            if (uploadedUrl != null) {
                card = card.copy(imageUrl = uploadedUrl, imageHighUrl = uploadedUrl)
            }
        }
        val candidates = outcome.candidates.map {
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

    private fun applyMatch(outcome: RecognitionOutcome.Match): Boolean {
        val card = outcome.card
        val now = System.currentTimeMillis()
        val isImmediateDuplicate = lastMatchedId == card.id && now - lastMatchedAt < 3_500L
        lastMatchedId = card.id
        lastMatchedAt = now

        mutableState.update { current ->
            val updatedCollection = if (isImmediateDuplicate) {
                current.sessionCards
            } else {
                incrementCard(current.sessionCards, card)
            }
            val updatedRecent = if (isImmediateDuplicate) {
                current.recentScanCards
            } else {
                incrementCard(current.recentScanCards, card)
            }
            current.copy(
                currentMatch = card,
                candidates = outcome.candidates,
                sessionCards = updatedCollection,
                recentScanCards = updatedRecent,
                phase = ScanPhase.MATCHED,
                statusMessage = if (isImmediateDuplicate) {
                    "같은 카드입니다. 다른 카드를 비춰 주세요"
                } else {
                    "${card.name} 인식 완료"
                },
                isEndpointConfigured = true,
            )
        }
        return !isImmediateDuplicate
    }

    private suspend fun syncCard(card: RecognizedCard) {
        mutableState.value.sessionCards.firstOrNull { matchesRecognized(it, card) }
            ?.let { persistCard(it) }
    }

    private suspend fun persistChange(
        before: SessionCard?,
        after: SessionCard?,
    ) {
        val session = activeSession() ?: return
        if (after == null) {
            before?.cloudId?.let {
                runSync { collectionRepository.delete(session, it) }
            }
        } else {
            persistCard(after)
        }
    }

    private suspend fun persistCard(item: SessionCard): Boolean {
        val session = activeSession() ?: return false
        return runSync {
            val saved = collectionRepository.saveExact(
                session = session,
                item = item,
                isFavorite = item.collectionKey in mutableState.value.favoriteCardIds,
            )
            mutableState.update { current ->
                current.copy(
                    sessionCards = current.sessionCards.map {
                        if (it.collectionKey == saved.collectionKey) saved else it
                    },
                    recentScanCards = current.recentScanCards.map {
                        if (it.collectionKey == saved.collectionKey) saved else it
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

    private fun incrementCard(
        cards: List<SessionCard>,
        card: RecognizedCard,
    ): List<SessionCard> {
        val existing = cards.firstOrNull { matchesRecognized(it, card) }
        return if (existing == null) {
            listOf(SessionCard(card)) + cards
        } else {
            cards.map {
                if (matchesRecognized(it, card)) it.copy(quantity = it.quantity + 1) else it
            }
        }
    }

    private fun replaceRecentCandidate(
        cards: List<SessionCard>,
        previous: RecognizedCard,
        candidate: RecognizedCard,
    ): List<SessionCard> {
        val withoutPrevious = decrementRecognized(cards, previous)
        return incrementCard(withoutPrevious, candidate)
    }

    private fun decrementRecognized(
        cards: List<SessionCard>,
        card: RecognizedCard,
    ): List<SessionCard> = cards.mapNotNull { item ->
        if (!matchesRecognized(item, card)) return@mapNotNull item
        if (item.quantity <= 1) null else item.copy(quantity = item.quantity - 1)
    }

    private fun matchesRecognized(
        item: SessionCard,
        card: RecognizedCard,
    ): Boolean = item.card.id == card.id &&
        item.card.source == card.source &&
        item.card.language == card.language &&
        item.condition == "NM" &&
        item.finish == "normal"

    private companion object {
        const val MIN_TEXT_MATCH_CONFIDENCE = 0.52
    }
}
