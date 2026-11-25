package app.lianxu.independent

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Read old archives without restoring the removed scoring feature. Other fields stay strict. */
internal object RetiredRelationshipData {
    val tables=listOf("relationship_state","relationship_history","relationship_jobs")
    val policyFields=listOf("relationshipEnabled","relationshipAutomatic","relationshipInterval","historyChoice")
    fun stripRole(root:JsonObject){
        root["prompts"]?.asJsonObject?.remove("initialScores")
        root["records"]?.asJsonObject?.let{records->tables.forEach(records::remove)}
    }
    fun stripBackup(root:JsonObject){
        root["selections"].asJsonObject.remove("relationship")
        root["roles"].asJsonArray.forEach{entry->
            val role=entry.asJsonObject
            val records=role["tables"].asJsonObject
            tables.forEach(records::remove)
            records["processing_policies"]?.asJsonArray?.forEach{row->
                val record=row.asJsonObject;val policy=JsonParser.parseString(record["json"].asString).asJsonObject
                policyFields.forEach(policy::remove);record.addProperty("json",policy.toString())
            }
            val preferences=role["preferences"].asJsonObject
            for(key in preferences.keySet().toList())if(key.startsWith("role-prompts:")){
                val prompts=JsonParser.parseString(preferences[key].asString).asJsonObject
                prompts.remove("initialScores");preferences.addProperty(key,prompts.toString())
            }
        }
    }
}
