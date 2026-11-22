package com.ftapps.remft.domain.alarm

object NotificationIds {
    const val TEST_ALARM = 1
    const val FIRST_RING = 0x0010_0000
    const val MATCHED_ATTEMPTS = 4

    private const val RING_SPAN = Int.MAX_VALUE.toLong() - FIRST_RING + 1
    private const val FNV64_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV64_PRIME = 0x100000001b3L

    fun isRing(id: Int): Boolean = id >= FIRST_RING

    fun ring(key: String, attempt: Int = 0): Int {
        require(attempt >= 0)
        val hash = fnv64("$key#$attempt")
        return (FIRST_RING + java.lang.Long.remainderUnsigned(hash, RING_SPAN)).toInt()
    }

    fun matchedRings(key: String): List<Int> = (0 until MATCHED_ATTEMPTS).map { ring(key, it) }

    fun assign(keys: List<String>): List<Int> {
        val taken = HashSet<Int>(keys.size * 2)
        return keys.map { key ->
            var attempt = 0
            var id = ring(key, attempt)
            while (!taken.add(id)) id = ring(key, ++attempt)
            id
        }
    }

    private fun fnv64(text: String): Long {
        var hash = FNV64_OFFSET
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= FNV64_PRIME
        }
        return hash
    }
}
