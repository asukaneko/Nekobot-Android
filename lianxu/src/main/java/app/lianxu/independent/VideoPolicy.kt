package app.lianxu.independent

internal object VideoPolicy {
    const val ORIGINAL_LIMIT=70_000_000L
    const val TOO_LARGE="暂不支持超过70 MB的视频"
    const val MAX_FRAMES = 24
    const val FRAME_MAX_LONG_EDGE = 1280
    const val FRAME_MAX_SHORT_EDGE = 720
    const val FRAME_JPEG_QUALITY = 80
    const val FRAME_TOKENS = 1024L

    fun isVideo(mime:String)=mime.startsWith("video/")
    fun requireSize(size:Long){require(size>0){"视频文件为空"};require(size<=ORIGINAL_LIMIT){TOO_LARGE}}
    fun requireDuration(milliseconds:Long){require(milliseconds in 1..Long.MAX_VALUE/1000){"无法读取有效的视频时长"}}
    fun frameCount(milliseconds:Long):Long {
        requireDuration(milliseconds)
        return minOf((milliseconds + 999) / 1000, MAX_FRAMES.toLong())
    }

    fun frameTimes(milliseconds: Long): Sequence<Long> {
        val count = frameCount(milliseconds)
        return (0L until count).asSequence().map { i ->
            if (milliseconds <= MAX_FRAMES * 1000L) i * 1000 else (milliseconds - 1) * i / (count - 1)
        }
    }

    fun formatTime(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val tail = (seconds % 60).toString().padStart(2, '0')
        return if (minutes < 60) "$minutes:$tail"
        else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$tail"
    }
}
