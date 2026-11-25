package app.lianxu.independent

/**
 * Adapted from Nekobot-Android LocalStreamEventCoalescer (GPL v3), authored by
 * asukaneko; source revision ef9cc6f0743d38dbeccb7602f2a46f4eeacaeb1a.
 * See docs/REUSE-PROVENANCE.md. Full response accumulation remains in transport.
 */
internal class TextStreamEventCoalescer(
    private val emit:(TextStreamEvent)->Unit,
    private val nowNanos:()->Long=System::nanoTime,
    private val intervalNanos:Long=100_000_000L,
    private val charBatch:Int=256,
) {
    private val pending=StringBuilder()
    private var reasoning:Boolean?=null
    private var lastEmitNanos:Long?=null
    private val emittedKinds=mutableSetOf<Boolean>()

    fun append(event:TextStreamEvent){
        if(event.text.isEmpty())return
        val kind=event is TextStreamEvent.Reasoning
        if(reasoning!=null&&reasoning!=kind)flush()
        reasoning=kind;pending.append(event.text)
        val elapsed=lastEmitNanos?.let{nowNanos()-it}?:Long.MAX_VALUE
        if(kind !in emittedKinds||pending.length>=charBatch||elapsed>=intervalNanos)flush()
    }
    fun flush(){
        val kind=reasoning?:return
        if(pending.isEmpty())return
        val text=pending.toString();pending.clear();reasoning=null
        lastEmitNanos=nowNanos();emittedKinds.add(kind)
        emit(if(kind)TextStreamEvent.Reasoning(text) else TextStreamEvent.Content(text))
    }
}
