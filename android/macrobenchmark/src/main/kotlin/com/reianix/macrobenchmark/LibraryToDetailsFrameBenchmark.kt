package com.reianix.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkRule
import androidx.benchmark.macro.StartupMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual physical-device benchmark only. Requires an already configured
 * library with at least one anime; no emulator/CI runner is added to ReiAnix.
 */
@RunWith(AndroidJUnit4::class)
class LibraryToDetailsFrameBenchmark {
    @get:Rule val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun openAnimeAndReturnToLibrary() = benchmarkRule.measureRepeated(
        packageName = "com.reiflix.reiflix_local",
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 7,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            val library = device.wait(Until.findObject(By.desc("Biblioteca")), 15_000L)
                ?: error("Configure the app and open the main tabs before running benchmarks")
            library.click()
            device.waitForIdle()
            check(device.wait(Until.hasObject(By.descStartsWith("Abrir ")), 10_000L)) {
                "Benchmark requires an already populated ReiAnix library"
            }
        },
    ) {
        val firstCard = device.wait(Until.findObject(By.descStartsWith("Abrir ")), 10_000L)
            ?: error("The first anime card did not appear")
        firstCard.click()
        check(device.wait(Until.hasObject(By.desc("Voltar")), 10_000L)) {
            "Details page did not become accessible"
        }
        device.pressBack()
        check(device.wait(Until.hasObject(By.descStartsWith("Abrir ")), 10_000L)) {
            "Library did not return after leaving Details"
        }
    }
}
