package app.lianxu.independent

import android.content.ContentValues
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import java.io.File

class MemoryUiSeedAndroidTest {
 // Fixed source IDs belong to this fixture only; previous screenshot runs must not leave
 // message_state/source withdrawals in the application's default database.
 @get:Rule(order=0) val fixture=MockChatFixture({object:TextChatClient{
  override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("Memory-page fixture must not call a model")
 }})
 @get:Rule(order=1) val ui=createAndroidComposeRule<LianxuActivity>()
 @Test fun seedAndCapture(){
  lateinit var vm:AppState
  ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]}
  ui.waitUntil(15000){vm.ready}
  ui.waitForIdle();if(ui.onAllNodesWithText("知道了").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithText("知道了").performClick()
  val repo=vm.processing;val role="memory-ui-task-a";val now=System.currentTimeMillis()
  var detailId=""
  runBlocking{
   repo.store.add(listOf(Friend(role,"晚星","记忆页合成验收数据")),false)
   repo.tx{db->db.delete("memory_current","friend_id=?",arrayOf(role))}
   val texts=listOf("你明天下午三点有个面试，有点紧张","你今天加班到很晚，还没吃晚饭","你最近在学吉他，想先学会晴天","你养过一只叫团子的橘猫","你小时候在外婆家住过一个暑假","我对芒果过敏，别推荐芒果味的东西","我的生日是三月十四日","叫我小星就好，不要叫全名")
   val days=listOf(0,0,2,20,40,0,3,30)
   texts.forEachIndexed{i,text->
    val old=repo.manualSave(role,null,text,MemoryCategory.STATE_FACT,false,false,MemoryAbout.USER)
    val at=now-days[i]*86400000L
    val evidence=if(i==0)listOf(MemoryEvidence(SourceRef(role,9000001,1),"user","明天下午三点面试，好紧张啊","明天下午三点面试，好紧张啊",now,"Asia/Shanghai"))else emptyList()
    val m=old.copy(manual=i>=5,createdAt=at,evidence=evidence)
    repo.tx{db->db.execSQL("UPDATE memory_current SET json=? WHERE id=?",arrayOf(m.json().toString(),m.id));MemoryForgetting.put(db,m,MemoryRecallState(now,0,120.0,listOf(95.0,85.0,65.0,35.0,10.0,95.0,90.0,60.0)[i]))}
    if(i==0)detailId=m.id
   }
   repo.tx{db->
    db.execSQL("INSERT OR REPLACE INTO messages(id,friend_id,body,mine,created) VALUES(9000001,?,?,1,?)",arrayOf<Any>(role,"明天下午三点面试，好紧张啊",now))
    db.execSQL("INSERT OR REPLACE INTO messages(id,friend_id,body,mine,created) VALUES(9000002,?,?,0,?)",arrayOf<Any>(role,"别怕，你准备了那么久。面试完第一时间告诉我呀",now))
    db.execSQL("INSERT OR REPLACE INTO chat_turns(id,friend_id,user_message_id,assistant_message_id,state,attempt,config_id,owner_session,source_revision,source_zone) VALUES('memory-ui-source',?,9000001,9000002,'COMPLETED',1,'','',1,'Asia/Shanghai')",arrayOf(role))
    db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key","memory-batch-failure:$role");put("value","""{"batch":"ui-seed","code":"NETWORK","detail":"合成验收：网络中断，没有调用模型","attempts":3,"first":1,"last":1,"at":$now}""")},android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
   }
   vm.refreshProcessing()
  }
  ui.runOnIdle{vm.open("processing-role",role)}
  ui.waitUntil(15000){ui.onAllNodesWithTag("memory-$detailId").fetchSemanticsNodes().isNotEmpty()}
  fun shot(name:String){ui.waitForIdle();Thread.sleep(500);val context=InstrumentationRegistry.getInstrumentation().targetContext;val dir=File(context.getExternalFilesDir(null),"memory-task-a").apply{mkdirs()};
      val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
      try {
          File(dir,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
      } finally { bitmap.recycle() }
    }
  shot("after_list")
  ui.onNodeWithText("更多").performClick();shot("after_list_menu");ui.onNodeWithText("总结记忆与维护").performClick();ui.onNodeWithTag("back").performClick()
  ui.onNodeWithText("我写的").performClick();shot("after_mine")
  ui.onNodeWithText("她记下的").performClick();ui.onNodeWithTag("memory-$detailId").performClick();shot("after_detail")
  ui.onNodeWithTag("memory-detail-menu").performClick();shot("after_more")
  ui.onNodeWithText("查看来源快照").performClick();ui.onNodeWithText("关闭").performClick()
  ui.onNodeWithText("删除").performClick();shot("after_delete_confirm");ui.onNodeWithText("取消").performClick()
  ui.onNodeWithTag("memory-jump-chat").performScrollTo().assertIsDisplayed()
  ui.mainClock.autoAdvance=false
  ui.onNodeWithTag("memory-jump-chat").performClick()
  // The click launches a Compose-scope coroutine. Keep rendering frames while awaiting its IO,
  // without auto-advancing through the whole highlight animation before the screenshot.
  try{ui.waitUntil(15000){ui.mainClock.advanceTimeByFrame();vm.page=="chat"}}
  catch(e:Throwable){throw AssertionError("page=${vm.page}; sources=${runBlocking{repo.sources(role)}}; messages=${vm.messages.filter{it.friendId==role}}; tree=${ui.onRoot(useUnmergedTree=true).printToString()}",e)}
  ui.mainClock.advanceTimeBy(300)
  ui.onNodeWithText("明天下午三点面试，好紧张啊").assertIsDisplayed()
  ui.onAllNodesWithTag("chat-turn-highlight",useUnmergedTree=true).onFirst().assertIsDisplayed()
  shot("after_jump")
  ui.mainClock.autoAdvance=true
  InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
  ui.waitUntil(10000){ui.onAllNodesWithTag("memory-timeline").fetchSemanticsNodes().isNotEmpty()}
  ui.onNodeWithTag("inner-tab-2").assertIsSelected()
 }
 /** 那轮聊天后来被改过：来源卡仍显示她记下时的原话（回退到证据原句），跳转按钮按轮次照常出现。 */

}
