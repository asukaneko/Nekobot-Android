package app.lianxu.independent

/** Decimal single-image threshold and independent complete-request safety budget. */
internal object ImagePolicy {
    const val ORIGINAL_LIMIT=20_000_000
    const val LEGACY_ASSET_LIMIT=40_000_000
    const val REQUEST_LIMIT=32*1024*1024
    const val MAX_PIXELS=160_000_000L
    const val MAX_SIDE=65535
    const val MAX_IMAGES=64
    val mimes=setOf("image/jpeg","image/png","image/webp")
    fun original(size:Long)=size in 1..ORIGINAL_LIMIT.toLong()
    fun encodedLength(size:Long)=4*((size+2)/3)
}
