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

enum class AuthStatus {
    RESTORING,
    SIGNED_OUT,
    SIGNED_IN,
}

data class AuthUser(
    val id: String,
    val email: String,
    val username: String = "",
)

data class FrameProbe(
    val brightness: Double = 0.0,
    val motion: Double = 1.0,
    val stableFrames: Int = 0,
    val detection: CardDetection? = null,
)

data class ScanPoint(
    val x: Float,
    val y: Float,
)

data class CardDetection(
    val corners: List<ScanPoint>,
    val confidence: Double,
    val frameWidth: Int,
    val frameHeight: Int,
)

data class RecognizedCard(
    val id: String,
    val name: String,
    val setName: String,
    val number: String,
    val imageUrl: String?,
    val marketPrice: Double?,
    val currency: String,
    val priceSource: String = "tcgdex",
    val confidence: Double,
    val language: CardLanguage,
    val source: String = "tcgdex",
    val setId: String = "",
    val rarity: String = "",
    val imageHighUrl: String? = null,
)

data class SessionCard(
    val card: RecognizedCard,
    val quantity: Int = 1,
    val cloudId: String? = null,
    val condition: String = "NM",
    val finish: String = "normal",
    val isFavorite: Boolean = false,
) {
    val collectionKey: String
        get() = "${card.source}:${card.language.code}:${card.id}:$condition:$finish"
}

data class ScannerUiState(
    val authStatus: AuthStatus = AuthStatus.RESTORING,
    val authUser: AuthUser? = null,
    val authBusy: Boolean = false,
    val authMessage: String = "",
    val isSyncing: Boolean = false,
    val syncMessage: String = "",
    val searchLanguage: CardLanguage = CardLanguage.JAPANESE,
    val searchResults: List<RecognizedCard> = emptyList(),
    val searchBusy: Boolean = false,
    val searchMessage: String = "일본판 카드를 우선 검색합니다",
    val language: CardLanguage = CardLanguage.JAPANESE,
    val phase: ScanPhase = ScanPhase.WAITING,
    val probe: FrameProbe = FrameProbe(),
    val currentMatch: RecognizedCard? = null,
    val candidates: List<RecognizedCard> = emptyList(),
    val sessionCards: List<SessionCard> = emptyList(),
    val recentScanCards: List<SessionCard> = emptyList(),
    val favoriteCardIds: Set<String> = emptySet(),
    val statusMessage: String = "화면 안에 카드를 보여 주세요",
    val isEndpointConfigured: Boolean = true,
) {
    val totalCards: Int
        get() = sessionCards.sumOf { it.quantity }

    val runningTotal: Double
        get() = sessionCards
            .filter { it.card.currency == displayCurrency }
            .sumOf { (it.card.marketPrice ?: 0.0) * it.quantity }

    val favoriteCards: List<SessionCard>
        get() = sessionCards.filter { it.collectionKey in favoriteCardIds }

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
