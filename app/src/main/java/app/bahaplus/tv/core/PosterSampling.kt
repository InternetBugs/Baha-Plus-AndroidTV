package app.bahaplus.tv.core

/** A power-of-two decoder sample that keeps both bitmap sides within the budget. */
internal fun posterSampleSize(width: Int, height: Int, maxSide: Int): Int {
    require(width in 1..16384 && height in 1..16384 && maxSide in 1..16384)
    var sample = 1
    while ((width + sample - 1) / sample > maxSide || (height + sample - 1) / sample > maxSide) {
        sample *= 2
    }
    return sample
}
