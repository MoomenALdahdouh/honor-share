package com.honor.share.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.UUID

class TransferPackageTest {
    @Test
    fun packageIdIsNotTheDisplayName() {
        val now = 1_776_000_000_000L
        val pkg = TransferPackage.create(
            sourceDeviceId = "device",
            sourceDeviceName = "MacBook Pro",
            sourceOs = "macos",
            files = listOf(sampleFile("photo.jpg", 100, "aaa")),
            now = now,
        )
        assertTrue(pkg.packageId.isNotBlank())
        assertNotEquals(pkg.displayName(now), pkg.packageId)
        assertTrue(pkg.displayName(now).contains("—") || pkg.displayName(now).length >= 4)
    }

    @Test
    fun illegalPackageTransitionFails() {
        try {
            PackageMachine.transition(PackageState.DRAFT, PackageState.COMPLETED)
            throw AssertionError("expected failure")
        } catch (error: ProtocolException) {
            assertEquals(ErrorCode.PROTOCOL_VIOLATION, error.code)
        }
    }

    @Test
    fun readyToWaitingIsLegal() {
        val state = PackageMachine.transition(PackageState.READY, PackageState.WAITING_FOR_RECEIVER)
        assertEquals(PackageState.WAITING_FOR_RECEIVER, state)
    }
}

class PackageInvitationTest {
    @Test
    fun roundTripAndRejectsShareLinkAndJunk() {
        val invite = PackageInvitation.create(
            host = "10.189.45.67",
            port = 49221,
            deviceId = "abc-id",
            os = "macos",
            packageId = "pkg-1",
            nowEpochSec = 1_700_000_000,
            numericCode = "482731",
        )
        val encoded = invite.encode()
        assertTrue(encoded.startsWith("HS2|"))
        assertFalse(encoded.contains("photo.jpg"))
        val parsed = PackageInvitation.parse(encoded)
        assertEquals(invite, parsed)
        assertNull(PackageInvitation.parse("not-a-code"))
        assertNull(PackageInvitation.parse("HS1|10.0.0.1|80|id|os|name"))
        assertEquals("482 731", invite.displayCode())
    }

    @Test
    fun inviteServiceNameEncodesAndParsesSixDigitCodes() {
        assertEquals("HS-851802", ProtocolConstants.inviteServiceName("851802"))
        assertEquals("851802", ProtocolConstants.inviteCodeFromServiceName("HS-851802"))
        assertEquals("851802", ProtocolConstants.inviteCodeFromServiceName("HS-851802 (2)"))
        assertNull(ProtocolConstants.inviteCodeFromServiceName("HS-abcdef12"))
    }

    @Test
    fun expiredInvitationIsDetectedWithoutParsingFailure() {
        val invite = PackageInvitation.create(
            host = "10.0.0.1",
            port = 1,
            deviceId = "id",
            os = "macos",
            packageId = UUID.randomUUID().toString(),
            ttlSec = 10,
            nowEpochSec = 1_000,
            numericCode = "000001",
        )
        assertFalse(invite.isExpired(1_009))
        assertTrue(invite.isExpired(1_010))
        assertEquals(0, invite.remainingSeconds(1_010))
        assertEquals(9, invite.remainingSeconds(1_001))
    }

    @Test
    fun regenerateCreatesNewInviteIdAndCodeWithoutNewPackageId() {
        val first = PackageInvitation.create(
            host = "10.0.0.1",
            port = 9,
            deviceId = "id",
            os = "android",
            packageId = "same-package",
            numericCode = "111111",
        )
        val second = PackageInvitation.create(
            host = "10.0.0.1",
            port = 9,
            deviceId = "id",
            os = "android",
            packageId = "same-package",
            numericCode = "222222",
        )
        assertEquals(first.packageId, second.packageId)
        assertNotEquals(first.inviteId, second.inviteId)
        assertNotEquals(first.numericCode, second.numericCode)
    }
}

class InviteRateLimiterTest {
    @Test
    fun locksAfterTooManyFailures() {
        val limiter = InviteRateLimiter(maxAttempts = 3, windowMs = 60_000, lockoutMs = 10_000)
        val key = "invite-1"
        assertTrue(limiter.allow(key, 0))
        limiter.recordFailure(key, 0)
        limiter.recordFailure(key, 1)
        assertTrue(limiter.allow(key, 2))
        limiter.recordFailure(key, 2)
        assertFalse(limiter.allow(key, 3))
        limiter.recordSuccess(key)
        assertTrue(limiter.allow(key, 4))
    }
}

class ComparisonEngineTest {
    @Test
    fun sameHashSkipsEvenIfNameDiffers() {
        val incoming = listOf(sampleFile("photo.jpg", 100, "abcd"))
        val dest = listOf(DestinationFile("IMG_0001.jpg", 100, "ABCD"))
        val result = ComparisonEngine.compare(incoming, dest)
        assertEquals(1, result.alreadyPresent.size)
        assertTrue(result.needsTransfer.isEmpty())
        assertTrue(result.conflicts.isEmpty())
        assertEquals(listOf(incoming[0].fileId), result.skipFileIds)
    }

    @Test
    fun filenameAloneNeverSkips() {
        val incoming = listOf(sampleFile("photo.jpg", 100, null))
        val dest = listOf(DestinationFile("photo.jpg", 100, null))
        val result = ComparisonEngine.compare(incoming, dest)
        assertTrue(result.alreadyPresent.isEmpty())
        assertEquals(1, result.conflicts.size)
        assertEquals("photo.jpg", result.conflicts[0].existingName)
    }

    @Test
    fun differentHashSameNameIsConflict() {
        val incoming = listOf(sampleFile("photo.jpg", 100, "aaaa"))
        val dest = listOf(DestinationFile("photo.jpg", 100, "bbbb"))
        val result = ComparisonEngine.compare(incoming, dest)
        assertEquals(1, result.conflicts.size)
        val skipped = result.withResolutions(mapOf(incoming[0].fileId to ConflictAction.SKIP))
        assertEquals(1, skipped.alreadyPresent.size)
        val replaced = result.withResolutions(mapOf(incoming[0].fileId to ConflictAction.REPLACE))
        assertEquals(1, replaced.needsTransfer.size)
        assertEquals(100L, result.neededBytes)
    }

    @Test
    fun newFileNeedsTransfer() {
        val incoming = listOf(sampleFile("video.mp4", 500, "ffff"))
        val result = ComparisonEngine.compare(incoming, emptyList())
        assertEquals(1, result.needsTransfer.size)
        assertEquals(500L, result.neededBytes)
    }
}

class UniquifiedNameTest {
    @Test
    fun uniquifiedNameIsRecorded() {
        val taken = mutableSetOf("photo.jpg")
        val unique = FilenameConflict.uniqueName("photo.jpg") { taken.contains(it) }
        assertEquals("photo (1).jpg", unique)
        taken += unique
        assertEquals("photo (2).jpg", FilenameConflict.uniqueName("photo.jpg") { taken.contains(it) })
    }
}

class StreamChecksumTest {
    @Test
    fun streamHashMatchesInMemory() {
        val payload = "honor-share-package".repeat(1000).toByteArray()
        assertEquals(Checksums.sha256Hex(payload), Checksums.sha256Hex(ByteArrayInputStream(payload)))
    }
}

private fun sampleFile(name: String, size: Long, hash: String?) = PackageFile(
    fileId = UUID.randomUUID().toString(),
    name = name,
    relativePath = name,
    size = size,
    mimeType = "application/octet-stream",
    modifiedAt = 1L,
    hash = hash,
)
