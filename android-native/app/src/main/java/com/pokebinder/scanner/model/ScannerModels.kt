package com.pokebinder.scanner.model

import java.text.Collator
import java.util.Locale

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

enum class SortDirection(val label: String) {
    ASCENDING("오름차순"),
    DESCENDING("내림차순"),
}

enum class SearchSortField(val label: String) {
    RELEASE_DATE("출시일"),
    NAME("이름"),
    PRICE("금액"),
}

enum class CollectionSortField(val label: String) {
    RELEASE_DATE("출시일"),
    ADDED_DATE("추가일"),
    NAME("이름"),
    PRICE("금액"),
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

data class ScanDebugCandidate(
    val name: String,
    val setName: String,
    val number: String,
    val confidence: Double,
    val source: String,
    val priceSource: String,
    val priceText: String,
)

data class ScanDebugSnapshot(
    val capturedAt: String = "",
    val language: CardLanguage = CardLanguage.JAPANESE,
    val phase: ScanPhase = ScanPhase.WAITING,
    val statusMessage: String = "",
    val brightness: Double = 0.0,
    val motion: Double = 0.0,
    val stableFrames: Int = 0,
    val detectionConfidence: Double = 0.0,
    val cornerSummary: String = "",
    val cropImageUrl: String? = null,
    val recognizedCardName: String = "",
    val recognizedSetName: String = "",
    val recognizedNumber: String = "",
    val recognizedConfidence: Double = 0.0,
    val recognitionPath: String = "",
    val errorMessage: String = "",
    val candidates: List<ScanDebugCandidate> = emptyList(),
)

data class CardTextHints(
    val names: List<String>,
    val localId: String?,
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
    val releaseDate: String = "",
)

data class SessionCard(
    val card: RecognizedCard,
    val quantity: Int = 1,
    val cloudId: String? = null,
    val condition: String = "NM",
    val finish: String = "normal",
    val isFavorite: Boolean = false,
    val addedAt: String = "",
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
    val searchResults: List<RecognizedCard> = emptyList(),
    val searchBusy: Boolean = false,
    val searchMessage: String = "한 번에 일본판·영문판·한국판을 검색합니다",
    val searchSortField: SearchSortField = SearchSortField.RELEASE_DATE,
    val searchSortDirection: SortDirection = SortDirection.DESCENDING,
    val collectionSortField: CollectionSortField = CollectionSortField.ADDED_DATE,
    val collectionSortDirection: SortDirection = SortDirection.DESCENDING,
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
    val scanAwaitingConfirmation: Boolean = false,
    val scanAdded: Boolean = false,
    val scanSaving: Boolean = false,
    val scanDebugEnabled: Boolean = false,
    val lastScanDebug: ScanDebugSnapshot? = null,
    val displayCurrency: String = "JPY",
    val currencyRates: Map<String, Double> = DEFAULT_CURRENCY_RATES,
    val noticeMessage: String = "",
    val noticeId: Long = 0L,
) {
    val totalCards: Int
        get() = sessionCards.sumOf { it.quantity }

    val runningTotal: Double
        get() = sessionCards
            .sumOf { convertedPrice(it.card) * it.quantity }

    val favoriteCards: List<SessionCard>
        get() = sessionCards.filter { it.collectionKey in favoriteCardIds }

    val sortedSearchResults: List<RecognizedCard>
        get() = searchResults.sortedWith(searchComparator())

    val sortedSessionCards: List<SessionCard>
        get() = sessionCards.sortedWith(collectionComparator())

    val sortedFavoriteCards: List<SessionCard>
        get() = favoriteCards.sortedWith(collectionComparator())

    fun convertedPrice(card: RecognizedCard): Double = convertMoney(
        value = card.marketPrice ?: 0.0,
        fromCurrency = card.currency,
        toCurrency = displayCurrency,
        rates = currencyRates,
    )

    private fun searchComparator(): Comparator<RecognizedCard> {
        val valueComparator = when (searchSortField) {
            SearchSortField.RELEASE_DATE -> compareBy<RecognizedCard> { it.releaseDate }
            SearchSortField.NAME -> Comparator { first, second ->
                NAME_COLLATOR.compare(first.name, second.name)
            }
            SearchSortField.PRICE -> compareBy { convertedPrice(it) }
        }
        val directed = if (searchSortDirection == SortDirection.ASCENDING) {
            valueComparator
        } else {
            valueComparator.reversed()
        }
        return compareBy<RecognizedCard> {
            when (searchSortField) {
                SearchSortField.RELEASE_DATE -> it.releaseDate.isBlank()
                SearchSortField.NAME -> false
                SearchSortField.PRICE -> it.marketPrice == null
            }
        }.then(directed)
    }

    private fun collectionComparator(): Comparator<SessionCard> {
        val valueComparator = when (collectionSortField) {
            CollectionSortField.RELEASE_DATE -> compareBy<SessionCard> { it.card.releaseDate }
            CollectionSortField.ADDED_DATE -> compareBy { it.addedAt }
            CollectionSortField.NAME -> Comparator { first, second ->
                NAME_COLLATOR.compare(first.card.name, second.card.name)
            }
            CollectionSortField.PRICE -> compareBy { convertedPrice(it.card) }
        }
        val directed = if (collectionSortDirection == SortDirection.ASCENDING) {
            valueComparator
        } else {
            valueComparator.reversed()
        }
        return compareBy<SessionCard> {
            when (collectionSortField) {
                CollectionSortField.RELEASE_DATE -> it.card.releaseDate.isBlank()
                CollectionSortField.ADDED_DATE -> it.addedAt.isBlank()
                CollectionSortField.NAME -> false
                CollectionSortField.PRICE -> it.card.marketPrice == null
            }
        }.then(directed)
    }

    private companion object {
        val NAME_COLLATOR: Collator = Collator.getInstance(Locale.KOREAN)
    }
}

val DEFAULT_CURRENCY_RATES = mapOf(
    "USD" to 1.0,
    "JPY" to 157.0,
    "KRW" to 1_380.0,
    "EUR" to 0.92,
)

fun convertMoney(
    value: Double,
    fromCurrency: String,
    toCurrency: String,
    rates: Map<String, Double>,
): Double {
    if (value <= 0.0 || fromCurrency == toCurrency) return value
    val fromRate = rates[fromCurrency] ?: DEFAULT_CURRENCY_RATES[fromCurrency] ?: 1.0
    val toRate = rates[toCurrency] ?: DEFAULT_CURRENCY_RATES[toCurrency] ?: 1.0
    return value / fromRate * toRate
}

sealed interface RecognitionOutcome {
    data class Match(
        val card: RecognizedCard,
        val candidates: List<RecognizedCard>,
    ) : RecognitionOutcome
    object NoMatch : RecognitionOutcome
    data class Unavailable(val message: String) : RecognitionOutcome
}
