package com.billmongan.herdrdroid.herdr

import com.billmongan.herdrdroid.ssh.Credentials
import com.billmongan.herdrdroid.ssh.HerdrSsh
import com.billmongan.herdrdroid.ssh.KeySource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in: a disposable loopback sshd with synthetic CLI and pane fixtures only. */
class PassiveQuotaSshTest {
    @Test fun automaticQuotaDoesNotWriteToWrappedOrEmptyComposers() = runBlocking {
        val port = System.getenv("HD_QUOTA_FIXTURE_PORT")?.toIntOrNull()
        assumeTrue("no synthetic quota fixture", port != null)
        val key = File(checkNotNull(System.getenv("HD_QUOTA_FIXTURE_KEY"))).readText()
        val ssh = HerdrSsh("127.0.0.1", port!!, "dev", Credentials(KeySource.Imported(key, null), null),
            checkNotNull(System.getenv("HD_QUOTA_FIXTURE_PIN")), { null })
        try {
            val h = Herdr(ssh, "/fixture/herdr")
            val panes = listOf("wrapped", "empty")
            for (profile in QuotaProfiles.builtIn.filterNot { it.hasPassiveSource }) {
                assertNull(profile.agents.toString(), h.readQuota(panes, profile))
            }
            val vibe = QuotaProfiles.forAgent("vibe")!!
            val muse = QuotaProfiles.forAgent("muse")!!.copy(cliCommand = "/fixture/herdr muse usage --json")
            assertEquals(17.0, h.readQuota(listOf("vibe"), vibe)?.single()?.usedPercent)
            assertEquals(23.0, h.readQuota(panes, muse)?.single()?.usedPercent)
            val trace = ssh.exec("/fixture/herdr trace")
            assertEquals(0, trace.exit)
            assertEquals(listOf("pane read vibe --source visible", "muse usage --json"), trace.out.lines().filter { it.isNotBlank() })
        } finally {
            ssh.close()
        }
    }
}
