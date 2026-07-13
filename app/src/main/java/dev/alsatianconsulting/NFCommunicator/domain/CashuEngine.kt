package dev.alsatianconsulting.NFCommunicator.domain

import java.math.BigInteger
import java.security.MessageDigest

data class BlindedMessage(
    val amount: Long,
    val id: String,
    val B_: String
)

data class BlindedSignature(
    val amount: Long,
    val id: String,
    val C_: String
)

data class CashuProof(
    val amount: Long,
    val id: String,
    val secret: String,
    val C: String
)

object Secp256k1Math {
    val p = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    val a = BigInteger.ZERO
    val b = BigInteger.valueOf(7)
    
    // Curve order N
    val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    
    // G generator coordinates
    val Gx = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    val Gy = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    
    data class Point(val x: BigInteger, val y: BigInteger) {
        val isInfinity: Boolean get() = this == INFINITY
        
        fun toHex(): String {
            if (this == INFINITY) return "00"
            val prefix = if (this.y.testBit(0)) "03" else "02"
            val xHex = this.x.toString(16).padStart(64, '0')
            return prefix + xHex
        }
    }
    
    val INFINITY = Point(BigInteger.ZERO, BigInteger.ZERO)
    val G = Point(Gx, Gy)
    
    fun add(P: Point, Q: Point): Point {
        if (P.isInfinity) return Q
        if (Q.isInfinity) return P
        if (P.x == Q.x) {
            if ((P.y + Q.y).mod(p) == BigInteger.ZERO) return INFINITY
            return double(P)
        }
        
        val num = Q.y.subtract(P.y).mod(p)
        val den = Q.x.subtract(P.x).mod(p)
        val invDen = den.modInverse(p)
        val lambda = num.multiply(invDen).mod(p)
        val rx = lambda.multiply(lambda).subtract(P.x).subtract(Q.x).mod(p)
        val ry = lambda.multiply(P.x.subtract(rx)).subtract(P.y).mod(p)
        return Point(rx, ry)
    }
    
    fun double(P: Point): Point {
        if (P.isInfinity) return INFINITY
        val num = BigInteger.valueOf(3).multiply(P.x).multiply(P.x).mod(p)
        val den = BigInteger.valueOf(2).multiply(P.y).mod(p)
        if (den == BigInteger.ZERO) return INFINITY
        val invDen = den.modInverse(p)
        val lambda = num.multiply(invDen).mod(p)
        val rx = lambda.multiply(lambda).subtract(P.x.multiply(BigInteger.valueOf(2))).mod(p)
        val ry = lambda.multiply(P.x.subtract(rx)).subtract(P.y).mod(p)
        return Point(rx, ry)
    }
    
    fun multiply(P: Point, k: BigInteger): Point {
        var tempK = k.mod(N)
        var current = P
        var result = INFINITY
        while (tempK > BigInteger.ZERO) {
            if (tempK.testBit(0)) {
                result = add(result, current)
            }
            current = double(current)
            tempK = tempK.shiftRight(1)
        }
        return result
    }
    
    fun negate(P: Point): Point {
        if (P.isInfinity) return INFINITY
        return Point(P.x, p.subtract(P.y).mod(p))
    }
    
    fun parsePoint(hex: String): Point {
        if (hex == "00") return INFINITY
        val clean = hex.trim()
        val prefix = clean.substring(0, 2)
        val xVal = BigInteger(clean.substring(2), 16)
        
        val x3 = xVal.multiply(xVal).multiply(xVal).add(BigInteger.valueOf(7)).mod(p)
        val pPlus1Div4 = p.add(BigInteger.ONE).divide(BigInteger.valueOf(4))
        var yVal = x3.modPow(pPlus1Div4, p)
        
        val isOddPrefix = prefix == "03"
        val isOddY = yVal.testBit(0)
        if (isOddPrefix != isOddY) {
            yVal = p.subtract(yVal)
        }
        
        require(yVal.multiply(yVal).mod(p) == x3) { "Invalid secp256k1 point" }
        return Point(xVal, yVal)
    }
}

object CashuEngine {
    
    fun hashToCurve(secret: ByteArray): Secp256k1Math.Point {
        val md = MessageDigest.getInstance("SHA-256")
        val domainSeparator = "Secp256k1_HashToCurve_Cashu_".toByteArray(Charsets.UTF_8)
        md.update(domainSeparator)
        md.update(secret)
        val msgHash = md.digest()
        
        var counter = 0
        while (true) {
            md.reset()
            md.update(msgHash)
            val counterBytes = byteArrayOf(
                (counter and 0xFF).toByte(),
                ((counter shr 8) and 0xFF).toByte(),
                ((counter shr 16) and 0xFF).toByte(),
                ((counter shr 24) and 0xFF).toByte()
            )
            md.update(counterBytes)
            val xBytes = md.digest()
            val x = BigInteger(1, xBytes)
            
            if (x < Secp256k1Math.p) {
                val x3 = x.multiply(x).multiply(x).add(BigInteger.valueOf(7)).mod(Secp256k1Math.p)
                val pPlus1Div4 = Secp256k1Math.p.add(BigInteger.ONE).divide(BigInteger.valueOf(4))
                val y = x3.modPow(pPlus1Div4, Secp256k1Math.p)
                if (y.multiply(y).mod(Secp256k1Math.p) == x3) {
                    val evenY = if (y.testBit(0)) Secp256k1Math.p.subtract(y) else y
                    return Secp256k1Math.Point(x, evenY)
                }
            }
            counter++
        }
    }
    
    fun blind(secret: String, r: BigInteger): Secp256k1Math.Point {
        val Y = hashToCurve(secret.toByteArray(Charsets.UTF_8))
        val T = Secp256k1Math.multiply(Secp256k1Math.G, r)
        return Secp256k1Math.add(Y, T)
    }
    
    fun unblind(C_prime: Secp256k1Math.Point, r: BigInteger, K: Secp256k1Math.Point): Secp256k1Math.Point {
        val rK = Secp256k1Math.multiply(K, r)
        val neg_rK = Secp256k1Math.negate(rK)
        return Secp256k1Math.add(C_prime, neg_rK)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun deriveSecretAndR(
        seed: ByteArray,
        keysetId: String,
        counter: Long
    ): Pair<String, BigInteger> {
        val domain = "Cashu_KDF_HMAC_SHA256".toByteArray(Charsets.UTF_8)
        val keysetBytes = try {
            val cleanHex = keysetId.trim()
            val result = ByteArray(cleanHex.length / 2)
            for (i in 0 until cleanHex.length step 2) {
                result[i / 2] = cleanHex.substring(i, i + 2).toInt(16).toByte()
            }
            result
        } catch (e: Exception) {
            ByteArray(0)
        }

        val counterBytes = java.nio.ByteBuffer.allocate(8).putLong(counter).array()

        // message = domain || keysetBytes || counterBytes
        val message = domain + keysetBytes + counterBytes

        val secretDerivation = message + byteArrayOf(0)
        val rDerivation = message + byteArrayOf(1)

        val secretDigest = hmacSha256(seed, secretDerivation)
        val rDigest = hmacSha256(seed, rDerivation)

        val rVal = BigInteger(1, rDigest).mod(Secp256k1Math.N)
        if (rVal == BigInteger.ZERO) {
            throw Exception("Derived invalid blinding scalar r == 0")
        }

        return Pair(secretDigest.toHex(), rVal)
    }

    fun deriveYPointHex(secret: String): String {
        val yPoint = hashToCurve(secret.toByteArray(Charsets.UTF_8))
        return yPoint.toHex()
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
