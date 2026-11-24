package app.lianxu.independent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 总结在她回话写到一半时保存，会改掉这一轮回话依据的快照，回话写入时就被判「来源已变」。
 * 所以总结只在最后保存那一步等一等：同一角色有回话在进行，就等它写完再保存；
 * 保存那一小会儿新回话稍等，保存完立刻放行。等太久（超时）就照原来的逻辑直接保存。
 */
internal object ReplyGate {
    private data class Role(val replies:Int=0,val saving:Boolean=false)
    private val roles=MutableStateFlow(emptyMap<String,Role>())
    private fun put(m:Map<String,Role>,role:String,r:Role)=if(r==Role())m-role else m+(role to r)

    /** 回话开始：若总结正在保存，先等保存完（只是一次写库）。 */
    suspend fun enter(role:String){
        while(true){
            roles.first{it[role]?.saving!=true}
            var entered=false
            roles.update{m->val r=m[role]?:Role();if(r.saving){entered=false;m}else{entered=true;put(m,role,r.copy(replies=r.replies+1))}}
            if(entered)return
        }
    }
    /** 回话结束（写完、失败、取消都算），或回话自己要等总结时先让开。 */
    fun leave(role:String){roles.update{m->val r=m[role];if(r==null)m else put(m,role,r.copy(replies=(r.replies-1).coerceAtLeast(0)))}}
    fun replying(role:String)=(roles.value[role]?.replies?:0)>0

    /** 总结保存：先等同一角色正在进行的回话写完，最多等 [waitMillis]；返回保存结果，[waited] 报告是否等过、是否超时。 */
    suspend fun <T> save(role:String,waitMillis:Long,waited:(Boolean,Boolean)->Unit={_,_->},block:suspend()->T):T {
        val busy=replying(role)
        var acquired=false
        // The flag, not withTimeoutOrNull's result, decides release: a timeout can race the last step.
        withTimeoutOrNull(waitMillis){
            while(true){
                roles.first{val r=it[role];r==null||(r.replies==0&&!r.saving)}
                roles.update{m->val r=m[role]?:Role();if(r.replies==0&&!r.saving){acquired=true;put(m,role,r.copy(saving=true))}else{acquired=false;m}}
                if(acquired)break
            }
        }
        waited(busy,!acquired)
        try{return block()}
        finally{if(acquired)roles.update{m->val r=m[role];if(r==null)m else put(m,role,r.copy(saving=false))}}
    }
}
