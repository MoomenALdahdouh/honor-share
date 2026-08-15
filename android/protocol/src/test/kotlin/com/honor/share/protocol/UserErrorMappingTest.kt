package com.honor.share.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class UserErrorMappingTest {
    @Test
    fun checksumIsNotRawException() {
        val error = ProtocolException(ErrorCode.CHECKSUM_MISMATCH, "java.security.digest")
        assertEquals(ErrorCode.CHECKSUM_MISMATCH, error.code)
    }
}
