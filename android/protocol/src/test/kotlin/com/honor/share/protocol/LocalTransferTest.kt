package com.honor.share.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocalTransferTest {
    @Test
    fun handshakeAndSmallFileOverLoopback() {
        val serverIdentity = SelfSignedCert.generate()
        val clientIdentity = SelfSignedCert.generate()
        val serverProfile = LocalProfile("server-id", "MacBook Pro", "macos", serverIdentity)
        val clientProfile = LocalProfile("client-id", "HONOR Phone", "android", clientIdentity)
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = listener.localPort
        val pool = Executors.newFixedThreadPool(2)
        try {
            val serverFuture = pool.submit(Callable {
                listener.accept().use { accepted ->
                    val session = ProtocolSession(accepted.getInputStream(), accepted.getOutputStream())
                    val peer = Handshake(
                        session = session,
                        local = serverProfile,
                        capturedFingerprint = clientIdentity.fingerprintSha256,
                        knownPin = { null },
                        confirmSas = { _, _ -> true },
                    ).runAsServer()
                    assertEquals("client-id", peer.deviceId)
                    val dest = kotlin.io.path.createTempDirectory("honor-recv").toFile()
                    val received = FileTransfer(session).receive(
                        sinkFactory = DirectorySinkFactory(dest),
                        accept = { ReceiveDecision(true) },
                        onProgress = {},
                    )
                    dest to received
                }
            })
            val clientFuture = pool.submit(Callable {
                Socket("127.0.0.1", port).use { socket ->
                    val session = ProtocolSession(socket.getInputStream(), socket.getOutputStream())
                    Handshake(
                        session = session,
                        local = clientProfile,
                        capturedFingerprint = serverIdentity.fingerprintSha256,
                        knownPin = { null },
                        confirmSas = { _, _ -> true },
                    ).runAsClient()
                    val payload = "hello from honor share".toByteArray()
                    val file = File.createTempFile("honor", ".txt")
                    file.writeBytes(payload)
                    val meta = FileMeta(
                        fileId = UUID.randomUUID().toString(),
                        name = "note.txt",
                        size = payload.size.toLong(),
                        mimeType = "text/plain",
                    )
                    val request = TransferRequestPayload(
                        transferId = UUID.randomUUID().toString(),
                        files = listOf(meta),
                        totalBytes = meta.size,
                    )
                    FileTransfer(session).send(
                        request,
                        listOf(OutgoingFile(meta) { file.inputStream() }),
                    ) {}
                    file
                }
            })
            val (dest, received) = serverFuture.get(20, TimeUnit.SECONDS)
            clientFuture.get(20, TimeUnit.SECONDS)
            val out = File(dest, "note.txt")
            assertTrue(out.exists())
            assertEquals("hello from honor share", out.readText())
            assertEquals(1, received.files.size)
        } finally {
            listener.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun skippedFileIsNotRewritten() {
        val serverIdentity = SelfSignedCert.generate()
        val clientIdentity = SelfSignedCert.generate()
        val serverProfile = LocalProfile("server-id", "MacBook Pro", "macos", serverIdentity)
        val clientProfile = LocalProfile("client-id", "HONOR Phone", "android", clientIdentity)
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = listener.localPort
        val pool = Executors.newFixedThreadPool(2)
        try {
            val dest = kotlin.io.path.createTempDirectory("honor-skip").toFile()
            File(dest, "keep.txt").writeText("already here")
            val keepHash = Checksums.sha256Hex("already here".toByteArray())
            val serverFuture = pool.submit(Callable {
                listener.accept().use { accepted ->
                    val session = ProtocolSession(accepted.getInputStream(), accepted.getOutputStream())
                    Handshake(
                        session = session,
                        local = serverProfile,
                        capturedFingerprint = clientIdentity.fingerprintSha256,
                        knownPin = { null },
                        confirmSas = { _, _ -> true },
                    ).runAsServer()
                    FileTransfer(session).receive(
                        sinkFactory = DirectorySinkFactory(dest),
                        accept = { request ->
                            val incoming = request.files.map { meta ->
                                PackageFile(meta.fileId, meta.name, meta.relativePath, meta.size, meta.mimeType, meta.modifiedAt, meta.sha256)
                            }
                            val comparison = ComparisonEngine.compare(
                                incoming,
                                listOf(DestinationFile("keep.txt", 12, keepHash)),
                            )
                            ReceiveDecision(true, comparison.skipFileIds, comparison.neededBytes)
                        },
                        onProgress = {},
                    )
                }
            })
            val clientFuture = pool.submit(Callable {
                Socket("127.0.0.1", port).use { socket ->
                    val session = ProtocolSession(socket.getInputStream(), socket.getOutputStream())
                    Handshake(
                        session = session,
                        local = clientProfile,
                        capturedFingerprint = serverIdentity.fingerprintSha256,
                        knownPin = { null },
                        confirmSas = { _, _ -> true },
                    ).runAsClient()
                    val keep = File.createTempFile("keep", ".txt").apply { writeText("already here") }
                    val extra = File.createTempFile("extra", ".txt").apply { writeText("new file") }
                    val keepMeta = FileMeta(UUID.randomUUID().toString(), "keep.txt", keep.length(), "text/plain", "keep.txt", keepHash)
                    val extraMeta = FileMeta(UUID.randomUUID().toString(), "extra.txt", extra.length(), "text/plain", "extra.txt", Checksums.sha256Hex(extra.readBytes()))
                    FileTransfer(session).send(
                        TransferRequestPayload(UUID.randomUUID().toString(), listOf(keepMeta, extraMeta), keepMeta.size + extraMeta.size),
                        listOf(
                            OutgoingFile(keepMeta) { keep.inputStream() },
                            OutgoingFile(extraMeta) { extra.inputStream() },
                        ),
                    ) {}
                }
            })
            serverFuture.get(20, TimeUnit.SECONDS)
            clientFuture.get(20, TimeUnit.SECONDS)
            assertEquals("already here", File(dest, "keep.txt").readText())
            assertTrue(File(dest, "extra.txt").exists())
            assertEquals("new file", File(dest, "extra.txt").readText())
        } finally {
            listener.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun duplicateFilenameGetsSuffix() {
        val dir = kotlin.io.path.createTempDirectory("honor-dup").toFile()
        File(dir, "photo.jpg").writeText("a")
        val name = FilenameConflict.uniqueName("photo.jpg") { File(dir, it).exists() }
        assertEquals("photo (1).jpg", name)
    }

    @Test
    fun handshakeWhenOnlyClientHasPinFallsBackToSas() {
        runHandshake(
            clientPin = { id -> if (id == "server-id") itServerFingerprint else null },
            serverPin = { null },
        )
    }

    @Test
    fun handshakeWhenOnlyServerHasPinFallsBackToSas() {
        runHandshake(
            clientPin = { null },
            serverPin = { id -> if (id == "client-id") itClientFingerprint else null },
        )
    }

    @Test
    fun trustedServerDoesNotLeaveAuthResponseBeforeTransfer() {
        val serverIdentity = SelfSignedCert.generate()
        val clientIdentity = SelfSignedCert.generate()
        val serverProfile = LocalProfile("server-id", "MacBook Pro", "macos", serverIdentity)
        val clientProfile = LocalProfile("client-id", "HONOR Phone", "android", clientIdentity)
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = listener.localPort
        val pool = Executors.newFixedThreadPool(2)
        try {
            val serverFuture = pool.submit(Callable {
                listener.accept().use { accepted ->
                    val session = ProtocolSession(accepted.getInputStream(), accepted.getOutputStream())
                    Handshake(
                        session = session,
                        local = serverProfile,
                        capturedFingerprint = clientIdentity.fingerprintSha256,
                        knownPin = { id -> if (id == "client-id") clientIdentity.fingerprintSha256 else null },
                        confirmSas = { _, _ -> true },
                    ).runAsServer()
                    val payload = "asymmetric send".toByteArray()
                    val file = File.createTempFile("honor", ".txt")
                    file.writeBytes(payload)
                    val meta = FileMeta(
                        fileId = UUID.randomUUID().toString().uppercase(),
                        name = "note.txt",
                        size = payload.size.toLong(),
                        mimeType = "text/plain",
                    )
                    val request = TransferRequestPayload(
                        transferId = UUID.randomUUID().toString(),
                        files = listOf(meta),
                        totalBytes = meta.size,
                    )
                    FileTransfer(session).send(
                        request,
                        listOf(OutgoingFile(meta) { file.inputStream() }),
                    ) {}
                    file
                }
            })
            val clientFuture = pool.submit(Callable {
                Socket("127.0.0.1", port).use { socket ->
                    val session = ProtocolSession(socket.getInputStream(), socket.getOutputStream())
                    Handshake(
                        session = session,
                        local = clientProfile,
                        capturedFingerprint = serverIdentity.fingerprintSha256,
                        knownPin = { null },
                        confirmSas = { _, _ -> true },
                    ).runAsClient()
                    val dest = kotlin.io.path.createTempDirectory("honor-asym").toFile()
                    FileTransfer(session).receive(
                        sinkFactory = DirectorySinkFactory(dest),
                        accept = { ReceiveDecision(true) },
                        onProgress = {},
                    )
                    File(dest, "note.txt").readText()
                }
            })
            serverFuture.get(20, TimeUnit.SECONDS)
            assertEquals("asymmetric send", clientFuture.get(20, TimeUnit.SECONDS))
        } finally {
            listener.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun handshakeWhenBothHavePinsSkipsSas() {
        runHandshake(
            clientPin = { id -> if (id == "server-id") itServerFingerprint else null },
            serverPin = { id -> if (id == "client-id") itClientFingerprint else null },
            clientSas = { _, _ -> error("sas should not run") },
            serverSas = { _, _ -> error("sas should not run") },
        )
    }

    private var itServerFingerprint = ""
    private var itClientFingerprint = ""

    private fun runHandshake(
        clientPin: (String) -> String?,
        serverPin: (String) -> String?,
        clientSas: (String, String) -> Boolean = { _, _ -> true },
        serverSas: (String, String) -> Boolean = { _, _ -> true },
    ) {
        val serverIdentity = SelfSignedCert.generate()
        val clientIdentity = SelfSignedCert.generate()
        itServerFingerprint = serverIdentity.fingerprintSha256
        itClientFingerprint = clientIdentity.fingerprintSha256
        val serverProfile = LocalProfile("server-id", "MacBook Pro", "macos", serverIdentity)
        val clientProfile = LocalProfile("client-id", "HONOR Phone", "android", clientIdentity)
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = listener.localPort
        val pool = Executors.newFixedThreadPool(2)
        try {
            val serverFuture = pool.submit(Callable {
                listener.accept().use { accepted ->
                    val session = ProtocolSession(accepted.getInputStream(), accepted.getOutputStream())
                    Handshake(
                        session = session,
                        local = serverProfile,
                        capturedFingerprint = clientIdentity.fingerprintSha256,
                        knownPin = serverPin,
                        confirmSas = serverSas,
                    ).runAsServer()
                }
            })
            val clientFuture = pool.submit(Callable {
                Socket("127.0.0.1", port).use { socket ->
                    val session = ProtocolSession(socket.getInputStream(), socket.getOutputStream())
                    Handshake(
                        session = session,
                        local = clientProfile,
                        capturedFingerprint = serverIdentity.fingerprintSha256,
                        knownPin = clientPin,
                        confirmSas = clientSas,
                    ).runAsClient()
                }
            })
            val serverPeer = serverFuture.get(10, TimeUnit.SECONDS)
            val clientPeer = clientFuture.get(10, TimeUnit.SECONDS)
            assertEquals("client-id", serverPeer.deviceId)
            assertEquals("server-id", clientPeer.deviceId)
        } finally {
            listener.close()
            pool.shutdownNow()
        }
    }
}

private class DirectorySinkFactory(private val dir: File) : ReceiveSinkFactory {
    override fun hasSpace(bytes: Long): Boolean = dir.usableSpace > bytes + 4096

    override fun open(file: FileMeta, offset: Long): ReceiveSink {
        val name = FilenameConflict.uniqueName(file.name) { File(dir, it).exists() }
        val temp = File(dir, ".$name${ProtocolConstants.PARTIAL_SUFFIX}")
        return object : ReceiveSink {
            private val stream = temp.outputStream()
            override var bytesWritten: Long = 0
            override fun write(data: ByteArray) {
                stream.write(data)
                bytesWritten += data.size
            }

            override fun commit(expectedSha256: String) {
                stream.close()
                val finalFile = File(dir, name)
                if (!temp.renameTo(finalFile)) {
                    temp.copyTo(finalFile, overwrite = true)
                    temp.delete()
                }
            }

            override fun abort() {
                stream.close()
                temp.delete()
            }
        }
    }
}
