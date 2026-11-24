package app.lianxu.independent

import org.json.JSONArray
import org.json.JSONObject

/** Complete fictional examples of the existing protocol, not material for the current memory batch. */
internal object MemorySummaryExample {
    fun text(document:Boolean):String {
        val batch="example-batch";val character="example-character"
        val op=JSONObject().put("id","example-op-1").put("op","ADD").put("target","N1")
            .put("category","PREFERENCE_BOUNDARY").put("about","USER").put("text","我记得对方喜欢喝热茶。")
            .put("core",false).put("date_expression",JSONObject.NULL).put("evidence",JSONArray().put("S1"))
        fun header()=JSONObject().put("batch_id",batch).put("character_id",character)
        val end=header().put("operation_count",1).put("complete",true).put("end_marker",batch)
        val output=if(document)end.put("operations",JSONArray().put(op)).toString()
            else header().put("type","operation").put("operation",op).toString()+"\n"+end.put("type","complete").toString()
        return """
完整格式示例（虚构，绝不是本次事实）：假设批次为example-batch、角色为example-character，没有已保存操作，唯一来源S1是用户说“我喜欢喝热茶。”，完整输出如下：
<memory_format_example>
$output
</memory_format_example>
实际输出必须替换为本次给定的batch_id、character_id和可用来源；不得把示例偏好或示例文字写入本次记忆。每条操作都必须有id，不能只给op/target。已有id在本批修正时保持不变；只有缺id的项补上稳定id。N1只在未占用时用于新增；已有记忆按existing_memories的ref字段引用，不按列表位置或旧请求的临时编号猜测。结束回执的operation_count是本批去重后的总数，不是本次新增条数。
"""
    }
}
