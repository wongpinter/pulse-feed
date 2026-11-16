package com.simon.harmonichackernews.ui.settings

import com.simon.harmonichackernews.settings.SettingsSearchEntry
import com.simon.harmonichackernews.settings.SettingsSearchIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSearchTest {
    private val labels = SettingsSection.entries.associateWith { it.name }
    private fun entries(availability: SettingsSearchAvailability = SettingsSearchAvailability()) =
        settingsSearchCatalog(labels, availability)
    private fun target(title: String): SettingsSearchEntry = entries().first { it.title == title }

    @Test fun everyEntryHasAUniqueIdAndNavigableDestination() {
        val entries = entries(SettingsSearchAvailability(loggedIn = true, debug = true, geminiNano = true))
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        entries.forEach {
            assertTrue(SettingsSection.fromRoute(it.screenRoute) != null, it.id)
            assertTrue(it.path.isNotEmpty(), it.id)
            assertFalse(it.screenRoute == SettingsSection.Search.route)
        }
    }

    @Test fun retiredHackerNewsSurfacesAreNeitherListedNorSearchable() {
        val entries = entries(SettingsSearchAvailability(loggedIn = true, debug = true))
        val retiredRoutes = HiddenSettingsSections.map { it.route }.toSet()
        assertFalse(entries.any { it.screenRoute in retiredRoutes }, "retired section reached via search")
        val index = SettingsSearchIndex(entries)
        for (query in listOf("hide comments", "thread lines", "frontpage selector", "log out", "replies to your comments")) {
            assertTrue(index.search(query).isEmpty(), query)
        }
    }

    @Test fun synonymsFindTheExpectedSettingsWithoutChangingVisibleCopy() {
        val index = SettingsSearchIndex(entries())
        for ((query, expected) in listOf(
            "adblock" to "Block in-app browser ads",
            "typeface" to "Font",
            "backup" to "Export settings",
            "readability" to "ReaderMode",
            "mute" to "FiltersTags",
        )) assertTrue(index.search(query).any { it.title == expected }, query)
        assertEquals(SettingsSection.Theme.route, index.search("dark mode").first().screenRoute)
        assertEquals(SettingsSection.Font.route, index.search("fonts").first().screenRoute)
    }

    @Test fun unavailableControlsAreNotIndexed() {
        val availability = SettingsSearchAvailability(capabilities = SettingsSearchCapabilities(
            webLinks = WebLinksSettingsCapabilities(adBlocking = false, readerMode = false,
                closeWebViewOnBack = false, preloadWebsites = false),
            notifications = false, systemSettings = false, volumeNavigation = false,
            transparentStatusBar = false,
        ), cloudAi = false)
        val unavailable = setOf("Block in-app browser ads", "Reader mode", "Hide browser on back",
            "Preload websites", "Transparent status bar", "API Key")
        assertFalse(entries(availability).any { it.title in unavailable || it.screenRoute == "debug" })
        assertFalse(entries().any { it.screenRoute == "notifications" })
    }

    @Test fun debugToolsStaySearchableWithoutAccountShortcuts() {
        val entries = entries(SettingsSearchAvailability(accountPreview = true, debug = true,
            capabilities = SettingsSearchCapabilities(notifications = false)))
        assertTrue(entries.any { it.screenRoute == "debug" && it.title == "Account preview" })
        assertTrue(entries.any { it.screenRoute == "debug" && it.title == "Mock AI answers" })
        assertFalse(entries.any { it.title == "Open Account settings" })
        assertFalse(entries.any { it.screenRoute == "account" })
        assertFalse(entries.any { it.title == "Add all bookmarks to HN favorites" })
    }

    @Test fun twoPaneSearchDoesNotAdvertiseRetiredControls() {
        val entries = entries(SettingsSearchAvailability(capabilities = SettingsSearchCapabilities(twoPane = true)))
        val retired = setOf("Allow adjustments", "Enable side by side", "Portrait split ratio", "Landscape split ratio")
        assertFalse(entries.any { it.title in retired })
        assertTrue(entries.any { it.title == "Extra side padding" })
    }

    @Test fun localOnlyHostDoesNotAdvertiseCloudSettingsOrProviders() {
        val entries = entries(SettingsSearchAvailability(cloudAiSupported = false, localAi = true))
        val aiEntries = entries.filter { it.screenRoute == SettingsSection.AiSummary.route }
        assertFalse(aiEntries.any { it.title in setOf("Base URL", "API Key", "Model") })
        assertTrue(aiEntries.any { it.title == "Downloadable models" })
        val index = SettingsSearchIndex(aiEntries)
        for (query in listOf("cloud", "openai", "anthropic", "ollama")) {
            assertTrue(index.search(query).isEmpty(), query)
        }
    }

    @Test fun resultAndNestedDetailReturnToTheSameSearchBeforeClosing() {
        for (twoPane in listOf(false, true)) {
            val navigation = SettingsNavigationStore(twoPane = twoPane)
            navigation.navigateTo(SettingsSection.Search)
            navigation.updateSearchQuery("adblock")
            assertTrue(navigation.openSearchResult(target("Block in-app browser ads")))
            navigation.navigateTo(SettingsSection.ReaderMode, preserveCurrentDetail = true)
            assertTrue(navigation.navigateBack())
            assertEquals(SettingsSection.WebLinks, navigation.state.value.selectedSection)
            assertTrue(navigation.navigateBack())
            assertEquals(SettingsSection.Search, navigation.state.value.selectedSection)
            assertEquals("adblock", navigation.state.value.searchQuery)
            assertEquals(listOf(target("Block in-app browser ads").id), navigation.state.value.recentSearchResultIds)
            assertTrue(navigation.navigateBack())
            assertFalse(SettingsSection.Search in navigation.state.value.detailStack)
        }
    }

    @Test fun restorationPreservesSearchAndItsTargetAcrossLayoutChanges() {
        val original = SettingsNavigationStore()
        original.navigateTo(SettingsSection.Search)
        original.updateSearchQuery("adblock")
        val target = target("Block in-app browser ads")
        original.openSearchResult(target)
        val state = original.state.value
        val restored = SettingsNavigationStore(twoPane = true, restoredRoutes = original.savedRoutes(),
            restoredSearchQuery = state.searchQuery, restoredSearchResultId = state.searchResultId,
            restoredRecentSearchResultIds = state.recentSearchResultIds)
        restored.updateLayout(false)
        assertEquals(target.id, restored.state.value.searchResultId)
        assertTrue(restored.navigateBack())
        assertEquals("adblock", restored.state.value.searchQuery)
        assertEquals(SettingsSection.Search, restored.state.value.selectedSection)
        restored.updateLayout(true)
        assertTrue(restored.navigateBack())
    }

    @Test fun repeatedResultsDoNotAccumulateDestinationsOrRecentDuplicates() {
        val navigation = SettingsNavigationStore(twoPane = true, initialSection = SettingsSection.WebLinks)
        navigation.navigateTo(SettingsSection.Search)
        repeat(8) {
            navigation.openSearchResult(target("Block in-app browser ads"))
            assertEquals(listOf(SettingsSection.Search, SettingsSection.WebLinks), navigation.state.value.detailStack)
            navigation.navigateBack()
        }
        assertEquals(1, navigation.state.value.recentSearchResultIds.size)
        navigation.navigateTo(SettingsSection.Data)
        assertFalse(navigation.openSearchResult(target("Block in-app browser ads")))
        assertEquals(null, navigation.state.value.searchResultId)
    }

    @Test fun closingWideSearchRemovesItsResultAndReopeningStartsAFreshQuery() {
        val navigation = SettingsNavigationStore(twoPane = true)
        navigation.navigateTo(SettingsSection.Search)
        navigation.updateSearchQuery("adblock")
        navigation.openSearchResult(target("Block in-app browser ads"))
        navigation.closeSearch()
        assertEquals(listOf(SettingsSection.Appearance), navigation.state.value.detailStack)
        assertEquals(null, navigation.state.value.searchResultId)
        navigation.navigateTo(SettingsSection.Search)
        assertEquals("", navigation.state.value.searchQuery)
        assertEquals(1, navigation.state.value.recentSearchResultIds.size)
    }

    @Test fun wideSearchRestoresThePreviousDetailHierarchyAfterBackOrClose() {
        for (useToolbar in listOf(false, true)) {
            val navigation = SettingsNavigationStore(twoPane = true)
            navigation.navigateTo(SettingsSection.Theme, preserveCurrentDetail = true)
            navigation.navigateTo(SettingsSection.Search)
            navigation.openSearchResult(target("Block in-app browser ads"))
            val state = navigation.state.value
            val restored = SettingsNavigationStore(twoPane = true, restoredRoutes = navigation.savedRoutes(),
                restoredSearchOriginRoutes = state.searchOriginRoutes)
            if (useToolbar) restored.closeSearch() else {
                assertTrue(restored.navigateBack())
                assertEquals(SettingsSection.Search, restored.state.value.selectedSection)
                assertTrue(restored.navigateBack())
            }
            assertEquals(listOf(SettingsSection.Appearance, SettingsSection.Theme), restored.state.value.detailStack)
            assertTrue(restored.state.value.searchOriginRoutes.isEmpty())
        }
    }
}