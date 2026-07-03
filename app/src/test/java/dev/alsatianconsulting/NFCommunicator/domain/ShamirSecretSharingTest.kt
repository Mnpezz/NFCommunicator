package dev.alsatianconsulting.NFCommunicator.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.SecureRandom

class ShamirSecretSharingTest {

    @Test
    fun testSplitAndReconstructExact() {
        val secret = "correct horse battery staple".toByteArray()
        // Split into 3 shares, requiring 2 to reconstruct
        val shares = ShamirSecretSharing.split(secret, 3, 2)
        assertEquals(3, shares.size)
        for (share in shares) {
            assertEquals(secret.size + 1, share.size)
        }

        // Test all pairs of shares
        val pair12 = listOf(shares[0], shares[1])
        val pair23 = listOf(shares[1], shares[2])
        val pair13 = listOf(shares[0], shares[2])

        assertArrayEquals(secret, ShamirSecretSharing.reconstruct(pair12))
        assertArrayEquals(secret, ShamirSecretSharing.reconstruct(pair23))
        assertArrayEquals(secret, ShamirSecretSharing.reconstruct(pair13))
    }

    @Test
    fun testSplitAndReconstructWith24WordsSize() {
        // 24-word seed phrases usually have 32 bytes of entropy
        val secret = ByteArray(32)
        SecureRandom().nextBytes(secret)

        val shares = ShamirSecretSharing.split(secret, 5, 3)
        assertEquals(5, shares.size)

        // Try 3 shares
        val combo1 = listOf(shares[0], shares[2], shares[4])
        assertArrayEquals(secret, ShamirSecretSharing.reconstruct(combo1))

        // Try another 3 shares
        val combo2 = listOf(shares[1], shares[2], shares[3])
        assertArrayEquals(secret, ShamirSecretSharing.reconstruct(combo2))
    }

    @Test
    fun testReconstructFailsWithInsufficientShares() {
        val secret = "highly secret information".toByteArray()
        val shares = ShamirSecretSharing.split(secret, 3, 3) // 3-of-3

        // Reconstructing with 2 shares should NOT match the original secret
        val incomplete = listOf(shares[0], shares[1])
        val reconstructed = ShamirSecretSharing.reconstruct(incomplete)
        
        // Reassembled with K-1 shares must be completely randomized/incorrect
        var matchedCount = 0
        for (i in secret.indices) {
            if (secret[i] == reconstructed[i]) {
                matchedCount++
            }
        }
        // It's extremely statistically unlikely to match more than a few bytes at random
        assertNotEquals(secret.size, matchedCount)
    }
}
