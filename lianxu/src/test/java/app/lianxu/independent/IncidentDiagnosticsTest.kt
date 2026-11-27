package app.lianxu.independent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IncidentDiagnosticsTest {
    @get:Rule val temp=TemporaryFolder()
    private fun rows(bytes:ByteArray)=bytes.decodeToString().lineSequence().filter { it.isNotBlank() }
        .map { JsonParser.parseString(it).asJsonObject }.toList()

    @Test fun persistedMarkerSurvivesNewRecorderAndKeepsExitKindsDistinctInBothExports() {
        val directory=temp.newFolder()
        val notes=File(directory,"process")
        val first=ProcessEvidence(notes,72)
        first.view("settings")
        first.action(ProcessAction.OPEN_MODELS)
        first.view("settings") // recomposition must preserve the click
        val secret="user-supplied-private-message"
        class StorageReadFailure(message: String): RuntimeException(message)
        first.uncaught(StorageReadFailure("cannot read: api_key=$secret " + "x".repeat(300)))
        first.uncaught(RuntimeException("second-fault"))
        val exit=System.currentTimeMillis()
        val next=ProcessEvidence(notes,74,exit+1)
        val system=listOf(OwnExit(72,exit,ExitKind.JAVA_CRASH),OwnExit(71,exit-1,ExitKind.LOW_MEMORY),
            OwnExit(70,exit-2,ExitKind.USER_REQUESTED),OwnExit(74,exit+1,ExitKind.ANR))
        repeat(2){next.recover(system,exit+1)}
        DiagnosticJournal(directory,"fixture").use { journal ->
            for(b in listOf(false,true)) {
                val bytes=journal.export(b)
                assertFalse(bytes.decodeToString().contains(secret))
                val all=rows(bytes)
                val fault=all.single { it["event"]?.asString=="process.uncaught" }
                assertEquals("settings",fault["page"].asString)
                assertEquals("OPEN_MODELS",fault["action"].asString)
                assertEquals("StorageReadFailure",fault["exception_type"].asString)
                if (b) {
                    assertTrue(fault["error_summary"].asString.startsWith("cannot read: api_key=[已脱敏]"))
                    assertEquals(240,fault["error_summary"].asString.length)
                } else assertFalse(fault.has("error_summary"))
                assertTrue(fault["app_frames"].asString.contains("IncidentDiagnosticsTest:"))
                val exits=all.filter { it["event"]?.asString=="process.exit" }
                assertEquals(setOf("JAVA_CRASH","LOW_MEMORY","USER_REQUESTED"),exits.map { it["reason"].asString }.toSet())
                assertEquals(3,exits.size)
                assertEquals(fault["trace"],exits.single { it["reason"].asString=="JAVA_CRASH" }["trace"])
                assertFalse(exits.single { it["reason"].asString=="LOW_MEMORY" }.has("trace"))
            }
        }
    }

    @Test fun basicFeatureEvidenceKeepsOnlyTypedOperationalValues() {
        DiagnosticJournal(temp.newFolder(),"fixture").use { j ->
            val privateText = "private chat text"
            j.record("review.inner_note.ignored","trace",mapOf("reason" to "too_long","innerNote" to privateText))
            j.record("memory.delete.experience","trace",mapOf("count" to 3,"reason" to privateText,"source" to privateText))
            j.record("daily.plan.failure","trace",mapOf("kind" to "NETWORK","prompt" to privateText))
            val output=j.export(false)
            assertFalse(output.decodeToString().contains(privateText))
            fun data(event:String)=rows(output).single { it["event"]?.asString==event }.getAsJsonObject("data")
            assertEquals("too_long",data("review.inner_note.ignored")["reason"].asString)
            assertEquals(3,data("memory.delete.experience")["count"].asInt)
            assertEquals("NETWORK",data("daily.plan.failure")["kind"].asString)
        }
    }

    @Test fun basicExportsTypedNetworkEvidenceAndRejectsForeignPayloads() {
        val id=UUID.randomUUID().toString()
        DiagnosticJournal(temp.newFolder(),"fixture").use { j ->
            val privateText="synthetic-body-and-user-id"
            j.record("http.phase",id,mapOf("request_id" to id,"turn" to id,"request_number" to 2,
                "stage" to "appraise","transport_phase" to "await_response_headers","client_write_completed_bytes" to 487598,"response_bytes" to 0,
                "cancel_source" to "none","exception_stack" to privateText))
            j.record("http.response",id,mapOf("status" to 403,"stream" to false,"url" to privateText))
            j.record("http.network_failed",id,mapOf("error_type" to "java.net.SocketException","reason" to privateText))
            j.record("network.response",id,mapOf("status" to "failure","reason" to "FORBIDDEN","http_status" to 403))
            j.record("http.phase",id,mapOf("request_id" to privateText,"request_number" to "2","span" to privateText,
                "stream" to "true","stage" to privateText,"status" to 700,"transport_phase" to privateText,"response_bytes" to -1))
            j.record("unknown",id,mapOf("request_id" to id,"status" to 403))
            val output=j.export(false)
            val all=rows(output)
            assertEquals("unknown",all.first()["prior_process_unflushed_events"].asString)
            assertFalse(output.decodeToString().contains(privateText))
            assertEquals("appraise",all[1]["data"].asJsonObject["stage"].asString)
            assertEquals(487598,all[1]["data"].asJsonObject["client_write_completed_bytes"].asInt)
            assertEquals(id,all[1]["data"].asJsonObject["request_id"].asString)
            assertEquals(403,all[2]["data"].asJsonObject["status"].asInt)
            assertEquals("SocketException",all[3]["data"].asJsonObject["error_type"].asString)
            assertEquals("FORBIDDEN",all[4]["data"].asJsonObject["reason"].asString)
            assertFalse(all[5].has("data")); assertFalse(all[6].has("data"))
            assertTrue(j.export(true).decodeToString().contains(privateText))
        }
    }
}
