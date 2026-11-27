package io.github.univers629.millet.core

import io.github.univers629.millet.system.ProcessSnapshotProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveImeAndMemoryTest {

    @Test
    fun `parseImePackage extracts package name from component id`() {
        assertEquals(
            "com.iflytek.inputmethod.miui",
            ActiveImeTracker.parseImePackage("com.iflytek.inputmethod.miui/.FlyIME")
        )
        assertEquals(
            "com.sohu.inputmethod.sogou.xiaomi",
            ActiveImeTracker.parseImePackage(" com.sohu.inputmethod.sogou.xiaomi/.SogouIME ")
        )
        assertNull(ActiveImeTracker.parseImePackage(null))
        assertNull(ActiveImeTracker.parseImePackage(""))
        assertNull(ActiveImeTracker.parseImePackage("invalid"))
    }

    @Test
    fun `parseAccessibilityPackages parses enabled services only when accessibility is enabled`() {
        val raw = "com.example.skip/.SkipService : com.google.android.marvin.talkback/.TalkBackService:invalid"
        assertTrue(ActiveImeTracker.parseAccessibilityPackages(0, raw).isEmpty())
        assertEquals(
            setOf("com.example.skip", "com.google.android.marvin.talkback"),
            ActiveImeTracker.parseAccessibilityPackages(1, raw)
        )
    }

    @Test
    fun `successful empty role read is known negative`() {
        val roles = ActiveImeTracker.readUserServiceRoles { null }
        assertNotNull(roles)
        assertEquals(UserServiceRoles(null, emptySet()), roles)
        for (uid in listOf(10131, 99910131)) {
            val protection = evaluateUserServiceProtection(uid, "com.example.app") { roles }
            assertEquals(ServiceRoleProtection.NONE, protection)
            assertFalse(protection.blocksFreeze)
        }
    }

    @Test
    fun `failure reading any role makes protection unknown`() {
        for (failedRead in 1..3) {
            var reads = 0
            val roles = ActiveImeTracker.readUserServiceRoles {
                if (++reads == failedRead) throw SecurityException("role read denied")
                null
            }
            assertNull(roles)
            for (uid in listOf(10131, 99910131)) {
                val protection = evaluateUserServiceProtection(uid, "com.example.app") { roles }
                assertEquals(ServiceRoleProtection.UNKNOWN, protection)
                assertTrue(protection.blocksFreeze)
            }
        }
        assertEquals(ServiceRoleProtection.UNKNOWN, evaluateUserServiceProtection(10131, "com.example.app") {
            throw UnsupportedOperationException("per-user role read unavailable")
        })
    }

    @Test
    fun `known roles protect matches while unrelated packages remain freezable`() {
        val roles = UserServiceRoles("com.example.ime", setOf("com.example.a11y"), "com.example.autofill")
        for (uid in listOf(10131, 99910131)) {
            assertEquals(ServiceRoleProtection.DEFAULT_IME, evaluateUserServiceProtection(uid, "com.example.ime") { roles })
            assertEquals(ServiceRoleProtection.ACCESSIBILITY, evaluateUserServiceProtection(uid, "com.example.a11y") { roles })
            // 自动填充仅用于亮屏解冻豁免，不纳入冻结硬保护。
            val autofill = evaluateUserServiceProtection(uid, "com.example.autofill") { roles }
            assertEquals(ServiceRoleProtection.NONE, autofill)
            assertFalse(autofill.blocksFreeze)
            val negative = evaluateUserServiceProtection(uid, "com.example.other") { roles }
            assertEquals(ServiceRoleProtection.NONE, negative)
            assertFalse(negative.blocksFreeze)
        }
    }

    @Test
    fun `readUserServiceRoles parses autofill service and tolerates autofill read failure`() {
        val parsed = ActiveImeTracker.readUserServiceRoles { key ->
            when (key) {
                ActiveImeTracker.SETTING_AUTOFILL_SERVICE -> "com.x8bit.bitwarden/com.x8bit.bitwarden.AutofillService"
                else -> null
            }
        }
        assertNotNull(parsed)
        assertEquals("com.x8bit.bitwarden", parsed!!.autofillPackage)

        val fallback = ActiveImeTracker.readUserServiceRoles { key ->
            if (key == ActiveImeTracker.SETTING_AUTOFILL_SERVICE) {
                throw SecurityException("autofill read denied")
            }
            null
        }
        assertNotNull(fallback)
        assertNull(fallback!!.autofillPackage)
    }

    @Test
    fun `full uid selects independent user roles without primary fallback`() {
        val primary = UserServiceRoles("com.example.primary.ime", setOf("com.example.primary.a11y"))
        val dual = UserServiceRoles("com.example.dual.ime", setOf("com.example.dual.a11y"))
        val snapshots = mapOf(0 to primary, 999 to dual)
        val queriedUsers = ArrayList<Int>()
        val reader: (Int) -> UserServiceRoles? = { userId ->
            queriedUsers.add(userId)
            snapshots[userId]
        }
        assertEquals(ServiceRoleProtection.DEFAULT_IME, evaluateUserServiceProtection(10131, primary.imePackage, reader))
        assertEquals(ServiceRoleProtection.NONE, evaluateUserServiceProtection(99910131, primary.imePackage, reader))
        assertEquals(ServiceRoleProtection.DEFAULT_IME, evaluateUserServiceProtection(99910131, dual.imePackage, reader))
        assertEquals(ServiceRoleProtection.NONE, evaluateUserServiceProtection(10131, dual.imePackage, reader))
        assertEquals(ServiceRoleProtection.ACCESSIBILITY, evaluateUserServiceProtection(99910131, "com.example.dual.a11y", reader))
        assertEquals(ServiceRoleProtection.NONE, evaluateUserServiceProtection(99910131, "com.example.primary.a11y", reader))
        assertEquals(listOf(0, 999, 999, 0, 999, 999), queriedUsers)

        val missingDualReader: (Int) -> UserServiceRoles? = { userId -> snapshots[userId].takeIf { userId == 0 } }
        assertEquals(ServiceRoleProtection.UNKNOWN, evaluateUserServiceProtection(99910131, primary.imePackage, missingDualReader))
        assertEquals(ServiceRoleProtection.DEFAULT_IME, evaluateUserServiceProtection(10131, primary.imePackage, missingDualReader))
    }

    @Test
    fun `unknown roles can recover on a later read without changing the other user`() {
        val primary = UserServiceRoles("com.example.primary.ime", emptySet())
        var dual: UserServiceRoles? = null
        val reader: (Int) -> UserServiceRoles? = { userId -> if (userId == 0) primary else dual }
        assertTrue(evaluateUserServiceProtection(99910131, "com.example.app", reader).blocksFreeze)
        dual = UserServiceRoles(null, emptySet())
        assertFalse(evaluateUserServiceProtection(99910131, "com.example.app", reader).blocksFreeze)
        assertEquals(ServiceRoleProtection.DEFAULT_IME, evaluateUserServiceProtection(10131, primary.imePackage, reader))
        dual = UserServiceRoles("com.example.app", emptySet())
        assertEquals(ServiceRoleProtection.DEFAULT_IME, evaluateUserServiceProtection(99910131, "com.example.app", reader))
    }

    @Test
    fun `missing context remains safety protected even for force freeze targets`() {
        val pkg = "com.example.app"
        for (uid in listOf(10131, 99910131)) {
            val target = AppUidUtils.encodeQueryTarget(pkg, uid)
            for (forced in listOf(false, true)) {
                val prefs = PrefsSnapshot(forceFreezeSet = if (forced) setOf(target) else emptySet())
                assertTrue(ActiveImeTracker.isProtectedDefaultIme(null, pkg, prefs, uid))
                assertTrue(ActiveImeTracker.isProtectedAccessibilityApp(null, pkg, prefs, uid))
                assertTrue(ActiveImeTracker.isProtectedSystemServiceApp(null, pkg, prefs, uid))
                assertEquals(ServiceRoleProtection.UNKNOWN, ActiveImeTracker.resolveServiceRoleProtection(null, pkg, uid))
            }
        }
    }

    @Test
    fun `role safety preserves invalid uid and missing package constraints`() {
        val prefs = PrefsSnapshot()
        for (uid in listOf(-1, 1000, 20131, 110131, 99920131)) {
            assertEquals(ServiceRoleProtection.NONE, evaluateUserServiceProtection(uid, "com.example.app") {
                throw AssertionError("unmanaged uid must not read roles")
            })
            assertFalse(ActiveImeTracker.isProtectedSystemServiceApp(null, "com.example.app", prefs, uid))
        }
        for (pkg in listOf(null, "")) {
            assertEquals(ServiceRoleProtection.NONE, evaluateUserServiceProtection(10131, pkg) {
                throw AssertionError("missing package must not read roles")
            })
            assertFalse(ActiveImeTracker.isProtectedSystemServiceApp(null, pkg, prefs, 10131))
        }
    }

    @Test
    fun `isToFrontReason recognizes activity start alongside resume and front`() {
        assertTrue(FreezeReasonClassifier.isToFrontReason("Activity Start"))
        assertTrue(FreezeReasonClassifier.isToFrontReason(" Activity Start!"))
        assertTrue(FreezeReasonClassifier.isToFrontReason("Activity Front"))
        assertTrue(FreezeReasonClassifier.isToFrontReason("Activity Resume"))
        assertTrue(FreezeReasonClassifier.isToFrontReason("toFrontActivity"))
    }

    @Test
    fun `parsePssKb reads kernel Pss field from smaps_rollup`() {
        val sample = """
            00000000-ffffffff ---p 00000000 00:00 0 [rollup]
            Rss:              263776 kB
            Pss:               47557 kB
            Pss_Dirty:         30680 kB
            SwapPss:          323894 kB
        """.trimIndent()
        assertEquals(47557L, ProcessSnapshotProvider.parsePssKb(sample))
        assertNull(ProcessSnapshotProvider.parsePssKb(""))
        assertNull(ProcessSnapshotProvider.parsePssKb("Rss: 100 kB\n"))
    }

    @Test
    fun `process package resolves dot named and colon processes to their installed owner`() {
        val gms = listOf("com.google.android.gms")
        for (name in listOf("com.google.android.gms", "com.google.android.gms.unstable", "com.google.android.gms.ui", "com.google.android.gms:persistent")) {
            assertEquals(gms.single(), ProcessSnapshotProvider.resolvePackageName(name, gms, gms))
            assertEquals(gms.single(), ProcessSnapshotProvider.resolvePackageName(name, gms, null))
        }
    }

    @Test
    fun `process package prefers per process ownership over shared uid ordering`() {
        val shared = listOf("com.example.first", "com.example.second")
        assertEquals("com.example.second", ProcessSnapshotProvider.resolvePackageName("custom.worker", shared, listOf("com.example.second")))
        assertEquals("com.example.second", ProcessSnapshotProvider.resolvePackageName("com.example.first:worker", shared, listOf("com.example.second")))
        assertEquals("com.example.second", ProcessSnapshotProvider.resolvePackageName("com.example.second:worker", shared, shared))
        assertEquals("com.example.second", ProcessSnapshotProvider.resolvePackageName("com.example.second:worker", shared, null))
        assertNull(ProcessSnapshotProvider.resolvePackageName("custom.worker", shared, shared))
        assertNull(ProcessSnapshotProvider.resolvePackageName("custom.worker", shared.reversed(), null))
    }

    @Test
    fun `process package rejects foreign owners and does not invent missing package names`() {
        val gms = listOf("com.google.android.gms")
        assertEquals(gms.single(), ProcessSnapshotProvider.resolvePackageName("custom.worker", gms, listOf("com.example.foreign")))
        assertEquals(gms.single(), ProcessSnapshotProvider.resolvePackageName("custom.worker", null, gms))
        assertNull(ProcessSnapshotProvider.resolvePackageName("com.google.android.gms.unstable", emptyList(), gms))
        assertNull(ProcessSnapshotProvider.resolvePackageName("com.google.android.gms.unstable", null, null))
        assertNull(ProcessSnapshotProvider.resolvePackageName("custom.worker", emptyList(), emptyList()))
    }

    @Test
    fun `snapshot keeps unconfirmed package ownership empty in v2`() {
        val row = "123|10131|10|20|do_epoll_wait|5|com.google.android.gms.unstable||S||Service|RUNNING"
        val entry = ProcessSnapshotCodec.decodeSnapshot("v2|1234\n$row")!!.entries.single()
        assertEquals("", entry.packageName)
        assertEquals("com.google.android.gms.unstable", entry.processName)
        assertEquals(123, entry.pid)
        assertEquals(10131, entry.uid)
        val legacy = ProcessSnapshotCodec.decodeSnapshot("v1\n${row.substringBeforeLast('|')}")!!.entries.single()
        assertEquals("com.google.android.gms.unstable", legacy.packageName)
    }

    @Test
    fun `snapshot groups gms aliases by real package without merging main and dual uids`() {
        val gms = listOf("com.google.android.gms")
        val rows = listOf(
            Triple(123, 10131, "com.google.android.gms.unstable"),
            Triple(124, 10131, "com.google.android.gms.ui"),
            Triple(125, 99910131, "com.google.android.gms.unstable"),
        ).map { (pid, uid, name) ->
            val owner = ProcessSnapshotProvider.resolvePackageName(name, gms, gms)
            "$pid|$uid|10|20|do_epoll_wait|5|$name|$owner|S|||RUNNING"
        }
        val entries = ProcessSnapshotCodec.decodeSnapshot("v2|1234\n${rows.joinToString("\n")}")!!.entries
        val groups = entries.groupBy { it.packageName to it.uid }
        assertEquals(setOf("com.google.android.gms" to 10131, "com.google.android.gms" to 99910131), groups.keys)
        assertEquals(listOf(123, 124), groups.getValue("com.google.android.gms" to 10131).map { it.pid })
        assertEquals(listOf(125), groups.getValue("com.google.android.gms" to 99910131).map { it.pid })
        assertEquals("com.google.android.gms.ui", entries[1].processName)
    }
}
