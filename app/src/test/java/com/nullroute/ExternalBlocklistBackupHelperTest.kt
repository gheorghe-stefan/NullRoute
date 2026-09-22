package com.nullroute

import com.nullroute.data.BlockedDomain
import com.nullroute.data.ExternalBlocklistBackupHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExternalBlocklistBackupHelperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testJsonSerializationAndParsingPreservesOrderAndAttributes() {
        val domains = listOf(
            BlockedDomain("reddit.com", isRemovable = true),
            BlockedDomain("tiktok.com", isRemovable = false),
            BlockedDomain("instagram.com", isRemovable = true),
            BlockedDomain("twitter.com", isRemovable = false)
        )

        val json = ExternalBlocklistBackupHelper.serializeToJson(domains)
        val parsed = ExternalBlocklistBackupHelper.parseJson(json)

        assertEquals(4, parsed.size)
        assertEquals("reddit.com", parsed[0].domain)
        assertTrue(parsed[0].isRemovable)

        assertEquals("tiktok.com", parsed[1].domain)
        assertEquals(false, parsed[1].isRemovable)

        assertEquals("instagram.com", parsed[2].domain)
        assertTrue(parsed[2].isRemovable)

        assertEquals("twitter.com", parsed[3].domain)
        assertEquals(false, parsed[3].isRemovable)
    }

    @Test
    fun testTxtSerializationAndParsingPreservesOrderAndAttributes() {
        val domains = listOf(
            BlockedDomain("reddit.com", isRemovable = true),
            BlockedDomain("tiktok.com", isRemovable = false),
            BlockedDomain("instagram.com", isRemovable = true),
            BlockedDomain("twitter.com", isRemovable = false)
        )

        val txt = ExternalBlocklistBackupHelper.serializeToTxt(domains)
        val parsed = ExternalBlocklistBackupHelper.parseTxt(txt)

        assertEquals(4, parsed.size)
        assertEquals("reddit.com", parsed[0].domain)
        assertTrue(parsed[0].isRemovable)

        assertEquals("tiktok.com", parsed[1].domain)
        assertEquals(false, parsed[1].isRemovable)

        assertEquals("instagram.com", parsed[2].domain)
        assertTrue(parsed[2].isRemovable)

        assertEquals("twitter.com", parsed[3].domain)
        assertEquals(false, parsed[3].isRemovable)
    }

    @Test
    fun testApplyProLimitEnforcesTwoDomainsForFreeUsers() {
        val domains = listOf(
            BlockedDomain("reddit.com", isRemovable = true),
            BlockedDomain("tiktok.com", isRemovable = false),
            BlockedDomain("instagram.com", isRemovable = true),
            BlockedDomain("youtube.com", isRemovable = true),
            BlockedDomain("facebook.com", isRemovable = false)
        )

        // Free tier (not Pro) -> only first 2
        val freeRestored = ExternalBlocklistBackupHelper.applyProLimit(domains, isPro = false)
        assertEquals(2, freeRestored.size)
        assertEquals("reddit.com", freeRestored[0].domain)
        assertEquals("tiktok.com", freeRestored[1].domain)

        // Pro tier -> all entries preserved in order
        val proRestored = ExternalBlocklistBackupHelper.applyProLimit(domains, isPro = true)
        assertEquals(5, proRestored.size)
        assertEquals("reddit.com", proRestored[0].domain)
        assertEquals("tiktok.com", proRestored[1].domain)
        assertEquals("instagram.com", proRestored[2].domain)
        assertEquals("youtube.com", proRestored[3].domain)
        assertEquals("facebook.com", proRestored[4].domain)
    }

    @Test
    fun testApplyProLimitWithFewerThanTwoDomains() {
        val singleDomain = listOf(BlockedDomain("single.com", isRemovable = true))

        val freeRestored = ExternalBlocklistBackupHelper.applyProLimit(singleDomain, isPro = false)
        assertEquals(1, freeRestored.size)
        assertEquals("single.com", freeRestored[0].domain)

        val emptyDomains = emptyList<BlockedDomain>()
        val freeEmpty = ExternalBlocklistBackupHelper.applyProLimit(emptyDomains, isPro = false)
        assertEquals(0, freeEmpty.size)
    }

    @Test
    fun testDirectorySaveAndLoadFromTxtFallback() {
        val rootDir = tempFolder.newFolder("storage")
        val domains = listOf(
            BlockedDomain("alpha.com", isRemovable = true),
            BlockedDomain("beta.com", isRemovable = false),
            BlockedDomain("gamma.com", isRemovable = true)
        )

        val txt = ExternalBlocklistBackupHelper.serializeToTxt(domains)
        val backupDir = java.io.File(rootDir, "NullRoute").apply { mkdirs() }
        java.io.File(backupDir, "blocked_domains.txt").writeText(txt)

        // Load as Free user (first 2 domains)
        val loadedFree = ExternalBlocklistBackupHelper.loadFromDirectory(backupDir, isPro = false)
        assertNotNull(loadedFree)
        assertEquals(2, loadedFree!!.size)
        assertEquals("alpha.com", loadedFree[0].domain)
        assertEquals("beta.com", loadedFree[1].domain)

        // Load as Pro user (all 3 domains)
        val loadedPro = ExternalBlocklistBackupHelper.loadFromDirectory(backupDir, isPro = true)
        assertNotNull(loadedPro)
        assertEquals(3, loadedPro!!.size)
        assertEquals("alpha.com", loadedPro[0].domain)
        assertEquals("beta.com", loadedPro[1].domain)
        assertEquals("gamma.com", loadedPro[2].domain)
    }

    @Test
    fun testTrySaveToPublicDirectoryCreatesFiles() {
        val rootDir = tempFolder.newFolder("public_docs")
        val domains = listOf(
            BlockedDomain("example.com", isRemovable = true)
        )

        val json = ExternalBlocklistBackupHelper.serializeToJson(domains)
        val txt = ExternalBlocklistBackupHelper.serializeToTxt(domains)

        ExternalBlocklistBackupHelper.trySaveToPublicDirectory(rootDir, json, txt)

        val targetDir = java.io.File(rootDir, "NullRoute")
        assertTrue(targetDir.exists())
        assertTrue(java.io.File(targetDir, "blocked_domains.txt").exists())
        assertEquals(txt, java.io.File(targetDir, "blocked_domains.txt").readText())
    }

    @Test
    fun testStrictlyOneFileMaintainedAndDuplicatesCleaned() {
        val rootDir = tempFolder.newFolder("cleanup_test")
        val backupDir = java.io.File(rootDir, "NullRoute").apply { mkdirs() }

        // Simulate legacy duplicate files
        java.io.File(backupDir, "blocked_domains (1).txt").writeText("old")
        java.io.File(backupDir, "blocked_domains (15).txt").writeText("old")
        java.io.File(backupDir, "blocked_domains.json").writeText("old")
        java.io.File(backupDir, "blocked_domains (15).json").writeText("old")

        val newDomains = listOf(BlockedDomain("latest.com", isRemovable = false))
        val newTxt = ExternalBlocklistBackupHelper.serializeToTxt(newDomains)

        ExternalBlocklistBackupHelper.trySaveToPublicDirectory(rootDir, newTxt)

        val remainingFiles = backupDir.listFiles()?.map { it.name } ?: emptyList()
        assertEquals(1, remainingFiles.size)
        assertEquals("blocked_domains.txt", remainingFiles[0])
        assertEquals(newTxt, java.io.File(backupDir, "blocked_domains.txt").readText())
    }

    @Test
    fun testInvalidSyntaxDomainsAreIgnored() {
        val rawTxt = """
            # Comments should be ignored
            valid-domain.com|true|removable
            notadomain|true|removable
            http://another-valid.org/path|false|permanent
            invalid..dot|true|removable
            .leadingdot.com|true|removable
            third-valid.net|true|removable
            :::invalid:::|true|removable
        """.trimIndent()

        val parsed = ExternalBlocklistBackupHelper.parseTxt(rawTxt)
        assertEquals(3, parsed.size)
        assertEquals("valid-domain.com", parsed[0].domain)
        assertEquals(true, parsed[0].isRemovable)

        assertEquals("another-valid.org", parsed[1].domain)
        assertEquals(false, parsed[1].isRemovable)

        assertEquals("third-valid.net", parsed[2].domain)
        assertEquals(true, parsed[2].isRemovable)
    }

    @Test
    fun testDuplicateDomainsAreIgnoredPreservingFirstOccurrence() {
        val rawTxt = """
            reddit.com|true|removable
            https://www.reddit.com/|false|permanent
            REDDIT.COM|true|removable
            twitter.com|false|permanent
        """.trimIndent()

        val parsed = ExternalBlocklistBackupHelper.parseTxt(rawTxt)
        assertEquals(2, parsed.size)
        assertEquals("reddit.com", parsed[0].domain)
        assertEquals(true, parsed[0].isRemovable) // first occurrence preserved
        assertEquals("twitter.com", parsed[1].domain)
        assertEquals(false, parsed[1].isRemovable)
    }

    @Test
    fun testNonExistentDirectoryReturnsNull() {
        val nonExistentDir = java.io.File(tempFolder.root, "does_not_exist")
        val result = ExternalBlocklistBackupHelper.loadFromDirectory(nonExistentDir, isPro = true)
        org.junit.Assert.assertNull(result)
    }
}
