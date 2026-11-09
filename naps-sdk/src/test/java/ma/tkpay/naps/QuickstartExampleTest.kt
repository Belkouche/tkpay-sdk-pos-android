package ma.tkpay.naps

import ma.tkpay.naps.examples.main
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs examples/quickstart/Quickstart.kt (compiled into this test source set) against
 * the emulator. Skipped unless NAPS_PORT is set:
 *
 *   python3 naps_emulator.py --port 4454
 *   NAPS_PORT=4454 ./gradlew :naps-sdk:testDebugUnitTest --tests '*QuickstartExampleTest' --rerun -i
 */
class QuickstartExampleTest {

    @Test
    fun quickstartRunsAgainstEmulator() {
        assumeTrue("NAPS_PORT not set", System.getenv("NAPS_PORT") != null)
        main()
    }
}
