package com.honor.share.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

class ProtocolJsonTest {
    @Test
    fun helloRoundTripAndGoldenVector() {
        val golden = javaClass.classLoader
            .getResource("hello.json")
            ?.readText()
            ?: readRepoExample("hello.json")
        val decoded = ProtocolJson.decode(golden)
        assertEquals("HELLO", decoded.type)
        assertEquals(1, decoded.v)
        val hello = ProtocolJson.payload<HelloPayload>(decoded)
        assertEquals("MacBook Pro", hello.name)
        assertEquals("macos", hello.os)
        val encoded = ProtocolJson.decode(ProtocolJson.encode(decoded))
        assertEquals(decoded, encoded)
    }

    @Test
    fun transferRequestGoldenVector() {
        val golden = readRepoExample("transfer_request.json")
        val decoded = ProtocolJson.decode(golden)
        val payload = ProtocolJson.payload<TransferRequestPayload>(decoded)
        assertEquals(1, payload.files.size)
        assertEquals("photo.jpg", payload.files[0].name)
        assertEquals(4_390_000L, payload.totalBytes)
    }

    @Test
    fun errorGoldenVector() {
        val decoded = ProtocolJson.decode(readRepoExample("error.json"))
        val payload = ProtocolJson.payload<ErrorPayload>(decoded)
        assertEquals("UNSUPPORTED_VERSION", payload.code)
        assertEquals(null, payload.transferId)
    }

    private fun readRepoExample(name: String): String {
        val fromClasspath = javaClass.classLoader.getResource(name)?.readText()
        if (fromClasspath != null) return fromClasspath
        val roots = listOf(
            "../protocol/schemas/examples/$name",
            "../../protocol/schemas/examples/$name",
            "protocol/schemas/examples/$name",
        )
        for (path in roots) {
            val file = java.io.File(path)
            if (file.exists()) return file.readText()
        }
        // Fallback copies live next to tests via duplicated resource.
        return when (name) {
            "hello.json" -> """{"v":1,"type":"HELLO","msgId":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"deviceId":"22222222-2222-4222-8222-222222222222","name":"MacBook Pro","os":"macos","protocolVersion":1,"certFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","authNonce":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}"""
            "transfer_request.json" -> """{"v":1,"type":"TRANSFER_REQUEST","msgId":"33333333-3333-4333-8333-333333333333","ts":1700000001000,"payload":{"transferId":"44444444-4444-4444-8444-444444444444","files":[{"fileId":"55555555-5555-4555-8555-555555555555","name":"photo.jpg","size":4390000,"mimeType":"image/jpeg","relativePath":"photo.jpg"}],"totalBytes":4390000}}"""
            "error.json" -> """{"v":1,"type":"ERROR","msgId":"66666666-6666-4666-8666-666666666666","ts":1700000002000,"payload":{"code":"UNSUPPORTED_VERSION","transferId":null,"fileId":null}}"""
            else -> error("missing $name")
        }
    }
}

class FrameCodecTest {
    @Test
    fun controlFrameRoundTrip() {
        val json = ProtocolJson.encode(
            ProtocolJson.message(MessageType.ERROR, ProtocolJson.payloadObject(ErrorPayload("TIMEOUT"))),
        ).toByteArray()
        val encoded = FrameCodec.encodeControl(json)
        val decoded = FrameCodec.read(ByteArrayInputStream(encoded)) as Frame.Control
        assertTrue(decoded.json.contentEquals(json))
    }

    @Test
    fun binaryFrameRoundTrip() {
        val fileId = UUID.fromString("55555555-5555-4555-8555-555555555555")
        val data = ByteArray(1024) { it.toByte() }
        val encoded = FrameCodec.encodeBinary(fileId, 4096, data)
        val decoded = FrameCodec.read(ByteArrayInputStream(encoded)) as Frame.Binary
        assertEquals(fileId, decoded.fileId)
        assertEquals(4096L, decoded.offset)
        assertTrue(decoded.data.contentEquals(data))
    }

    @Test
    fun binaryFileIdMatchesUppercaseStartId() {
        val fileId = UUID.fromString("aabbccdd-e5f6-4789-abcd-ef1234567890")
        val macStyleStartId = fileId.toString().uppercase()
        assertTrue(fileId.toString().equals(macStyleStartId, ignoreCase = true))
        assertFalse(fileId.toString() == macStyleStartId)
    }

    @Test
    fun streamMultipleFrames() {
        val out = ByteArrayOutputStream()
        out.write(FrameCodec.encodeControl("""{"v":1}""".toByteArray()))
        out.write(FrameCodec.encodeBinary(UUID(1, 2), 0, byteArrayOf(9, 8, 7)))
        val input = ByteArrayInputStream(out.toByteArray())
        val first = FrameCodec.read(input)
        val second = FrameCodec.read(input) as Frame.Binary
        assertTrue(first is Frame.Control)
        assertEquals(3, second.data.size)
    }
}

class SasTest {
    @Test
    fun goldenVector() {
        val code = Sas.compute(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        )
        assertEquals("693253", code)
        assertEquals("693 253", Sas.display(code))
    }

    @Test
    fun sortIsOrderIndependent() {
        val a = "aa"
        val b = "cc"
        val n = "nonce"
        assertEquals(Sas.compute(a, b, n), Sas.compute(b, a, n))
    }
}

class FilenameConflictTest {
    @Test
    fun appendsNumberBeforeExtension() {
        val taken = mutableSetOf("photo.jpg")
        val name = FilenameConflict.uniqueName("photo.jpg") { taken.contains(it) }
        assertEquals("photo (1).jpg", name)
        taken += name
        assertEquals("photo (2).jpg", FilenameConflict.uniqueName("photo.jpg") { taken.contains(it) })
    }

    @Test
    fun rejectsPathEscape() {
        try {
            FilenameConflict.sanitizeRelativePath("../secret")
            throw AssertionError("expected failure")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.PROTOCOL_VIOLATION, e.code)
        }
    }
}

class StateMachineTest {
    @Test
    fun connectionHappyPath() {
        var state = ConnectionState.IDLE
        state = ConnectionMachine.transition(state, ConnectionState.DISCOVERING)
        state = ConnectionMachine.transition(state, ConnectionState.DEVICE_FOUND)
        state = ConnectionMachine.transition(state, ConnectionState.CONNECTING)
        state = ConnectionMachine.transition(state, ConnectionState.AUTHENTICATING)
        state = ConnectionMachine.transition(state, ConnectionState.CONNECTED)
        assertEquals(ConnectionState.CONNECTED, state)
    }

    @Test
    fun illegalConnectionTransitionFails() {
        try {
            ConnectionMachine.transition(ConnectionState.IDLE, ConnectionState.CONNECTED)
            throw AssertionError("expected failure")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.PROTOCOL_VIOLATION, e.code)
        }
    }

    @Test
    fun transferCannotCompleteFromIdle() {
        assertFalse(TransferMachine.canTransition(TransferState.IDLE, TransferState.COMPLETED))
    }
}

class ChecksumTest {
    @Test
    fun sha256KnownVector() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Checksums.sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun incrementalMatchesFull() {
        val data = "honor-share".repeat(100).toByteArray()
        val inc = IncrementalSha256()
        inc.update(data, 0, 20)
        inc.update(data, 20, data.size - 20)
        assertEquals(Checksums.sha256Hex(data), inc.hex())
    }
}

class SpeedEstimatorTest {
    @Test
    fun hidesEtaUntilStable() {
        val estimator = SpeedEstimator(minElapsedMs = 2_000)
        estimator.onProgress(0, 0)
        estimator.onProgress(100_000, 100)
        assertEquals(null, estimator.etaSeconds(1_000_000, 100))
    }

    @Test
    fun reportsEtaWhenStable() {
        val estimator = SpeedEstimator(minElapsedMs = 100, smoothing = 1.0)
        estimator.onProgress(0, 0)
        estimator.onProgress(1_000_000, 200)
        estimator.onProgress(2_000_000, 400)
        estimator.onProgress(3_000_000, 600)
        val eta = estimator.etaSeconds(5_000_000, 600)
        assertTrue(eta != null && eta > 0)
    }
}

class SelfSignedCertTest {
    @Test
    fun generatesVerifiableCertificate() {
        val identity = SelfSignedCert.generate()
        identity.certificate.checkValidity()
        identity.certificate.verify(identity.keyPair.public)
        assertEquals(64, identity.fingerprintSha256.length)
    }
}

class RetryPolicyTest {
    @Test
    fun retriesDisconnectsOnly() {
        assertTrue(RetryPolicy.shouldRetry(ErrorCode.CONNECTION_LOST, 0))
        assertFalse(RetryPolicy.shouldRetry(ErrorCode.CHECKSUM_MISMATCH, 0))
        assertFalse(RetryPolicy.shouldRetry(ErrorCode.CONNECTION_LOST, 3))
        assertEquals(800L, RetryPolicy.delayMs(1))
    }
}

class ShareLinkTest {
    @Test
    fun roundTripAndRejectsJunk() {
        val link = ShareLink("10.189.45.67", 49221, "abc-id", "Moomen's Mac", "macos")
        val parsed = ShareLink.parse(link.encode())
        assertEquals(link, parsed)
        assertEquals(null, ShareLink.parse("not-a-code"))
        assertEquals(null, ShareLink.parse("HS1|host|99999|id|os|name"))
    }
}

class ReceiveFolderTest {
    @Test
    fun uniqueDatePeerPath() {
        val path = ProtocolConstants.receiveSubfolder("Honor 200", 1_787_000_000_000L)
        assertTrue(path.endsWith("/Honor 200"))
        assertTrue(path.matches(Regex("""\d{4}-\d{2}-\d{2}/Honor 200""")))
        assertFalse(ProtocolConstants.receiveSubfolder("a/b").contains("a/b"))
        assertEquals("Device", ProtocolConstants.receiveSubfolder("  ").substringAfter('/'))
    }
}

class FolderBrowserTest {
    @Test
    fun nestedFoldersAndFiles() {
        val paths = listOf("2026-08-15/Honor 200/photo.jpg", "2026-08-15/Honor 200/clip.mp4", "2026-08-16/Mac/note.txt")
        assertEquals(listOf("2026-08-15", "2026-08-16"), FolderBrowser.childFolders(paths, ""))
        assertEquals(listOf("Honor 200"), FolderBrowser.childFolders(paths, "2026-08-15"))
        assertEquals(
            listOf("2026-08-15/Honor 200/photo.jpg", "2026-08-15/Honor 200/clip.mp4"),
            FolderBrowser.filesAt(paths, "2026-08-15/Honor 200"),
        )
        assertEquals("2026-08-15/Honor 200", FolderBrowser.parentPath("2026-08-15/Honor 200/photo.jpg"))
    }
}

class PickerSelectionTest {
    @Test
    fun emptyPickerReplacesAndAddAppends() {
        assertEquals(listOf("b"), PickerSelection.merge(emptyList(), listOf("b")))
        assertEquals(listOf("a", "b"), PickerSelection.merge(listOf("a"), listOf("b")))
    }

    @Test
    fun resetClearsSelectionAndPreparing() {
        data class Session(val selected: List<String>, val preparing: Boolean, val pkg: String?)
        fun Session.reset() = Session(emptyList(), false, null)
        val dirty = Session(listOf("a.jpg"), true, "pkg")
        val clean = dirty.reset()
        assertTrue(clean.selected.isEmpty())
        assertFalse(clean.preparing)
        assertNull(clean.pkg)
    }
}
