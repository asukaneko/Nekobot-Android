package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MoodHeroUiTest {
    @get:Rule val ui=createComposeRule()
    @Test fun bothPaperThemesPaintDistinctSkiesAndReadableInkForAllSixMoods(){
        var dark by mutableStateOf(false)
        val base=RelationState(.3,.2,0.0,0.0,0.0,0.0,0.0,emptyMap(),null)
        var current by mutableStateOf(base)
        ui.setContent {
            LianxuTheme(dark) {
                Box(Modifier.fillMaxSize().background(Chrome)){
                    MoodHero(RelationshipView(RelationTemplate.STEADY,RelationStart.NEW,emptyList(),current,""),{}){}
                }
            }
        }
        for(night in listOf(false,true)){
            ui.runOnIdle{dark=night}
            val samples=mutableSetOf<Int>()
            val states=listOf(base.copy(missing=.5),base.copy(happy=.5),base,base.copy(hurt=.5),base.copy(unhappy=.5),base.copy(tension=.5))
            for(s in states){
                ui.runOnIdle{current=s}
                val rendered=ui.onNodeWithTag("mood-hero").assertIsDisplayed().captureToImage().toPixelMap()
                val wash=rendered[rendered.width/2,rendered.height/4]
                val paper=rendered[rendered.width-1,rendered.height-1]
                samples+=(wash.value shr 32).toInt()
                assertTrue(if(night)paper.luminance()<.15f && wash.luminance()<.4f else paper.luminance()>.75f && wash.luminance()>.55f)
            }
            assertEquals(6,samples.size)
        }
    }
}
