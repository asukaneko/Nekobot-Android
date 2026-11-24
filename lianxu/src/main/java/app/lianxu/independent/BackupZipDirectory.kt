package app.lianxu.independent

import java.io.File
import java.io.RandomAccessFile

/** Bounded raw directory scan BEFORE ZipFile is allowed to allocate its native/Java index. */
internal object BackupZipDirectory {
    const val MAX_DIRECTORY_BYTES=16L*1024*1024
    data class Checked(val records:Int,val directoryBytes:Long)
    private fun u16(b:ByteArray,at:Int)=(b[at].toInt() and 255) or ((b[at+1].toInt() and 255) shl 8)
    private fun u32(b:ByteArray,at:Int)=(0..3).fold(0L){n,i->n or ((b[at+i].toLong() and 255) shl (8*i))}
    private fun u64(b:ByteArray,at:Int):Long {require(b[at+7]>=0){"ZIP64数值超出安全范围"};return (0..7).fold(0L){n,i->n or ((b[at+i].toLong() and 255) shl (8*i))}}
    private fun read(f:RandomAccessFile,at:Long,n:Int):ByteArray{require(at>=0&&at<=f.length()-n){"备份ZIP目录越界或不完整"};return ByteArray(n).also{f.seek(at);f.readFully(it)}}
    fun check(file:File,valid:()->Unit={}):Checked=RandomAccessFile(file,"r").use{f->
        val length=f.length();require(length>=22&&u32(read(f,0,4),0)==0x04034b50L){"备份ZIP文件头无效或不完整"}
        val tailSize=minOf(length,65557).toInt();val tail=read(f,length-tailSize,tailSize)
        val end=(tailSize-22 downTo 0).firstOrNull{i->u32(tail,i)==0x06054b50L&&i+22+u16(tail,i+20)==tailSize}?:error("备份ZIP结尾缺失或含额外尾部，未导入")
        val endAt=length-tailSize+end
        require(u16(tail,end+4)==0&&u16(tail,end+6)==0){"暂不支持分卷ZIP备份"}
        var count=u16(tail,end+10).toLong();var onDisk=u16(tail,end+8).toLong()
        var size=u32(tail,end+12);var offset=u32(tail,end+16);var expectedEnd=endAt
        if(count==65535L||onDisk==65535L||size==0xffffffffL||offset==0xffffffffL){
            val locator=read(f,endAt-20,20);require(u32(locator,0)==0x07064b50L&&u32(locator,4)==0L&&u32(locator,16)==1L){"ZIP64定位或分卷信息无效"}
            val at=u64(locator,8);val header=read(f,at,56);val recordSize=u64(header,4)
            require(u32(header,0)==0x06064b50L&&recordSize in 44..MAX_DIRECTORY_BYTES&&at<=endAt-32-recordSize&&at+12+recordSize==endAt-20){"ZIP64目录记录无效"}
            require(u32(header,16)==0L&&u32(header,20)==0L){"暂不支持分卷ZIP64备份"}
            onDisk=u64(header,24);count=u64(header,32);size=u64(header,40);offset=u64(header,48);expectedEnd=at
        }
        require(count in 2..BackupArchive.MAX_ENTRIES.toLong()&&count==onDisk){"备份文件数量无效或过多"}
        require(size in 92..MAX_DIRECTORY_BYTES&&offset>=4&&offset<=expectedEnd&&size==expectedEnd-offset){"备份ZIP目录体积或边界无效"}
        val limit=offset+size;var at=offset;var actual=0;val names=hashSetOf<String>()
        while(at<limit){
            valid();require(++actual<=BackupArchive.MAX_ENTRIES){"备份目录实际文件数量过多"}
            require(limit-at>=46){"备份ZIP目录记录不完整"};val header=read(f,at,46)
            require(u32(header,0)==0x02014b50L){"备份ZIP目录记录无效"}
            val nameLength=u16(header,28);val extra=u16(header,30);val comment=u16(header,32)
            require(nameLength in 1..128){"备份ZIP路径过长或为空"}
            val n=46L+nameLength+extra+comment;require(n<=limit-at){"备份ZIP目录记录越界"}
            val raw=read(f,at+46,nameLength);require(raw.all{(it.toInt() and 255) in 32..126}){"备份ZIP路径编码无效"}
            val name=RoleArchive.entryName(raw.toString(Charsets.US_ASCII));require(names.add(name)){"备份含重复或混淆路径"}
            at+=n
        }
        require(at==limit&&actual.toLong()==count){"备份目录实际数量与声明不一致"};valid();Checked(actual,size)
    }
}
