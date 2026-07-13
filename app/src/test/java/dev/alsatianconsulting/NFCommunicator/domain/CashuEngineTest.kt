package dev.alsatianconsulting.NFCommunicator.domain

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class CashuEngineTest {

    @Test
    fun testHashToCurve_validSecp256k1Point() {
        val secret = "test_secret_message_for_cashu"
        val point = CashuEngine.hashToCurve(secret.toByteArray(Charsets.UTF_8))
        
        assertNotNull(point)
        assertFalse(point.isInfinity)
        
        // y^2 mod p == x^3 + 7 mod p
        val x3 = point.x.multiply(point.x).multiply(point.x).add(BigInteger.valueOf(7)).mod(Secp256k1Math.p)
        val y2 = point.y.multiply(point.y).mod(Secp256k1Math.p)
        assertEquals(x3, y2)
        
        // Cashu NUT-00 requires y coordinate to be even
        assertFalse(point.y.testBit(0))
    }

    @Test
    fun testBlindingAndUnblindingIdentity() {
        val secret = "super_secret_token_12345"
        val r = BigInteger("1234567890123456789012345678901234567890123456789012345678901234")
        
        // 1. Blind
        val blindedPoint = CashuEngine.blind(secret, r)
        assertNotNull(blindedPoint)
        
        // Verify blinded point is on the curve
        val x3_blind = blindedPoint.x.multiply(blindedPoint.x).multiply(blindedPoint.x).add(BigInteger.valueOf(7)).mod(Secp256k1Math.p)
        val y2_blind = blindedPoint.y.multiply(blindedPoint.y).mod(Secp256k1Math.p)
        assertEquals(x3_blind, y2_blind)

        // 2. Mint signs the blinded point: C_ = a * B_
        val mintPrivateKey = BigInteger("987654321098765432109876543210987654321098765432109876543210")
        val C_prime = Secp256k1Math.multiply(blindedPoint, mintPrivateKey)
        
        // Mint's public key corresponding to keyset: K = a * G
        val K = Secp256k1Math.multiply(Secp256k1Math.G, mintPrivateKey)

        // 3. User unblinds signature: C = C_ - r * K
        val unblindedPoint = CashuEngine.unblind(C_prime, r, K)
        
        // 4. Verification: C == a * Y
        val Y = CashuEngine.hashToCurve(secret.toByteArray(Charsets.UTF_8))
        val expectedUnblinded = Secp256k1Math.multiply(Y, mintPrivateKey)

        assertEquals(expectedUnblinded.x, unblindedPoint.x)
        assertEquals(expectedUnblinded.y, unblindedPoint.y)
    }

    @Test
    fun testPointToHexAndParse() {
        val secret = "test_hex_roundtrip"
        val originalPoint = CashuEngine.hashToCurve(secret.toByteArray(Charsets.UTF_8))
        
        val hex = originalPoint.toHex()
        val parsedPoint = Secp256k1Math.parsePoint(hex)
        
        assertEquals(originalPoint.x, parsedPoint.x)
        assertEquals(originalPoint.y, parsedPoint.y)
    }

    @Test
    fun testDeterministicDerivation() {
        val seed = "dummy_seed_phrase_with_at_least_twelve_words_long".toByteArray(Charsets.UTF_8)
        val keysetId = "009a0f5a"
        val counter = 0L
        
        // Derivation 1
        val (secret1, r1) = CashuEngine.deriveSecretAndR(seed, keysetId, counter)
        assertNotNull(secret1)
        assertNotNull(r1)
        
        // Derivation 2 (reproducibility test)
        val (secret2, r2) = CashuEngine.deriveSecretAndR(seed, keysetId, counter)
        assertEquals(secret1, secret2)
        assertEquals(r1, r2)
        
        // Derivation with different counter
        val (secret3, r3) = CashuEngine.deriveSecretAndR(seed, keysetId, counter + 1)
        assertNotEquals(secret1, secret3)
        assertNotEquals(r1, r3)
    }
}
