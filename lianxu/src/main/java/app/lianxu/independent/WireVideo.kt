package app.lianxu.independent

import java.io.File

/** Always a verified local original, streamed without decoding, changing tracks or metadata. */
class WireVideo internal constructor(val mime:String,internal val file:File,val description:String) {
    internal val size get()=file.length()
    internal fun input()=file.inputStream()
    override fun toString()="WireVideo($mime,$size bytes)"
}
