package com.reiflix.reiflix_local.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads the same baseline fixtures as the Python regression suite.
 * The Kotlin/JVM workflow remains manual and is not invoked by this change.
 */
class MediaDomainParityBaselineTest {
    @Test
    fun filenameParserMatchesTheSharedBaselineCorpus() {
        readFixture("media_parser_parity.tsv").forEach { row ->
            val caseId = row[0]
            val parsed = MediaFilenameParser.parseVideoPath(
                row[1],
                row[2].takeUnless { it == "-" },
            )

            assertEquals("$caseId title", row[3], parsed.animeTitle)
            assertTrue("$caseId season", nullableInt(row[4]) == parsed.season)
            assertTrue("$caseId episode number", nullableDouble(row[5]) == parsed.episodeNumber)
            assertTrue("$caseId absolute number", nullableInt(row[6]) == parsed.absoluteNumber)
            assertEquals("$caseId kind", row[7], parsed.kind.name.lowercase())
            assertEquals("$caseId confidence", row[8], parsed.confidence.name.lowercase())
            assertEquals("$caseId extension support", row[9].toBoolean(), parsed.isSupportedVideoExtension)
        }
    }

    @Test
    fun physicalMediaIdentityMatchesTheSharedBaselineCorpus() {
        readFixture("media_identity_parity.tsv").forEach { row ->
            val caseId = row[0]
            val operation = row[1]
            val uri = row[2].takeUnless { it == "-" }
            val relativePath = row[3].takeUnless { it == "-" }
            val volumeName = row[4].takeUnless { it == "-" }
            val treeUri = row[5].takeUnless { it == "-" }

            val actual = when (operation) {
                "document" -> LocalMediaIdentity.identityFromDocument(
                    uri = uri,
                    relativePath = relativePath,
                    volumeName = volumeName,
                    treeUri = treeUri,
                )
                "relative" -> LocalMediaIdentity.identityFromRelativePath(relativePath, volumeName)
                else -> error("Unknown operation in shared media identity corpus: $operation")
            }

            assertEquals(
                "$caseId media identity",
                row[6].takeUnless { it == "-" },
                actual,
            )
        }
    }

    private fun readFixture(resourceName: String): List<List<String>> {
        val stream = javaClass.classLoader?.getResourceAsStream(resourceName)
            ?: error("Missing shared media-domain fixture: $resourceName")
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.drop(1)
                .filter { it.isNotBlank() }
                .map { it.split('\t') }
                .toList()
        }
    }

    private fun nullableInt(value: String): Int? =
        value.takeUnless { it == "-" }?.toInt()

    private fun nullableDouble(value: String): Double? =
        value.takeUnless { it == "-" }?.toDouble()
}
