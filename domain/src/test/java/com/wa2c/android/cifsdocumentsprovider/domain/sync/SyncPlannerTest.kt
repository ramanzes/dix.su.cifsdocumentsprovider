package com.wa2c.android.cifsdocumentsprovider.domain.sync

import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncAction
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncActionType.DELETE_LOCAL
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncActionType.DELETE_REMOTE
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncActionType.DOWNLOAD
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncActionType.UPLOAD
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncBlockReason
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncEntry
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncMode
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {

    private fun file(size: Long, time: Long = 1000) = SyncSide(isDirectory = false, size = size, lastModified = time)
    private val dir = SyncSide(isDirectory = true)

    /** same.txt on both sides, phone.txt and dir p/ only on the phone, server.txt and dir s/ only on the server, diff.txt differs */
    private val entries = listOf(
        SyncEntry("same.txt", file(10), file(10)),
        SyncEntry("phone.txt", file(5), null),
        SyncEntry("server.txt", null, file(7)),
        SyncEntry("diff.txt", file(3, time = 2000), file(4, time = 1000)),
        SyncEntry("p", dir, null),
        SyncEntry("p/a.txt", file(1), null),
        SyncEntry("p/q", dir, null),
        SyncEntry("p/q/b.txt", file(2), null),
        SyncEntry("s", null, dir),
        SyncEntry("s/c.txt", null, file(6)),
    )

    private fun SyncMode.plan() = SyncPlanner.plan(this, entries, emptyList())

    private fun List<SyncAction>.summary() = map { "${it.type} ${it.path}" + if (it.overwrite) " !" else "" }.toSet()

    @Test
    fun upload_onlyUploads() {
        val plan = SyncMode.UPLOAD.plan()
        assertEquals(setOf("UPLOAD diff.txt !", "UPLOAD phone.txt", "UPLOAD p/a.txt", "UPLOAD p/q/b.txt"), plan.actions.summary())
        assertFalse(plan.needsConfirm)
        assertNull(plan.blockReason)
    }

    @Test
    fun twoWay_copiesBothWays_newerWins_neverDeletes() {
        val plan = SyncMode.TWO_WAY.plan()
        assertEquals(
            setOf("UPLOAD diff.txt !", "UPLOAD phone.txt", "UPLOAD p/a.txt", "UPLOAD p/q/b.txt", "DOWNLOAD server.txt", "DOWNLOAD s/c.txt"),
            plan.actions.summary(),
        )
        assertTrue(plan.deletes.isEmpty())
    }

    @Test
    fun twoWay_sameTime_isConflict() {
        val plan = SyncPlanner.plan(SyncMode.TWO_WAY, listOf(SyncEntry("x", file(1, 500), file(2, 500))), emptyList())
        assertTrue(plan.actions.isEmpty())
        assertEquals(listOf("x"), plan.conflicts)
    }

    @Test
    fun twoWay_unknownTime_isConflict() {
        val plan = SyncPlanner.plan(SyncMode.TWO_WAY, listOf(SyncEntry("x", file(1, 500), file(2, 0))), emptyList())
        assertEquals(listOf("x"), plan.conflicts)
    }

    @Test
    fun mirrorToServer_deletesServerOnly_folderAsWhole() {
        val plan = SyncMode.MIRROR_TO_SERVER.plan()
        assertEquals(
            setOf("UPLOAD diff.txt !", "UPLOAD phone.txt", "UPLOAD p/a.txt", "UPLOAD p/q/b.txt", "DELETE_REMOTE server.txt", "DELETE_REMOTE s"),
            plan.actions.summary(),
        )
        val folder = plan.actions.single { it.path == "s" }
        assertTrue(folder.isDirectory)
        assertEquals(1, folder.fileCount)
        assertEquals(6L, folder.size)
        assertEquals(2, plan.deletedFiles)
        assertTrue(plan.needsConfirm)
    }

    @Test
    fun mirrorToPhone_deletesPhoneOnly_nestedFolderOnce() {
        val plan = SyncMode.MIRROR_TO_PHONE.plan()
        assertEquals(
            setOf("DOWNLOAD diff.txt !", "DOWNLOAD server.txt", "DOWNLOAD s/c.txt", "DELETE_LOCAL phone.txt", "DELETE_LOCAL p"),
            plan.actions.summary(),
        )
        assertEquals(2, plan.actions.single { it.path == "p" }.fileCount)
        assertEquals(3, plan.deletedFiles)
    }

    @Test
    fun mirror_typeMismatch_isConflict() {
        val plan = SyncPlanner.plan(SyncMode.MIRROR_TO_SERVER, listOf(SyncEntry("x", file(1), dir)), emptyList())
        assertTrue(plan.actions.isEmpty())
        assertEquals(listOf("x"), plan.conflicts)
    }

    @Test
    fun mirror_failedFolder_blocks() {
        val plan = SyncPlanner.plan(SyncMode.MIRROR_TO_SERVER, entries, listOf("p/q"))
        assertEquals(SyncBlockReason.FAILED_FOLDERS, plan.blockReason)
        // Two-way does not delete, an unreadable folder is only skipped
        assertNull(SyncPlanner.plan(SyncMode.TWO_WAY, entries, listOf("p/q")).blockReason)
    }

    @Test
    fun mirror_emptySource_blocks() {
        val serverOnly = listOf(SyncEntry("a", null, file(1)), SyncEntry("b", null, file(1)))
        val plan = SyncPlanner.plan(SyncMode.MIRROR_TO_SERVER, serverOnly, emptyList())
        assertEquals(SyncBlockReason.EMPTY_SOURCE, plan.blockReason)
        assertTrue(plan.needsStrongConfirm)
    }

    @Test
    fun strongConfirm_aboveTwentyPercent() {
        val many = (1..10).map { SyncEntry("f$it", file(1), file(1)) }
        val one = SyncPlanner.plan(SyncMode.MIRROR_TO_SERVER, many + SyncEntry("x", null, file(1)), emptyList())
        assertFalse(one.needsStrongConfirm) // 1 of 11
        val three = SyncPlanner.plan(SyncMode.MIRROR_TO_SERVER, many + (1..3).map { SyncEntry("x$it", null, file(1)) }, emptyList())
        assertTrue(three.needsStrongConfirm) // 3 of 13
    }

    @Test
    fun writeAccess_blocks() {
        assertEquals(SyncBlockReason.REMOTE_READ_ONLY, SyncPlanner.plan(SyncMode.UPLOAD, entries, emptyList(), canWriteRemote = false).blockReason)
        assertEquals(SyncBlockReason.LOCAL_NO_WRITE, SyncPlanner.plan(SyncMode.MIRROR_TO_PHONE, entries, emptyList(), canWriteLocal = false).blockReason)
        // Download-only mirror on a read-only connection is fine
        val downloadOnly = listOf(SyncEntry("a", null, file(1)))
        assertNull(SyncPlanner.plan(SyncMode.MIRROR_TO_PHONE, downloadOnly, emptyList(), canWriteRemote = false).blockReason)
    }

    @Test
    fun actions_sortedDeletesLast() {
        val types = SyncMode.MIRROR_TO_SERVER.plan().actions.map { it.type }
        assertEquals(types.sortedBy { it.ordinal }, types)
        assertEquals(DELETE_REMOTE, types.last())
        assertTrue(UPLOAD.ordinal < DOWNLOAD.ordinal && DOWNLOAD.ordinal < DELETE_LOCAL.ordinal)
    }
}
