package com.reiflix.reiflix_local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class ReiAnixBuildSecretSafetyTest {
    @Test
    fun tmdbReadTokenIsNotACompiledBuildConfigField() {
        assertFalse(
            BuildConfig::class.java.declaredFields.any { it.name == "TMDB_READ_ACCESS_TOKEN" },
        )
    }

    @Test
    fun onlyTheNonSecretBackendOriginIsUsedForTmdbBuildConfiguration() {
        val backendField = BuildConfig::class.java.declaredFields.singleOrNull {
            it.name == "TMDB_METADATA_BACKEND_URL"
        }
        assertTrue("TMDB backend URL BuildConfig field should exist", backendField != null)
        assertEquals(String::class.java, backendField!!.type)
        val value = backendField.get(null) as String
        assertTrue(value.isEmpty() || value.startsWith("https://"))
        assertFalse(value.contains("@"))
    }

    @Test
    fun buildConfigDoesNotContainJwtShapedStringConstants() {
        val jwtPattern = Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}")
        val stringFields = BuildConfig::class.java.declaredFields.filter {
            Modifier.isStatic(it.modifiers) && it.type == String::class.java
        }
        val credentialLikeValues = stringFields.mapNotNull { field ->
            (field.get(null) as? String)?.takeIf(jwtPattern::containsMatchIn)?.let { field.name }
        }
        assertTrue("JWT-shaped BuildConfig values are forbidden: $credentialLikeValues", credentialLikeValues.isEmpty())
    }
}
