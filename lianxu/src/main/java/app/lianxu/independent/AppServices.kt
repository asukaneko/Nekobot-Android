package app.lianxu.independent

/** Production defaults. Instrumentation supplies isolated local mocks before Activity creation. */
internal object AppServices {
    internal var voiceConnections=HttpsConnectionFactory { it.openConnection() as javax.net.ssl.HttpsURLConnection }
    @Volatile var createTextClient:()->TextChatClient={HttpsTextChatClient()}
    @Volatile var databaseName="lianxu.db"
    @Volatile var vaultNamespace="model-configs"
    @Volatile var companionClock:()->Long=System::currentTimeMillis
}
