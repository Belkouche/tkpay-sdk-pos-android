package ma.tkpay.naps

import kotlinx.coroutines.runBlocking
import ma.tkpay.naps.config.NapsConfig
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the README samples (ReadmeExamples) against an emulator in approve mode.
 * Skipped unless NAPS_EMULATOR_PORT is set and NAPS_EMULATOR_MODE is approve (or unset).
 */
class ReadmeExamplesTest {

    @Test
    fun readmeSamplesRunAgainstEmulator() = runBlocking {
        val port = System.getenv("NAPS_EMULATOR_PORT")?.toIntOrNull()
        assumeTrue("NAPS_EMULATOR_PORT not set", port != null)
        assumeTrue((System.getenv("NAPS_EMULATOR_MODE") ?: "approve") == "approve")
        val client = NapsPayClient(NapsConfig(host = "127.0.0.1", port = port!!))
        val saved = ReadmeExamples.payment(client)
        assertNotNull(saved)
        ReadmeExamples.void(client, saved!!.first, saved.second, saved.third)
        ReadmeExamples.otherOperations(client)
        ReadmeExamples.responseCodes()
    }
}
