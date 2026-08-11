package com.bingwa.mobile

import android.content.Context

class UssdStorage(private val context: Context) {
    data class CatalogOffer(
        val key: String,
        val legacyId: Int,
        val price: Double,
        val ussdCode: String,
        val label: String
    )

    private val catalogOffers = listOf(
        CatalogOffer("sms_daily_20", 0, 5.0, "*188*10*1*1*pn*1*2#", "20 SMS Daily"),
        CatalogOffer("data_hourly_1gb", 1, 19.0, "*180*5*2*pn*5*1#", "1GB 1 Hour"),
        CatalogOffer("data_daily_250mb", 2, 20.0, "*180*5*2*pn*6*1#", "250MB 24 Hours"),
        CatalogOffer("flex_350_2h", 3, 21.0, "*444*5*1*3*pn*2*1#", "350 Flex 2 Hours"),
        CatalogOffer("sms_weekly_1000", 4, 30.0, "*188*10*2*2*pn*1*2#", "1000 Weekly SMS"),
        CatalogOffer("minutes_midnight_50", 5, 51.0, "*444*7*8*3*pn*2*1#", "50 Minutes Till Midnight"),
        CatalogOffer("data_midnight_1250mb", 6, 55.0, "*180*5*2*pn*8*1#", "1.25 GB Till Midnight")
    )

    private val definedUssds: Map<Double, String> = catalogOffers.associate { it.price to it.ussdCode }
    private val defaultLabels: Map<Double, String> = catalogOffers.associate { it.price to it.label }

    private val legacyCatalogPrices: Set<Int> = setOf(
        5, 10, 19, 20, 25, 30, 49, 50, 55, 99, 130, 200, 250, 300, 480, 500, 700
    )

    fun getUssdForAmount(amount: Double): String? = definedUssds[amount]
    fun getLabels(): Map<Double, String> = defaultLabels
    fun getCatalogOffers(): List<CatalogOffer> = catalogOffers.map { it.copy() }
    fun managedCatalogPrices(): Set<Int> = catalogOffers.map { it.price.toInt() }.toSet()
    fun managedCatalogKeys(): Set<String> = catalogOffers.map { it.key }.toSet()
    fun legacyManagedCatalogPrices(): Set<Int> = legacyCatalogPrices
}
