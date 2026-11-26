package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource
import kotlinx.coroutines.runBlocking

/** Old seeded roles exist only in explicit synthetic fixtures, never in a fresh production database. */
internal fun seedLegacyTestRoles(context:android.content.Context,database:String)=runBlocking {
    val store=Store(context,database)
    try {
        store.add(listOf(Friend("seed-lin","林间","合成旧角色甲"),Friend("seed-an","时安","合成旧角色乙")),true)
    }finally{store.close()}
}

/** Instrumentation-only data/client injection; no credentials or fake reply in the production default. */
class MockChatFixture(private val clientFactory:(()->TextChatClient)?=null,private val hostModels:HostModelBridge=HostModelBridge { emptyList() }):ExternalResource(){
    private val id=System.nanoTime().toString()
    val namespace="mock-chat-$id"
    val database="mock-chat-$id.db"
    private lateinit var previousDatabase:String
    private lateinit var previousNamespace:String
    private lateinit var previousClient:()->TextChatClient
    override fun before(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        previousDatabase=AppServices.databaseName;previousNamespace=AppServices.vaultNamespace;previousClient=AppServices.createTextClient
        AppServices.databaseName=database;AppServices.vaultNamespace=namespace
        LianxuModule.initialize(context.applicationContext as android.app.Application,
            hostModels, HostRoleBridge { null },
            { object : HostPrivacyGate {
                @androidx.compose.runtime.Composable override fun Content(content: @androidx.compose.runtime.Composable () -> Unit) = content()
                override fun returningToHost() {}
            } }, {})
        seedLegacyTestRoles(context,database)
        runBlocking { val store=Store(context,database); try {
            val code=InstalledBuild.read(context).versionCode
            if(LocalReleaseNotes.forVersion(code)!=null)LocalUpdateRepository(store).acknowledge(code)
        } finally { store.close() } }
        ModelVault(context,namespace).apply{
            save(ModelConfig("fixture","合成测试服务","custom","https://example.invalid/v1","synthetic-model"),"synthetic-key-only")
            setPurpose(ModelPurpose.CHAT,"fixture")
        }
        AppServices.createTextClient=clientFactory ?: { object:TextChatClient {
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest): TextChatResult =
                error("Unexpected model request in UI fixture")
        } }
    }
    override fun after(){
        AppServices.createTextClient=previousClient;AppServices.databaseName=previousDatabase;AppServices.vaultNamespace=previousNamespace
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        ModelVault(context,namespace).let{v->v.list().forEach{v.delete(it.id)}}
        LianxuModule.roles.store.close()
        context.deleteDatabase(database)
    }
}
