package app.lianxu.independent

import org.junit.Test
import org.junit.Assert.*
import java.io.File

class BackupWorkspaceTest {
    @Test fun closedWorkspacesAreRemovedAndActiveWorkspacesAreNotReaped(){
        val parent=java.nio.file.Files.createTempDirectory("backup-work-test-").toFile()
        try{val first=BackupWorkspace.create(parent);first.zip.writeText("owned")
            val second=BackupWorkspace.create(parent);assertTrue(first.directory.exists());assertEquals("owned",first.zip.readText());second.close();assertFalse(second.directory.exists())
            first.close();assertFalse(first.directory.exists())
        }finally{File(parent,"lianxu-backup-work").delete();parent.delete()}
    }
    @Test fun abandonedUnlockedWorkspaceIsRemovedButUnrelatedFileIsNot(){
        val parent=java.nio.file.Files.createTempDirectory("backup-orphan-test-").toFile();val root=File(parent,"lianxu-backup-work").apply{mkdirs()}
        val old=File(root,"stage-abandoned").apply{mkdirs()};File(old,".lease").writeBytes(byteArrayOf());File(old,"archive.zip").writeText("partial")
        val unrelated=File(root,"keep.txt").apply{writeText("keep")}
        try{BackupWorkspace.create(parent).use{assertFalse(old.exists());assertEquals("keep",unrelated.readText())}}
        finally{unrelated.delete();root.delete();parent.delete()}
    }
    @Test fun spaceBoundaryKeepsReserveWithoutAnArchiveTotal(){BackupWorkspace.create().use{w->
        w.space(70_000_000,70_000_000+ImageDisk.RESERVE);assertTrue(runCatching{w.space(70_000_000,70_000_000+ImageDisk.RESERVE-1)}.isFailure)
        w.space(3L*1024*1024*1024,4L*1024*1024*1024)
    }}
    @Test fun interruptedInputClosesAndRemovesOnlyItsPrivateWorkspace(){
        val parent=java.nio.file.Files.createTempDirectory("backup-input-test-").toFile();var closed=false
        val input=object:java.io.InputStream(){override fun read():Int=throw java.io.IOException("synthetic interrupted");override fun close(){closed=true}}
        try{assertTrue(runCatching{BackupArchive.read(input,parent)}.isFailure);assertTrue(closed);assertTrue(File(parent,"lianxu-backup-work").listFiles()!!.isEmpty())}
        finally{File(parent,"lianxu-backup-work").delete();parent.delete()}
    }
}
