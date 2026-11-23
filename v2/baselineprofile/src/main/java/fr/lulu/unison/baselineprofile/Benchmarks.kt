package fr.lulu.unison.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mesures avant/après sur le téléphone branché :
 * ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 * Chaque mesure est faite sans profil puis avec, pour voir ce que le profil apporte.
 */
@RunWith(AndroidJUnit4::class)
class Benchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test fun startupWithoutProfile() = startup(CompilationMode.None())
    @Test fun startupWithProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test fun scrollWithoutProfile() = scroll(CompilationMode.None())
    @Test fun scrollWithProfile() = scroll(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        iterations = 10,
        startupMode = StartupMode.COLD,
    ) { startToHome() }

    private fun scroll(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = { startToHome() },
    ) {
        scrollHome()
        openPlayer()
    }
}
