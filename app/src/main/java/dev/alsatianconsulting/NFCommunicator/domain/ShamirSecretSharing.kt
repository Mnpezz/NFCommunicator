package dev.alsatianconsulting.NFCommunicator.domain

import java.security.SecureRandom

object ShamirSecretSharing {
    
    private val exp = ByteArray(512)
    private val log = ByteArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x.toByte()
            log[x] = i.toByte()
            x = x shl 1
            if (x and 0x100 != 0) {
                x = x xor 0x11d
            }
        }
        for (i in 255 until 512) {
            exp[i] = exp[i - 255]
        }
    }

    private fun add(a: Byte, b: Byte): Byte = (a.toInt() xor b.toInt()).toByte()
    
    private fun mul(a: Byte, b: Byte): Byte {
        val ia = a.toInt() and 0xff
        val ib = b.toInt() and 0xff
        if (ia == 0 || ib == 0) return 0
        return exp[(log[ia].toInt() and 0xff) + (log[ib].toInt() and 0xff)]
    }

    private fun div(a: Byte, b: Byte): Byte {
        val ia = a.toInt() and 0xff
        val ib = b.toInt() and 0xff
        if (ib == 0) throw ArithmeticException("Division by zero in GF(256)")
        if (ia == 0) return 0
        var diff = (log[ia].toInt() and 0xff) - (log[ib].toInt() and 0xff)
        if (diff < 0) diff += 255
        return exp[diff]
    }

    /**
     * Splits secret bytes into N shares, requiring K shares to reconstruct.
     * Each share bytes will consist of: [x_coordinate (1 byte)] + [share_payload (same length as secret)]
     */
    fun split(secret: ByteArray, n: Int, k: Int): List<ByteArray> {
        require(k in 2..n) { "Threshold K must be between 2 and N" }
        require(n in 2..255) { "N must be between 2 and 255" }
        
        val random = SecureRandom()
        val shares = List(n) { ByteArray(secret.size + 1) }
        
        // Assign x coordinates (1 to n)
        for (j in 0 until n) {
            shares[j][0] = (j + 1).toByte()
        }

        // For each byte of the secret
        for (i in secret.indices) {
            // Construct a random polynomial of degree K-1
            // P(x) = a_0 + a_1*x + a_2*x^2 + ... + a_{K-1}*x^{K-1}
            // where a_0 is the secret byte.
            val poly = ByteArray(k)
            poly[0] = secret[i]
            for (coeff in 1 until k) {
                poly[coeff] = random.nextInt(256).toByte()
            }

            // Evaluate P(x) for each share coordinate x_j = j + 1
            for (j in 0 until n) {
                val xVal = (j + 1).toByte()
                var yVal = 0.toByte()
                var xPower = 1.toByte() // x^0
                
                for (coeff in 0 until k) {
                    val term = mul(poly[coeff], xPower)
                    yVal = add(yVal, term)
                    xPower = mul(xPower, xVal)
                }
                
                shares[j][i + 1] = yVal
            }
        }
        
        return shares
    }

    /**
     * Reconstructs the secret bytes from a list of gathered shares.
     * Each share has: [x_coordinate (1 byte)] + [share_payload]
     */
    fun reconstruct(shares: List<ByteArray>): ByteArray {
        require(shares.isNotEmpty()) { "No shares provided for reconstruction" }
        val secretSize = shares[0].size - 1
        require(secretSize > 0) { "Invalid share size" }
        
        // Extract x coordinates and payloads, ensuring no duplicates
        val points = shares.map { share ->
            val x = share[0].toInt() and 0xff
            val yBytes = ByteArray(secretSize)
            System.arraycopy(share, 1, yBytes, 0, secretSize)
            x to yBytes
        }.distinctBy { it.first }

        val k = points.size
        val secret = ByteArray(secretSize)

        for (i in 0 until secretSize) {
            var secretByte = 0.toByte()
            for (j in 0 until k) {
                val xj = points[j].first.toByte()
                val yj = points[j].second[i]

                // Calculate Lagrange coefficient l_j(0) = Prod_{m != j} (xm / (xj ^ xm))
                var lj = 1.toByte()
                for (m in 0 until k) {
                    if (m != j) {
                        val xm = points[m].first.toByte()
                        val num = xm
                        val den = add(xj, xm)
                        val term = div(num, den)
                        lj = mul(lj, term)
                    }
                }
                
                val term = mul(yj, lj)
                secretByte = add(secretByte, term)
            }
            secret[i] = secretByte
        }

        return secret
    }
}
