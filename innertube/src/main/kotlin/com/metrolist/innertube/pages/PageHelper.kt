package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_LIBRARY_ARTIST
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_USER_CHANNEL
import com.metrolist.innertube.models.Menu
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer.FlexColumn
import com.metrolist.innertube.models.Run
import com.metrolist.innertube.utils.parseTime

object PageHelper {
    // Icon types for library management (YouTube changed these in Feb 2026)
    // Old icons: LIBRARY_ADD (not in library), LIBRARY_SAVED/LIBRARY_REMOVE (in library)
    // New icons: BOOKMARK_BORDER (not in library), BOOKMARK (in library)
    // Note: KEEP/KEEP_OFF are for "Pin to Listen Again" - different from library!
    private val LIBRARY_ADD_ICONS = setOf("LIBRARY_ADD", "BOOKMARK_BORDER")
    private val LIBRARY_SAVED_ICONS = setOf("LIBRARY_SAVED", "BOOKMARK", "LIBRARY_REMOVE")
    private val ALL_LIBRARY_ICONS = LIBRARY_ADD_ICONS + LIBRARY_SAVED_ICONS

    /**
     * Data class to hold both library feedback tokens extracted from a menu
     */
    data class LibraryFeedbackTokens(
        val addToken: String?, // Token to add song to library (from BOOKMARK_BORDER)
        val removeToken: String?, // Token to remove song from library (from BOOKMARK)
    )

    /**
     * Check if an icon type is a library-related icon (for filtering menu items)
     * Excludes KEEP/KEEP_OFF which are for "Pin to Listen Again"
     */
    fun isLibraryIcon(iconType: String?): Boolean {
        if (iconType == null) return false
        // Exclude KEEP/KEEP_OFF (Listen Again pins)
        if (iconType == "KEEP" || iconType == "KEEP_OFF") return false
        return iconType in ALL_LIBRARY_ICONS || iconType.startsWith("LIBRARY_")
    }

    /**
     * Check if an icon type indicates the song is NOT in library (add state)
     */
    fun isAddLibraryIcon(iconType: String?): Boolean = iconType in LIBRARY_ADD_ICONS

    /**
     * Check if an icon type indicates the song IS in library (saved/remove state)
     */
    fun isSavedLibraryIcon(iconType: String?): Boolean = iconType in LIBRARY_SAVED_ICONS

    fun extractRuns(
        columns: List<FlexColumn>,
        typeLike: String,
    ): List<Run> {
        val filteredRuns = mutableListOf<Run>()
        for (column in columns) {
            val runs =
                column.musicResponsiveListItemFlexColumnRenderer.text?.runs
                    ?: continue

            for (run in runs) {
                val typeStr =
                    run.navigationEndpoint
                        ?.watchEndpoint
                        ?.watchEndpointMusicSupportedConfigs
                        ?.watchEndpointMusicConfig
                        ?.musicVideoType
                        ?: run.navigationEndpoint
                            ?.browseEndpoint
                            ?.browseEndpointContextSupportedConfigs
                            ?.browseEndpointContextMusicConfig
                            ?.pageType
                        ?: continue

                if (typeLike in typeStr) {
                    filteredRuns.add(run)
                }
            }
        }
        return filteredRuns
    }

    /**
     * Extract library feedback tokens from a list of menu items.
     *
     * This function iterates through ALL toggle menu items and extracts tokens
     * based on their icon types, ensuring we don't confuse library tokens with
     * "Pin to Listen Again" tokens (KEEP/KEEP_OFF).
     *
     * YouTube's icon system (Feb 2026):
     * - BOOKMARK_BORDER: Song NOT in library -> defaultToken = ADD, toggledToken = REMOVE
     * - BOOKMARK: Song IS in library -> defaultToken = REMOVE, toggledToken = ADD
     * - KEEP/KEEP_OFF: "Pin to Listen Again" - COMPLETELY DIFFERENT, must be ignored!
     *
     * @param menuItems The list of menu items to search through
     * @return LibraryFeedbackTokens containing both add and remove tokens
     */
    fun extractLibraryTokensFromMenuItems(menuItems: List<Menu.MenuRenderer.Item>?): LibraryFeedbackTokens {
        if (menuItems == null) return LibraryFeedbackTokens(null, null)

        var addToken: String? = null
        var removeToken: String? = null

        for (item in menuItems) {
            val toggleRenderer = item.toggleMenuServiceItemRenderer ?: continue
            val iconType = toggleRenderer.defaultIcon.iconType

            // Skip KEEP/KEEP_OFF icons (Pin to Listen Again) - these are NOT library actions
            if (iconType == "KEEP" || iconType == "KEEP_OFF") continue

            // Only process library-related icons
            if (!isLibraryIcon(iconType)) continue

            val defaultToken = toggleRenderer.defaultServiceEndpoint.feedbackEndpoint?.feedbackToken
            val toggledToken = toggleRenderer.toggledServiceEndpoint?.feedbackEndpoint?.feedbackToken

            // Determine which token is which based on icon type
            when {
                isAddLibraryIcon(iconType) -> {
                    // BOOKMARK_BORDER or LIBRARY_ADD: default=add, toggled=remove
                    if (addToken == null) addToken = defaultToken
                    if (removeToken == null) removeToken = toggledToken
                }

                isSavedLibraryIcon(iconType) -> {
                    // BOOKMARK or LIBRARY_SAVED/REMOVE: default=remove, toggled=add
                    if (removeToken == null) removeToken = defaultToken
                    if (addToken == null) addToken = toggledToken
                }
            }
        }

        return LibraryFeedbackTokens(addToken, removeToken)
    }

    fun extractArtists(runs: List<Run>?): List<Artist> =
        runs.orEmpty().mapNotNull { run ->
            val endpoint = run.navigationEndpoint?.browseEndpoint ?: return@mapNotNull null
            val pageType = endpoint.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType
            if (run.text.isBlank() || isStatsText(run.text) ||
                !(
                    pageType == MUSIC_PAGE_TYPE_ARTIST || pageType == MUSIC_PAGE_TYPE_LIBRARY_ARTIST ||
                        pageType == MUSIC_PAGE_TYPE_USER_CHANNEL || pageType == null && endpoint.browseId.startsWith("UC")
                )
            ) {
                null
            } else {
                Artist(run.text.trim(), endpoint.browseId)
            }
        }

    /** An artist taken from a run's bare text (uploads, art tracks), unless that text is a count or a duration. */
    fun textArtist(run: Run): Artist? =
        run.text
            .trim()
            .takeIf { it.isNotEmpty() && !isStatsText(it) }
            ?.let { Artist(it, null) }

    // Vegas: YouTube puts counts ("2,4 M reproducciones", "3,2 M de visualizaciones", "37 K Me gusta") and
    // durations in the same subtitle as the artists; none of them is ever an artist.
    private val STATS_WORDS =
        listOf(
            // English
            "view", "play", "stream", "subscriber", "listener", "like", "monthly",
            // Spanish
            "visualizaci", "vista", "reproducci", "suscriptor", "oyente", "me gusta", "usuarios mensuales", "escucha",
            // Portuguese
            "visualiza", "reprodu", "inscrito", "ouvinte", "curtida", "gostei",
            // French
            "vue", "écoute", "lecture", "abonné", "auditeur", "j'aime", "j’aime",
            // German
            "aufruf", "wiedergabe", "abonnent", "hörer", "mag ich",
            // Italian
            "visualizzazion", "riproduzion", "iscritt", "ascoltator", "mi piace",
            // Dutch
            "weergave", "keer afgespeeld", "abonnee", "luisteraar",
            // Polish, Russian, Turkish
            "wyświetle", "odtworze", "subskrybent", "słuchacz",
            "просмотр", "прослушиван", "подписчик", "слушател",
            "görüntülen", "dinlen", "abone", "dinleyici",
        )

    private val STATS_WORDS_PATTERN =
        Regex(
            """^[\d\s.,\u00A0\u202F]+[\p{L}.]{0,6}\.?[\s\u00A0\u202F]*(?:de[\s\u00A0\u202F]+|d['’][\s\u00A0\u202F]*)?(?:${STATS_WORDS.joinToString("|") { Regex.escape(it) }})""",
            RegexOption.IGNORE_CASE,
        )

    // Japanese, Chinese and Korean put the count words right next to the number.
    private val CJK_STATS_PATTERN = Regex("""回視聴|回再生|万回|次观看|次觀看|次播放|观看次数|觀看次數|登録者|订阅者|訂閱者|조회수|재생|구독자""")

    fun isStatsText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.none(Char::isDigit)) return false
        return trimmed.isDurationText() || STATS_WORDS_PATTERN.containsMatchIn(trimmed) || CJK_STATS_PATTERN.containsMatchIn(trimmed)
    }

    fun extractDuration(runs: List<Run>?): Int? =
        runs.orEmpty().firstNotNullOfOrNull { run ->
            run.text
                .trim()
                .takeIf { it.isDurationText() }
                ?.parseTime()
        }

    private fun String.isDurationText(): Boolean = matches(Regex("""\d{1,2}[:.,]\d{2}(?:[:.,]\d{2})?"""))
}
