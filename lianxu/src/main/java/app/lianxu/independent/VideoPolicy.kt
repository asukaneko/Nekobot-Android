package app.lianxu.independent

internal object VideoPolicy {
    const val ORIGINAL_LIMIT=70_000_000L
    const val TOO_LARGE="暂不支持超过70 MB的视频"
    fun isVideo(mime:String)=mime.startsWith("video/")
    fun requireSize(size:Long){require(size>0){"视频文件为空"};require(size<=ORIGINAL_LIMIT){TOO_LARGE}}
    fun requireDuration(milliseconds:Long){require(milliseconds in 1..Long.MAX_VALUE/1000){"无法读取有效的视频时长"}}
    fun frameCount(milliseconds:Long):Long {
        requireDuration(milliseconds)
        return milliseconds/1000+if(milliseconds%1000==0L)0 else 1
    }
    /** Every whole second starting at zero, including the last partial second; never sample sparsely. */
    fun frameTimes(milliseconds:Long):Sequence<Long> {
        requireDuration(milliseconds)
        return generateSequence(0L){it+1000}.takeWhile{it<milliseconds}
    }
}
