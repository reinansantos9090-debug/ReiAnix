package com.reianix.reianix_local.downloads

import org.junit.Assert.*
import org.junit.Test

class DownloadResourceIdentityTest {
    @Test fun signedCloudObjectProvidesFriendlyName() {
        val url="https://bucket.r2.cloudflarestorage.com/hub/8fe665d3e6f704da38443162bcd2a408" +
            "?X-Amz-Expires=28800&response-content-disposition=" +
            "attachment%3B%20filename%3D%22Hanaori-san.mp4%22"
        assertEquals("Hanaori-san.mp4",DownloadResourceIdentity.fromUrl(url))
        assertEquals("Hanaori-san.mp4",DownloadResourceIdentity.bestName(null,url,"application/octet-stream"))
        assertTrue(DownloadResourceIdentity.opaque("8fe665d3e6f704da38443162bcd2a408.bin"))
    }

    @Test fun contentDispositionExtendedUtf8WinsOverFallback() {
        assertEquals("Episódio 01.mp4",DownloadResourceIdentity.disposition(
            "attachment; filename=video.bin; filename*=UTF-8''Epis%C3%B3dio%2001.mp4"))
        assertEquals("A+B.mp4",DownloadResourceIdentity.disposition(
            "attachment; filename*=UTF-8''A%2BB.mp4"))
        assertEquals("Nome Final.mp4",DownloadResourceIdentity.disposition(
            "attachment; filename=\"Nome Final.mp4\""))
    }

    @Test fun rejectsUnsafePathAndDoesNotPromoteOpaqueIds() {
        assertEquals("episode.mp4",DownloadResourceIdentity.disposition("attachment; filename=\"../episode.mp4\""))
        assertNull(DownloadResourceIdentity.fromUrl("https://cdn.example/resource/4af0f294e36b91c271e83430.mp4?sign=token"))
        assertNull(DownloadResourceIdentity.fromUrl("https://cdn.example/streams/master.m3u8"))
    }

    @Test fun pathOnlyInfersEpisodeWhenSeriesSlugAndNumberExplicit() {
        assertEquals("Solo Leveling Dublado - Episódio 01",
            DownloadResourceIdentity.contextualEpisodeName(
                "https://cdn.example/streams/s/solo-leveling-dublado/01/720p/index.m3u8"))
        assertNull(DownloadResourceIdentity.contextualEpisodeName("https://cdn.example/resource/0123456789abcdef.mp4"))
    }

    @Test fun contentTypeProvidesExtensionWithoutPretendingBinIsVideo() {
        assertEquals("Hanaori-san.mp4",DownloadResourceIdentity.bestName(
            null,"https://cdn.example/s/Hanaori-san","video/mp4"))
        assertEquals("Hanaori-san",DownloadResourceIdentity.bestName(
            null,"https://cdn.example/s/Hanaori-san","application/octet-stream"))
    }
    @Test fun rejectsMixedCaseCdnCapabilityWithoutRejectingEpisodeFile() {
        val token="v1.zW4qkAXt-NJKrGdCHKFzzfPFHquCg123K6aN6a9yNsAQdiftVMcDuJWCIAr.mp4"
        assertTrue(DownloadResourceIdentity.opaque(token))
        assertNull(DownloadResourceIdentity.bestName(null,"https://cdn.example/$token","video/mp4"))
        assertFalse(DownloadResourceIdentity.opaque(
            "Hanaori-san.Still.Wants.to.Fight.in.the.Next.Life.S01E01.720p.mp4"))
    }
}
