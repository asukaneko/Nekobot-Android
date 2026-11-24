package app.lianxu.independent

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlin.math.sqrt

internal data class VoiceEnvelope(val durationMs: Long, val levels: List<Float>)

internal object VoiceAudio {
    fun decode(file: File): VoiceEnvelope {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true }
            extractor.selectTrack(track)
            val input = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(requireNotNull(input.getString(MediaFormat.KEY_MIME)))
            try {
                codec.configure(input,null,null,0); codec.start()
                val info = MediaCodec.BufferInfo()
                val bins = mutableListOf<Double>()
                var sum = 0.0; var count = 0; var frames = 0L
                var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                var eos = false
                val deadline = android.os.SystemClock.elapsedRealtime()+10000
                while(true) {
                    check(android.os.SystemClock.elapsedRealtime()<deadline && !Thread.currentThread().isInterrupted)
                    if(!eos) {
                        val i=codec.dequeueInputBuffer(5000)
                        if(i>=0) {
                            val buffer=requireNotNull(codec.getInputBuffer(i));buffer.clear()
                            val n=extractor.readSampleData(buffer,0)
                            eos=n<0
                            codec.queueInputBuffer(i,0,n.coerceAtLeast(0),if(eos) 0 else extractor.sampleTime,if(eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            if(!eos) extractor.advance()
                        }
                    }
                    val i=codec.dequeueOutputBuffer(info,5000)
                    if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val fmt=codec.outputFormat
                        rate=fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if(fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding=fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        require(encoding==AudioFormat.ENCODING_PCM_16BIT || encoding==AudioFormat.ENCODING_PCM_FLOAT)
                    }
                    if(i<0) continue
                    try {
                        val data=requireNotNull(codec.getOutputBuffer(i)).order(ByteOrder.nativeOrder())
                        data.position(info.offset);data.limit(info.offset+info.size)
                        val stride=channels*(if(encoding==AudioFormat.ENCODING_PCM_FLOAT) 4 else 2)
                        while(data.remaining()>=stride) {
                            repeat(channels){val v=if(encoding==AudioFormat.ENCODING_PCM_FLOAT)data.float.toDouble() else data.short/32768.0;sum+=v*v}
                            frames++;count++
                            if(count>=rate/100) {bins+=sum/(count*channels);sum=0.0;count=0}
                        }
                    } finally {codec.releaseOutputBuffer(i,false)}
                    if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                if(count>0) bins+=sum/(count*channels)
                require(bins.isNotEmpty() && frames>0)
                val levels=List(40){i->
                    val lo=i*bins.size/40;val hi=((i+1)*bins.size/40).coerceAtLeast(lo+1)
                    sqrt(bins.subList(lo,hi).average()).toFloat()
                }
                val peak=levels.max().coerceAtLeast(0.01f)
                return VoiceEnvelope(frames*1000/rate,levels.map{(it/peak).coerceIn(0f,1f)})
            } finally {codec.release()}
        } finally {extractor.release()}
    }
}
