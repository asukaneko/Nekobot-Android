package app.lianxu.independent

/**
 * Counts what one SSE connection actually delivered, so a rejection names the real check instead of
 * a blanket "format" error: the total byte cap, a body that never used SSE framing, one line or one
 * payload over its own cap. Holds counters only; the sole content kept is a short sample of the
 * first line that was neither blank, a comment, an SSE field nor `data:`.
 */
internal class SseStreamGuard(val maxWireBytes:Int=MAX_WIRE_BYTES) {
    companion object {
        /**
         * SSE framing costs roughly 150-200 bytes per token, so a full 32768-token answer can exceed
         * several MiB even when the dialogue itself is small. Payload caps below bound the real text.
         */
        const val MAX_WIRE_BYTES=8*1024*1024
        /** A body is only called "not an event stream" after this many bytes never used SSE framing. */
        const val PROBE_BYTES=64*1024
        /** One wire line may not exceed this; the largest legitimate `data:` payload stays below it. */
        const val LINE_LIMIT_BYTES=1024*1024
        const val PROGRESS_BYTES=1024*1024
        const val SAMPLE_CHARS=96
        private const val LF='\n'.code.toByte()
        private const val CR='\r'.code.toByte()
        private const val TAB='\t'.code.toByte()
        private const val SPACE=' '.code.toByte()
    }
    var bytes=0L;private set
    var dataLines=0;private set
    var blankLines=0;private set
    var commentLines=0;private set
    var fieldLines=0;private set
    var otherLines=0;private set
    var longestLine=0;private set
    var dataChars=0L;private set
    /** Content and reasoning lengths are reported by the parser; they never gate the wire cap. */
    var contentChars=0;private set
    var reasoningChars=0;private set
    private var sample:Any=""
    private var sampled=false
    private var lineBytes=0
    /** 0 = still undecided, 1 = SSE-shaped opening seen, -1 = definitely not SSE. */
    private var headState=0
    private var bomPhase=0
    private var nextProgress=PROGRESS_BYTES.toLong()

    /**
     * Counts one read. The body head is judged as soon as its first meaningful byte arrives (leading
     * blanks and an optional UTF-8 BOM are skipped, however many reads they span), and one line that
     * keeps growing past the line cap is reported instead of waiting for the whole body.
     */
    fun read(b:ByteArray,off:Int,len:Int):String? {
        if(len<=0)return null
        bytes+=len
        headVerdict(b,off,len)?.let{return it}
        for(i in off until off+len){
            // BufferedReader breaks lines on CR, LF or CRLF; all three end the current line.
            if(b[i]==LF||b[i]==CR)lineBytes=0 else lineBytes++
            if(lineBytes>LINE_LIMIT_BYTES)return "line_limit"
        }
        return if(bytes>maxWireBytes)"wire_limit" else null
    }

    /** Classifies one completed line; returns "protocol_probe" when nothing has been SSE-framed. */
    fun line(raw:String):String? {
        longestLine=maxOf(longestLine,raw.length)
        when {
            raw.isEmpty()->blankLines++
            raw.startsWith(":")->commentLines++
            raw.startsWith("data:")->{dataLines++;dataChars+=raw.length}
            raw.startsWith("event:")||raw.startsWith("id:")||raw.startsWith("retry:")->fieldLines++
            else->{
                otherLines++
                if(!sampled) {
                    sampled=true
                    val prefix=raw.take(SAMPLE_CHARS)
                    sample=Diagnostics.raw(if(raw.length>prefix.length)Diagnostics.protectTail(prefix) else prefix) ?: ""
                }
            }
        }
        // Blank lines alone never prove SSE: an error page may carry them too.
        return if(dataLines==0&&fieldLines==0&&commentLines==0&&otherLines>0&&bytes>=PROBE_BYTES)"protocol_probe" else null
    }

    fun parsed(content:Int,reasoning:Int){contentChars=content;reasoningChars=reasoning}
    fun progressDue()=bytes>=nextProgress
    fun progressed(){while(nextProgress<=bytes)nextProgress+=PROGRESS_BYTES}
    fun snapshot():Map<String,Any?> =mapOf("bytes" to bytes,"limit" to maxWireBytes,"data_lines" to dataLines,
        "blank_lines" to blankLines,"comment_lines" to commentLines,"field_lines" to fieldLines,"other_lines" to otherLines,
        "longest_line" to longestLine,"line_bytes" to lineBytes,"data_chars" to dataChars,
        "content_chars" to contentChars,"reasoning_chars" to reasoningChars,"sample" to sample)

    /** The first meaningful byte decides whether this can be an SSE body at all. */
    private fun headVerdict(b:ByteArray,off:Int,len:Int):String? {
        if(headState!=0)return null
        for(i in off until off+len){
            val v=b[i]
            if(bomPhase<3){
                val expected=when(bomPhase){0->0xEF;1->0xBB;else->0xBF}
                if(v.toInt() and 0xff==expected){bomPhase++;continue}
                bomPhase=3
            }
            if(v==LF||v==CR||v==SPACE||v==TAB)continue
            // data:/event:/id:/retry: and SSE comments are the only legal openings.
            headState=if(v.toInt().toChar() in "deir:")1 else -1
            return if(headState<0)"protocol_probe" else null
        }
        return null
    }
}

/**
 * Maps a failed stream check to the failure the UI and the memory retry policy actually see.
 * Reading over a cap and a body that was never an event stream are different problems.
 */
internal fun streamCheckFailure(check:String):ChatFailure=when(check){
    "wire_limit","line_limit","data_limit","content_limit","reasoning_limit","tool_limit"->ChatFailure.STREAM_LIMIT
    "secret_leak"->ChatFailure.FORMAT
    "service_error"->ChatFailure.REJECTED
    "tools_unavailable"->ChatFailure.TOOLS_UNAVAILABLE
    "interrupted"->ChatFailure.INTERRUPTED
    "done_before_finish","incomplete","finish_length"->ChatFailure.TRUNCATED
    else->ChatFailure.STREAM_PROTOCOL
}
