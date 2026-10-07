package ai.alert.app.data

internal object GeoHash {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun encode(latitude: Double, longitude: Double, precision: Int = 9): String {
        require(latitude in -90.0..90.0)
        require(longitude in -180.0..180.0)

        var latMin = -90.0
        var latMax = 90.0
        var lonMin = -180.0
        var lonMax = 180.0
        var evenBit = true
        var bit = 0
        var charValue = 0
        val result = StringBuilder(precision)

        while (result.length < precision) {
            val mid = if (evenBit) (lonMin + lonMax) / 2.0
            else (latMin + latMax) / 2.0

            val value = if (evenBit) longitude else latitude
            if (value >= mid) {
                charValue = (charValue shl 1) + 1
                if (evenBit) lonMin = mid else latMin = mid
            } else {
                charValue = charValue shl 1
                if (evenBit) lonMax = mid else latMax = mid
            }

            evenBit = !evenBit
            bit++

            if (bit == 5) {
                result.append(BASE32[charValue])
                bit = 0
                charValue = 0
            }
        }

        return result.toString()
    }
}
