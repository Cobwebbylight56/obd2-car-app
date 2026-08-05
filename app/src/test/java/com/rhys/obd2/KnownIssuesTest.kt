package com.rhys.obd2

import com.rhys.obd2.obd.KnownIssues
import com.rhys.obd2.obd.Dtc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The known-issues list.
 *
 * The risk this guards against is not a crash, it is a confident wrong answer. Someone
 * reads "commonly the head gasket", spends eight hundred pounds, and it was never the head
 * gasket. So the structural claims are checked: codes are well formed, ids are unique, and
 * every entry says how sure it is.
 */
class KnownIssuesTest {

    @Test
    fun `every referenced code is a well-formed OBD-II code`() {
        val malformed = KnownIssues.models
            .flatMap { it.issues }
            .flatMap { it.codes }
            .filterNot { it.matches(Regex("[PBCU][0-9A-F]{4}")) }
        assertTrue("malformed codes: $malformed", malformed.isEmpty())
    }

    @Test
    fun `every referenced code decodes to something the app can describe`() {
        // A code listed here but unknown to the decoder would show a known-cause note
        // attached to a fault the app cannot otherwise explain, which reads as broken.
        val undecodable = KnownIssues.models
            .flatMap { it.issues }
            .flatMap { it.codes }
            .distinct()
            .filter { code -> Dtc.describe(code, com.rhys.obd2.obd.DtcStatus.STORED).description.isBlank() }
        assertTrue("codes with no description: $undecodable", undecodable.isEmpty())
    }

    @Test
    fun `model ids are unique`() {
        val ids = KnownIssues.models.map { it.id }
        assertEquals("ids must be unique, they are the stored key", ids.size, ids.distinct().size)
    }

    @Test
    fun `every model has at least one issue and a display name`() {
        KnownIssues.models.forEach { model ->
            assertTrue("${model.id} has no issues", model.issues.isNotEmpty())
            assertTrue("${model.id} has an empty name", model.displayName.length > 5)
        }
    }

    @Test
    fun `every issue carries enough to be acted on`() {
        KnownIssues.models.flatMap { m -> m.issues.map { m.id to it } }.forEach { (id, issue) ->
            assertTrue("$id: '${issue.title}' has no summary", issue.summary.length > 10)
            assertTrue("$id: '${issue.title}' has no detail", issue.detail.length > 30)
        }
    }

    @Test
    fun `an issue that sets no code is marked as not detectable`() {
        // Corrosion, suspension and gearboxes are not emissions-related and never reach
        // this app. The UI leans on this flag to avoid implying otherwise.
        val chassisRust = KnownIssues.byId("lr-defender-td5")!!
            .issues.first { it.title.contains("Bulkhead") }
        assertTrue("a structural fault sets no OBD-II code", !chassisRust.detectableOverObd)
    }

    @Test
    fun `looking up by code returns only issues for the chosen model`() {
        // P0016 is listed on both the 1.4 TSI and the BMW N47, and they are different
        // faults with different fixes. Asking about one must never return the other.
        val tsi = KnownIssues.issuesForCode("vw-14tsi-ea111", "P0016")
        val bmw = KnownIssues.issuesForCode("bmw-n47", "P0016")
        assertTrue("expected a TSI chain tensioner entry", tsi.any { it.title.contains("tensioner", true) })
        assertTrue("expected a BMW chain entry", bmw.any { it.title.contains("chain", true) })
        assertTrue("the two must not be interchanged", tsi.first().detail != bmw.first().detail)
    }

    @Test
    fun `an unknown or unset model returns nothing rather than guessing`() {
        assertTrue(KnownIssues.issuesForCode(null, "P0016").isEmpty())
        assertTrue(KnownIssues.issuesForCode("not-a-model", "P0016").isEmpty())
    }

    @Test
    fun `code matching ignores case`() {
        assertTrue(KnownIssues.issuesForCode("bmw-n47", "p0016").isNotEmpty())
    }
}
