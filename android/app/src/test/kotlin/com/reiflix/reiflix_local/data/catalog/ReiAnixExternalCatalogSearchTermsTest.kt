package com.reiflix.reiflix_local.data.catalog

import com.reiflix.reiflix_local.data.media.MediaTitleNormalizer
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixExternalCatalogSearchTermsTest {
    @Test
    fun retainsEveryAvailableTitleInsteadOfStoppingAfterThree() {
        val titles = listOf(
            "Original Japanese Title",
            "Romaji Title",
            "English Release Title",
            "Alternative Regional Title",
            "Short Name",
            "Another Known Synonym",
        )

        val terms = boundedExternalCatalogSearchTerms(titles)

        titles.forEach { title ->
            assertTrue(
                "Missing title variant: $title",
                terms.any {
                    MediaTitleNormalizer.normalizeTitle(it) ==
                        MediaTitleNormalizer.normalizeTitle(title)
                },
            )
        }
    }

    @Test
    fun deduplicatesEquivalentTitleVariantsWithoutDiscardingDistinctAliases() {
        val terms = boundedExternalCatalogSearchTerms(
            listOf("Sample.Anime", "Sample Anime", "サンプルアニメ", "The Sample Anime"),
        )

        assertTrue(terms.any { it.contains("サンプルアニメ") })
        assertTrue(terms.any { it.equals("The Sample Anime", ignoreCase = true) })
        assertTrue(
            terms.map(MediaTitleNormalizer::normalizeTitle).distinct().size == terms.size,
        )
    }
}
