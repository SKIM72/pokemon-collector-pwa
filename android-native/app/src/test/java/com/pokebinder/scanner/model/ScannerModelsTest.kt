package com.pokebinder.scanner.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ScannerModelsTest {
    @Test
    fun convertsEuroPriceToJapaneseYen() {
        val converted = convertMoney(
            value = 1.0,
            fromCurrency = "EUR",
            toCurrency = "JPY",
            rates = mapOf(
                "USD" to 1.0,
                "EUR" to 0.9,
                "JPY" to 150.0,
            ),
        )

        assertEquals(166.67, converted, 0.01)
    }

    @Test
    fun keepsSameCurrencyValue() {
        val rates = DEFAULT_CURRENCY_RATES

        assertEquals(320.0, convertMoney(320.0, "JPY", "JPY", rates), 0.0)
        assertSame(rates, DEFAULT_CURRENCY_RATES)
    }
}
