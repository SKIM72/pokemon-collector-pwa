package com.pokebinder.scanner.model

enum class CardLanguage(val code: String, val label: String) {
    JAPANESE("ja", "일본판"),
    KOREAN("ko", "한국판"),
    ENGLISH("en", "영문판"),
}

enum class ScanPhase {
    WAITING,
    STABILIZING,
    ANALYZING,
    MATCHED,
    ERROR,
}

data class FrameProbe(
    val brightness: Double = 0.0,
    val motion: Double = 1.0,
    val stableFrames: Int = 0,
)

data class RecognizedCard(
    val id: String,
    val name: String,
    val setName: String,
    val number: String,
    val imageUrl: String?,
    val marketPrice: Double?,
    val currency: String,
    val confidence: Double,
    val language: CardLanguage,
)

data class SessionCard(
    val card: RecognizedCard,
    val quantity: Int = 1,
)

data class ScannerUiState(
    val language: CardLanguage = CardLanguage.JAPANESE,
    val phase: ScanPhase = ScanPhase.WAITING,
    val probe: FrameProbe = FrameProbe(),
    val currentMatch: RecognizedCard? = null,
    val candidates: List<RecognizedCard> = emptyList(),
    val sessionCards: List<SessionCard> = emptyList(),
    val statusMessage: String = "카드를 가이드 안에 맞춰 주세요",
    val isEndpointConfigured: Boolean = true,
) {
    val totalCards: Int
        get() = sessionCards.sumOf { it.quantity }

    val runningTotal: Double
        get() = sessionCards.sumOf { (it.card.marketPrice ?: 0.0) * it.quantity }

    val displayCurrency: String
        get() = currentMatch?.currency
            ?: sessionCards.firstOrNull()?.card?.currency
            ?: when (language) {
                CardLanguage.JAPANESE -> "JPY"
                CardLanguage.KOREAN -> "KRW"
                CardLanguage.ENGLISH -> "USD"
            }
}

sealed interface RecognitionOutcome {
    data class Match(
        val card: RecognizedCard,
        val candidates: List<RecognizedCard>,
    ) : RecognitionOutcome
    object NoMatch : RecognitionOutcome
    data class Unavailable(val message: String) : RecognitionOutcome
}
