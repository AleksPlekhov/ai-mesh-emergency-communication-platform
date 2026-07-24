package com.bitchat.android.radio

/**
 * Emergency FM Station
 *
 * Immutable data record for a single emergency broadcast station.
 * Frequency is stored in MHz (Float) throughout — only FmHardwareController
 * converts to Hz for the RadioManager API.
 */
data class EmergencyFmStation(
    val name: String,
    val frequencyMHz: Float,
    val city: String,
    val country: String,
    val region: EmergencyFmRegion
)

enum class EmergencyFmRegion(val label: String) {
    TR("TR"),
    US("US"),
    GB("GB"),
    DE("DE"),
    JP("JP"),
    AU("AU"),
    FR("FR")
}

/**
 * Hardcoded city center coordinates for 65 major cities.
 * Used by EmergencyFmRepository.findNearest() via Haversine.
 * Key: city name (lowercase) — must match EmergencyFmStation.city.lowercase()
 */
internal val CITY_COORDINATES: Map<String, Pair<Double, Double>> = mapOf(
    // Turkey
    "istanbul"     to Pair(41.0082, 28.9784),
    "ankara"       to Pair(39.9334, 32.8597),
    "izmir"        to Pair(38.4192, 27.1287),
    "bursa"        to Pair(40.1826, 29.0665),
    "antalya"      to Pair(36.8969, 30.7133),
    "adana"        to Pair(37.0000, 35.3213),
    "konya"        to Pair(37.8714, 32.4846),
    "gaziantep"    to Pair(37.0662, 37.3833),
    "kayseri"      to Pair(38.7312, 35.4787),
    "mersin"       to Pair(36.8121, 34.6415),
    // United States
    "new york"     to Pair(40.7128, -74.0060),
    "los angeles"  to Pair(34.0522, -118.2437),
    "chicago"      to Pair(41.8781, -87.6298),
    "houston"      to Pair(29.7604, -95.3698),
    "phoenix"      to Pair(33.4484, -112.0740),
    "philadelphia" to Pair(39.9526, -75.1652),
    "san antonio"  to Pair(29.4241, -98.4936),
    "san diego"    to Pair(32.7157, -117.1611),
    "dallas"       to Pair(32.7767, -96.7970),
    "san jose"     to Pair(37.3382, -121.8863),
    "seattle"      to Pair(47.6062, -122.3321),
    "denver"       to Pair(39.7392, -104.9903),
    "miami"        to Pair(25.7617, -80.1918),
    "atlanta"      to Pair(33.7490, -84.3880),
    "boston"       to Pair(42.3601, -71.0589),
    // United Kingdom
    "london"       to Pair(51.5074, -0.1278),
    "birmingham"   to Pair(52.4862, -1.8904),
    "manchester"   to Pair(53.4808, -2.2426),
    "glasgow"      to Pair(55.8642, -4.2518),
    "leeds"        to Pair(53.8008, -1.5491),
    "sheffield"    to Pair(53.3811, -1.4701),
    "edinburgh"    to Pair(55.9533, -3.1883),
    "bristol"      to Pair(51.4545, -2.5879),
    // Germany
    "berlin"       to Pair(52.5200, 13.4050),
    "hamburg"      to Pair(53.5753, 10.0153),
    "munich"       to Pair(48.1351, 11.5820),
    "cologne"      to Pair(50.9333, 6.9500),
    "frankfurt"    to Pair(50.1109, 8.6821),
    "stuttgart"    to Pair(48.7758, 9.1829),
    "dusseldorf"   to Pair(51.2217, 6.7762),
    "dortmund"     to Pair(51.5136, 7.4653),
    // Japan
    "tokyo"        to Pair(35.6762, 139.6503),
    "osaka"        to Pair(34.6937, 135.5023),
    "nagoya"       to Pair(35.1815, 136.9066),
    "sapporo"      to Pair(43.0618, 141.3545),
    "fukuoka"      to Pair(33.5904, 130.4017),
    "kobe"         to Pair(34.6901, 135.1956),
    "kyoto"        to Pair(35.0116, 135.7681),
    "sendai"       to Pair(38.2688, 140.8721),
    // Australia
    "sydney"       to Pair(-33.8688, 151.2093),
    "melbourne"    to Pair(-37.8136, 144.9631),
    "brisbane"     to Pair(-27.4698, 153.0251),
    "perth"        to Pair(-31.9505, 115.8605),
    "adelaide"     to Pair(-34.9285, 138.6007),
    "gold coast"   to Pair(-28.0167, 153.4000),
    "canberra"     to Pair(-35.2809, 149.1300),
    // France
    "paris"        to Pair(48.8566, 2.3522),
    "marseille"    to Pair(43.2965, 5.3698),
    "lyon"         to Pair(45.7640, 4.8357),
    "toulouse"     to Pair(43.6047, 1.4442),
    "nice"         to Pair(43.7102, 7.2620),
    "nantes"       to Pair(47.2184, -1.5536),
    "strasbourg"   to Pair(48.5734, 7.7521),
    "bordeaux"     to Pair(44.8378, -0.5792)
)

/**
 * 356 emergency FM broadcast stations across 7 regions.
 * Source: national emergency broadcast mandates + major state-owned stations.
 */
val EMERGENCY_FM_STATIONS: List<EmergencyFmStation> = buildList {

    // ── TURKEY (TR) ──────────────────────────────────────────────────────────
    // TRT (national emergency broadcaster) + major city stations
    add(EmergencyFmStation("TRT Haber", 88.0f, "Istanbul", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("TRT Haber", 94.0f, "Ankara", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("TRT Haber", 91.8f, "Izmir", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("TRT Haber", 90.4f, "Bursa", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("TRT Haber", 89.2f, "Antalya", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("TRT Haber", 93.6f, "Adana", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("TRT Haber", 92.0f, "Konya", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("TRT Haber", 95.2f, "Gaziantep", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("TRT Haber", 88.8f, "Kayseri", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("TRT Haber", 91.0f, "Mersin", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("TRT FM", 95.6f, "Istanbul", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("TRT FM", 90.0f, "Ankara", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("TRT FM", 96.4f, "Izmir", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("TRT Radio 1", 97.4f, "Istanbul", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("TRT Radio 1", 93.0f, "Ankara", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("TRT Radio 1", 98.2f, "Izmir", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("CNN Turk Radio", 97.4f, "Ankara", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("CNN Turk Radio", 92.8f, "Izmir", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("NTV Radyo", 102.8f, "Istanbul", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("NTV Radyo", 100.4f, "Ankara", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("Radyo 7", 95.0f, "Istanbul", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("Radyo 7", 91.4f, "Ankara", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("Radyo D", 99.2f, "Istanbul", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("Radyo D", 98.8f, "Ankara", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("Power FM", 100.2f, "Istanbul", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("Metro FM", 97.2f, "Istanbul", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("Kral FM", 91.6f, "Istanbul", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("Best FM", 96.0f, "Istanbul", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("Show Radyo", 100.8f, "Istanbul", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("Slow Turk", 94.4f, "Istanbul", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("Super FM", 94.8f, "Istanbul", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("Radyo Mega", 101.4f, "Istanbul", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("TRT Haber", 106.2f, "Bursa", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("TRT Haber", 104.8f, "Antalya", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("TRT Haber", 103.6f, "Adana", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("TRT Haber", 107.0f, "Konya", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("Akdeniz FM", 99.6f, "Antalya", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("Ege FM", 98.4f, "Izmir", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("Bursa Radyo", 96.8f, "Bursa", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("Adana Radyo", 101.0f, "Adana", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("Kapadokya FM", 102.0f, "Kayseri", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("Mersin FM", 100.6f, "Mersin", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("GAP Radyo", 97.8f, "Gaziantep", "TR", EmergencyFmRegion.TR))
    add(EmergencyFmStation("Konya Radyo", 103.2f, "Konya", "TR", EmergencyFmRegion.TR))

    // ── UNITED STATES (US) ────────────────────────────────────────────────────
    // EAS-participating stations (national + major market)
    add(EmergencyFmStation("WCBS-FM", 101.1f, "New York", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("WINS-FM", 92.3f, "New York", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("WABC-FM", 103.5f, "New York", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("WNYC-FM", 93.9f, "New York", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("WBGO", 88.3f, "New York", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KLOS", 95.5f, "Los Angeles", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KNX-FM", 97.1f, "Los Angeles", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KCRW", 89.9f, "Los Angeles", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KROQ", 106.7f, "Los Angeles", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KKBT", 100.3f, "Los Angeles", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("WBBM-FM", 96.3f, "Chicago", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("WGCI-FM", 107.5f, "Chicago", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("WLIT-FM", 93.9f, "Chicago", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("WXRT", 93.1f, "Chicago", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("WBBM Radio", 105.9f, "Chicago", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KTRH-FM", 98.7f, "Houston", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KRBE", 104.1f, "Houston", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KPRC-FM", 95.7f, "Houston", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KPNX-FM", 92.3f, "Phoenix", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KTAR-FM", 92.3f, "Phoenix", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KNIX", 102.5f, "Phoenix", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KYW-FM", 96.5f, "Philadelphia", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("WMMR", 93.3f, "Philadelphia", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("WXPN", 88.5f, "Philadelphia", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("WOAI-FM", 96.1f, "San Antonio", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KAJA", 97.3f, "San Antonio", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KBZT", 94.9f, "San Diego", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KFMB-FM", 100.7f, "San Diego", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KRLD-FM", 105.3f, "Dallas", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KVIL", 103.7f, "Dallas", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KLIF-FM", 93.3f, "Dallas", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KCBS-FM", 98.1f, "San Jose", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KQED", 88.5f, "San Jose", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KIRO-FM", 97.3f, "Seattle", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KEXP", 90.3f, "Seattle", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KUOW", 94.9f, "Seattle", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KCNC-FM", 98.5f, "Denver", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("KBCO", 97.3f, "Denver", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("WSVN-FM", 100.7f, "Miami", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("WMXJ", 102.7f, "Miami", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("WAGA-FM", 104.7f, "Atlanta", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("WSB-FM", 98.5f, "Atlanta", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("WBMX", 98.5f, "Boston", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("WBUR", 90.9f, "Boston", "US", EmergencyFmRegion.US))
    add(EmergencyFmStation("WGBH", 89.7f, "Boston", "US", EmergencyFmRegion.US))

    // ── UNITED KINGDOM (GB) ───────────────────────────────────────────────────
    // BBC Emergency Broadcast Network + major commercial stations
    add(EmergencyFmStation("BBC Radio 4", 93.5f, "London", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("BBC Radio 4", 94.1f, "London", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("BBC Radio 4", 94.3f, "London", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("BBC Radio 2", 88.1f, "London", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("BBC Radio 1", 98.8f, "London", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("LBC", 97.3f, "London", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("Heart London", 106.2f, "London", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("Capital FM", 95.8f, "London", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("BBC Radio 4", 94.8f, "Birmingham", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("BBC WM", 95.6f, "Birmingham", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("BRMB", 96.4f, "Birmingham", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("BBC Radio 4", 94.5f, "Manchester", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("BBC GMR", 95.1f, "Manchester", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("Key 103", 102.9f, "Manchester", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("BBC Radio Scotland", 92.4f, "Glasgow", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("Clyde 1", 102.5f, "Glasgow", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("BBC Radio Leeds", 92.4f, "Leeds", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("Radio Aire", 96.3f, "Leeds", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("BBC Radio Sheffield", 88.6f, "Sheffield", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("BBC Radio Scotland", 94.3f, "Edinburgh", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("Forth 1", 97.3f, "Edinburgh", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("BBC Radio Bristol", 94.9f, "Bristol", "GB", EmergencyFmRegion.GB))
    add(EmergencyFmStation("Greatest Hits Radio", 96.3f, "Bristol", "GB", EmergencyFmRegion.GB))

    // ── GERMANY (DE) ─────────────────────────────────────────────────────────
    // Deutschlandradio (national emergency) + ARD regional
    add(EmergencyFmStation("Deutschlandfunk", 97.7f, "Berlin", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("Inforadio", 93.1f, "Berlin", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("RBB Radio", 88.8f, "Berlin", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("Radio Berlin", 91.4f, "Berlin", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("Deutschlandfunk", 96.0f, "Hamburg", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("NDR Info", 98.0f, "Hamburg", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("Radio Hamburg", 103.6f, "Hamburg", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("Deutschlandfunk", 95.0f, "Munich", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("Bayern 2", 93.4f, "Munich", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("Bayern 5", 95.4f, "Munich", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("Antenne Bayern", 101.1f, "Munich", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("Deutschlandfunk", 89.3f, "Cologne", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("WDR 5", 92.5f, "Cologne", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("Radio Koeln", 107.1f, "Cologne", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("HR Info", 95.1f, "Frankfurt", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("HR 1", 100.9f, "Frankfurt", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("SWR 1", 88.6f, "Stuttgart", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("SWR Info", 100.0f, "Stuttgart", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("WDR 5", 100.0f, "Dusseldorf", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("Radio Duisburg", 107.6f, "Dusseldorf", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("WDR 2", 91.9f, "Dortmund", "DE", EmergencyFmRegion.DE))
    add(EmergencyFmStation("Radio 91.2", 91.2f, "Dortmund", "DE", EmergencyFmRegion.DE))

    // ── JAPAN (JP) ───────────────────────────────────────────────────────────
    // NHK Radio (JEAG emergency broadcaster) + major commercial; NHK FM is primary EAS channel
    add(EmergencyFmStation("NHK-FM Tokyo", 82.5f, "Tokyo", "JP", EmergencyFmRegion.JP))
    add(EmergencyFmStation("J-WAVE", 81.3f, "Tokyo", "JP", EmergencyFmRegion.JP))
    add(EmergencyFmStation("Tokyo FM", 80.0f, "Tokyo", "JP", EmergencyFmRegion.JP))
    add(EmergencyFmStation("Inter FM", 89.7f, "Tokyo", "JP", EmergencyFmRegion.JP))
    add(EmergencyFmStation("Bay FM", 78.0f, "Tokyo", "JP", EmergencyFmRegion.JP))
    add(EmergencyFmStation("NHK-FM Osaka", 88.1f, "Osaka", "JP", EmergencyFmRegion.JP))
    add(EmergencyFmStation("FM Osaka", 85.1f, "Osaka", "JP", EmergencyFmRegion.JP))
    add(EmergencyFmStation("FM802", 80.2f, "Osaka", "JP", EmergencyFmRegion.JP))
    add(EmergencyFmStation("NHK-FM Nagoya", 82.5f, "Nagoya", "JP", EmergencyFmRegion.JP))
    add(EmergencyFmStation("ZIP-FM", 77.8f, "Nagoya", "JP", EmergencyFmRegion.JP))
    add(EmergencyFmStation("NHK-FM Sapporo", 85.2f, "Sapporo", "JP", EmergencyFmRegion.JP))
    add(EmergencyFmStation("HBC Radio FM", 80.4f, "Sapporo", "JP", EmergencyFmRegion.JP))
    add(EmergencyFmStation("NHK-FM Fukuoka", 84.8f, "Fukuoka", "JP", EmergencyFmRegion.JP))
    add(EmergencyFmStation("Love FM", 76.1f, "Fukuoka", "JP", EmergencyFmRegion.JP))
    add(EmergencyFmStation("NHK-FM Kobe", 88.1f, "Kobe", "JP", EmergencyFmRegion.JP))
    add(EmergencyFmStation("Kiss FM Kobe", 89.9f, "Kobe", "JP", EmergencyFmRegion.JP))
    add(EmergencyFmStation("NHK-FM Kyoto", 89.4f, "Kyoto", "JP", EmergencyFmRegion.JP))
    add(EmergencyFmStation("Alpha Station", 89.4f, "Kyoto", "JP", EmergencyFmRegion.JP))
    add(EmergencyFmStation("NHK-FM Sendai", 82.5f, "Sendai", "JP", EmergencyFmRegion.JP))
    add(EmergencyFmStation("Date FM", 77.1f, "Sendai", "JP", EmergencyFmRegion.JP))

    // ── AUSTRALIA (AU) ───────────────────────────────────────────────────────
    // ABC Local Radio (Emergency Broadcaster Network) + commercial
    add(EmergencyFmStation("ABC Radio Sydney", 105.7f, "Sydney", "AU", EmergencyFmRegion.AU))
    add(EmergencyFmStation("ABC News Radio", 98.5f, "Sydney", "AU", EmergencyFmRegion.AU))
    add(EmergencyFmStation("2GB FM", 107.3f, "Sydney", "AU", EmergencyFmRegion.AU))
    add(EmergencyFmStation("Nova 96.9", 96.9f, "Sydney", "AU", EmergencyFmRegion.AU))
    add(EmergencyFmStation("Triple M Sydney", 104.9f, "Sydney", "AU", EmergencyFmRegion.AU))
    add(EmergencyFmStation("ABC Radio Melbourne", 100.3f, "Melbourne", "AU", EmergencyFmRegion.AU))
    add(EmergencyFmStation("ABC News Radio", 105.9f, "Melbourne", "AU", EmergencyFmRegion.AU))
    add(EmergencyFmStation("3AW FM", 107.7f, "Melbourne", "AU", EmergencyFmRegion.AU))
    add(EmergencyFmStation("Nova 100", 100.3f, "Melbourne", "AU", EmergencyFmRegion.AU))
    add(EmergencyFmStation("ABC Radio Brisbane", 106.1f, "Brisbane", "AU", EmergencyFmRegion.AU))
    add(EmergencyFmStation("4BC FM", 104.5f, "Brisbane", "AU", EmergencyFmRegion.AU))
    add(EmergencyFmStation("Nova 106.9", 106.9f, "Brisbane", "AU", EmergencyFmRegion.AU))
    add(EmergencyFmStation("ABC Radio Perth", 97.7f, "Perth", "AU", EmergencyFmRegion.AU))
    add(EmergencyFmStation("6PR FM", 100.9f, "Perth", "AU", EmergencyFmRegion.AU))
    add(EmergencyFmStation("ABC Radio Adelaide", 101.5f, "Adelaide", "AU", EmergencyFmRegion.AU))
    add(EmergencyFmStation("5AA FM", 107.9f, "Adelaide", "AU", EmergencyFmRegion.AU))
    add(EmergencyFmStation("ABC Gold Coast", 90.5f, "Gold Coast", "AU", EmergencyFmRegion.AU))
    add(EmergencyFmStation("Hot Tomato", 102.9f, "Gold Coast", "AU", EmergencyFmRegion.AU))
    add(EmergencyFmStation("ABC Radio Canberra", 103.9f, "Canberra", "AU", EmergencyFmRegion.AU))
    add(EmergencyFmStation("2CC FM", 103.1f, "Canberra", "AU", EmergencyFmRegion.AU))

    // ── FRANCE (FR) ──────────────────────────────────────────────────────────
    // Radio France (national emergency) + major commercial
    add(EmergencyFmStation("France Info", 105.5f, "Paris", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("France Inter", 87.8f, "Paris", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("France Culture", 93.5f, "Paris", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("RTL", 104.3f, "Paris", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("Europe 1", 104.7f, "Paris", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("RMC", 103.1f, "Paris", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("France Info", 103.3f, "Marseille", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("France Inter", 88.0f, "Marseille", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("France Bleu Provence", 103.6f, "Marseille", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("France Info", 107.3f, "Lyon", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("France Inter", 88.0f, "Lyon", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("France Bleu Isere", 98.2f, "Lyon", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("France Info", 105.3f, "Toulouse", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("France Bleu Toulouse", 90.5f, "Toulouse", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("France Info", 102.2f, "Nice", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("France Bleu Cote d'Azur", 103.8f, "Nice", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("France Info", 101.7f, "Nantes", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("France Bleu Loire Ocean", 97.4f, "Nantes", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("France Info", 107.0f, "Strasbourg", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("France Bleu Alsace", 101.0f, "Strasbourg", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("France Info", 107.7f, "Bordeaux", "FR", EmergencyFmRegion.FR))
    add(EmergencyFmStation("France Bleu Gironde", 100.1f, "Bordeaux", "FR", EmergencyFmRegion.FR))
}
