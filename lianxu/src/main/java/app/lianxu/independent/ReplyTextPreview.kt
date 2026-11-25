package app.lianxu.independent

/** Extract only a top-level reply string from an unfinished JSON response. Final validation is unchanged. */
internal class ReplyTextPreview {
    private val wire=StringBuilder()
    private var emitted=0
    fun append(part:String):String {
        wire.append(part);if(wire.length>100000)return ""
        val s=wire.toString();val start=replyStart(s)?:return ""
        val out=StringBuilder();var i=start
        while(i<s.length){
            val ch=s[i++]
            if(ch=='\"')break
            if(ch<' ')return ""
            if(ch!='\\'){out.append(ch);continue}
            if(i==s.length)break
            when(val escaped=s[i++]){
                '\"','\\','/'->out.append(escaped)
                'b'->out.append('\b');'f'->out.append('\u000c');'n'->out.append('\n');'r'->out.append('\r');'t'->out.append('\t')
                'u'->{if(i+4>s.length)break;val code=s.substring(i,i+4).toIntOrNull(16)?:return "";out.append(code.toChar());i+=4}
                else->return ""
            }
        }
        if(out.isNotEmpty()&&out.last().isHighSurrogate())out.setLength(out.length-1)
        if(out.length<=emitted)return ""
        return out.substring(emitted).also{emitted=out.length}
    }
    private fun replyStart(s:String):Int? {
        var depth=0;var i=0
        while(i<s.length){
            when(s[i]){
                '{','['->{depth++;i++}
                '}',']'->{depth--;i++}
                '\"'->{
                    val begin=++i
                    while(i<s.length&&s[i]!='\"'){if(s[i]=='\\')i++;i++}
                    if(i>=s.length)return null
                    val name=s.substring(begin,i);i++
                    if(depth==1&&name=="reply"){
                        while(i<s.length&&s[i].isWhitespace())i++
                        if(i>=s.length||s[i++]!=':')continue
                        while(i<s.length&&s[i].isWhitespace())i++
                        if(i<s.length&&s[i]=='\"')return i+1
                    }
                }
                else->i++
            }
        }
        return null
    }
}
