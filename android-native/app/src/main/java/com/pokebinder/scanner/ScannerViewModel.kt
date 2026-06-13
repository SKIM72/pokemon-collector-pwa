package com.pokebinder.scanner

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pokebinder.scanner.data.CardRecognitionRepository
import com.pokebinder.scanner.data.EdgeFunctionCardRecognitionRepository
import com.pokebinder.scanner.model.CardLanguage
import com.pokebinder.scanner.model.FrameProbe
import com.pokebinder.scanner.model.RecognitionOutcome
import com.pokebinder.scanner.model.RecognizedCard
import com.pokebinder.scanner.model.ScanPhase
import com.pokebinder.scanner.model.ScannerUiState
import com.pokebinder.scanner.model.SessionCard
import com.pokebinder.scanner.scanner.CardImageEmbedder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val imageEmbedder = CardImageEmbedder(application)
    private val repository: CardRecognitionRepository =
        EdgeFunctionCardRecognitionRepository(imageEmbedder)

    private val mutableState = MutableStateFlow(
        ScannerUiState(
            isEndpointConfigured = BuildConfig.SUPABASE_URL.isNotBlank() &&
                BuildConfig.SUPABASE_ANON_KEY.isNotBlank(),
        ),
    )
    val state: StateFlow<ScannerUiState> = mutableState.asStateFlow()

    private var requestInFlight = false
    private var lastMatchedId: String? = null
    private var lastMatchedAt = 0L

    fun selectLanguage(language: CardLanguage) {
        mutableState.update {
            it.copy(
                language = language,
                phase = ScanPhase.WAITING,
                currentMatch = null,
                candidates = emptyList(),
                statusMessage = "카드를 가이드 안에 맞춰 주세요",
            )
        }
    }

    fun onFrameProbe(probe: FrameProbe) {
        if (requestInFlight) return
        mutableState.update { current ->
            val phase = when {
                probe.brightness !in 35.0..235.0 -> ScanPhase.WAITING
                probe.stableFrames > 0 -> ScanPhase.STABILIZING
                else -> ScanPhase.WAITING
            }
            val message = when {
                probe.brightness < 35.0 -> "조금 더 밝은 곳에서 비춰 주세요"
                probe.brightness > 235.0 -> "빛 반사를 줄여 주세요"
                probe.stableFrames > 0 -> "카드를 고정해 주세요"
                else -> "카드를 가이드 안에 맞춰 주세요"
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
            when (val outcome = repository.recognize(jpegBytes, language)) {
                is RecognitionOutcome.Match -> applyMatch(outcome)
                RecognitionOutcome.NoMatch -> mutableState.update {
                    it.copy(
                        phase = ScanPhase.WAITING,
                        statusMessage = "일치 카드가 없습니다. 각도와 거리를 조정해 주세요",
                    )
                }
                is RecognitionOutcome.Unavailable -> mutableState.update {
                    it.copy(
                        phase = ScanPhase.ERROR,
                        statusMessage = outcome.message,
                        isEndpointConfigured = false,
                    )
                }
            }
            requestInFlight = false
        }
    }

    fun changeQuantity(cardId: String, delta: Int) {
        mutableState.update { current ->
            val nextCards = current.sessionCards.mapNotNull { item ->
                if (item.card.id != cardId) return@mapNotNull item
                val next = item.quantity + delta
                if (next <= 0) null else item.copy(quantity = next)
            }
            current.copy(
                sessionCards = nextCards,
                favoriteCardIds = current.favoriteCardIds.intersect(
                    nextCards.map { it.card.id }.toSet(),
                ),
            )
        }
    }

    fun toggleFavorite(cardId: String) {
        mutableState.update { current ->
            val favorites = current.favoriteCardIds.toMutableSet().apply {
                if (!add(cardId)) remove(cardId)
            }
            current.copy(favoriteCardIds = favorites)
        }
    }

    fun clearSession() {
        mutableState.update {
            it.copy(
                sessionCards = emptyList(),
                favoriteCardIds = emptySet(),
                currentMatch = null,
                candidates = emptyList(),
                phase = ScanPhase.WAITING,
                statusMessage = "새 스캔을 시작합니다",
            )
        }
    }

    fun selectCandidate(candidate: RecognizedCard) {
        mutableState.update { current ->
            val previous = current.currentMatch ?: return@update current
            if (previous.id == candidate.id) return@update current

            val withoutPrevious = decrementCard(current.sessionCards, previous.id)
            val existingCandidate = withoutPrevious.firstOrNull { it.card.id == candidate.id }
            val correctedSession = if (existingCandidate == null) {
                listOf(SessionCard(candidate)) + withoutPrevious
            } else {
                withoutPrevious.map {
                    if (it.card.id == candidate.id) it.copy(quantity = it.quantity + 1) else it
                }
            }

            lastMatchedId = candidate.id
            lastMatchedAt = System.currentTimeMillis()
            current.copy(
                currentMatch = candidate,
                sessionCards = correctedSession,
                statusMessage = "${candidate.name}(으)로 후보를 변경했습니다",
            )
        }
    }

    override fun onCleared() {
        imageEmbedder.close()
        super.onCleared()
    }

    private fun applyMatch(outcome: RecognitionOutcome.Match) {
        val card = outcome.card
        val now = System.currentTimeMillis()
        val isImmediateDuplicate = lastMatchedId == card.id && now - lastMatchedAt < 3_500L
        lastMatchedId = card.id
        lastMatchedAt = now

        mutableState.update { current ->
            val updatedSession = if (isImmediateDuplicate) {
                current.sessionCards
            } else {
                val existing = current.sessionCards.firstOrNull { it.card.id == card.id }
                if (existing == null) {
                    listOf(SessionCard(card)) + current.sessionCards
                } else {
                    current.sessionCards.map {
                        if (it.card.id == card.id) it.copy(quantity = it.quantity + 1) else it
                    }
                }
            }
            current.copy(
                currentMatch = card,
                candidates = outcome.candidates,
                sessionCards = updatedSession,
                phase = ScanPhase.MATCHED,
                statusMessage = if (isImmediateDuplicate) {
                    "같은 카드입니다. 다른 카드를 비춰 주세요"
                } else {
                    "${card.name} 인식 완료"
                },
                isEndpointConfigured = true,
            )
        }
    }

    private fun decrementCard(
        cards: List<SessionCard>,
        cardId: String,
    ): List<SessionCard> = cards.mapNotNull { item ->
        if (item.card.id != cardId) return@mapNotNull item
        if (item.quantity <= 1) null else item.copy(quantity = item.quantity - 1)
    }
}
