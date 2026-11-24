package app.lianxu.independent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Keeps one conversation owner across Activity/ViewModel replacement while a reply is in flight. */
internal class ChatRunnerBinding private constructor(private val key:Pair<String,String>){
    companion object {
        private val bindings=mutableMapOf<Pair<String,String>,ChatRunnerBinding>()
        fun acquire(store:Store,vault:ModelVault,client:TextChatClient,processing:ProcessingRepository,host:AppState):ChatRunnerBinding {
            val key=store.databaseName to vault.statusNamespace
            val binding=bindings.getOrPut(key){ChatRunnerBinding(key)}
            binding.host=host
            if(!binding::runner.isInitialized){
                binding.memory=MemoryEngine(binding.scope,processing,ModelAccess(vault),client,{binding.host?.refreshChat()})
                binding.runner=ConversationRunner(binding.scope,store,vault,client,processing,binding.memory,{binding.host?.refreshChat()},
                    {binding.host?.chatNotice(it)},{binding.host?.settleCompanion()},displayReplies=true,ownForegroundRequests=true,
                    isReadingChat={role -> binding.host?.isReadingChat(role) == true},
                    appInForeground={binding.host?.chatHostLifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)==true},onIdle=binding::releaseIfDetached)
            }
            return binding
        }
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var host:AppState?=null
    lateinit var memory:MemoryEngine
        private set
    lateinit var runner:ConversationRunner
        private set
    fun detach(owner:AppState){
        if(host!==owner)return
        host=null
        releaseIfDetached()
    }
    private fun releaseIfDetached(){
        if(host==null&&!runner.hasActiveAttempts&&bindings[key]===this){bindings.remove(key);scope.cancel()}
    }
}
