package app.lianxu.independent

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun Header(title:String,back:()->Unit,extra:(@Composable ()->Unit)?=null){
    AppTopBar(title,back,"back",extra=extra)
}
@Composable internal fun AppTopBar(title:String,onBack:()->Unit,backTag:String="back",backDescription:String="返回",enabled:Boolean=true,extra:(@Composable ()->Unit)?=null){
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).background(Chrome).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
        IconButton(onClick=onBack,enabled=enabled,modifier=Modifier.size(UiSpace.action).testTag(backTag).semantics{contentDescription=backDescription}){ControlIcon("back",Ink)}
        Text(title,Modifier.weight(1f).padding(horizontal=8.dp,vertical=12.dp),style=PageTitleStyle,maxLines=2,overflow=TextOverflow.Ellipsis)
        extra?.invoke()
    }
}
internal val LocalRoleAvatars=staticCompositionLocalOf{emptyMap<String,ByteArray>()}
private val AvatarPaper=Color(0xFFFFF8F0)
private val AvatarStroke=Color(0xFFC9B08A)
private val AvatarSilhouette=Color(0xFFD8C7A8)

/** Imported role resources can have huge dimensions despite a small file size. Bound every decode. */
internal fun decodeAvatar(bytes:ByteArray,target:Int):android.graphics.Bitmap?=runCatching decode@{
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    if(bounds.outWidth<=0||bounds.outHeight<=0)return@decode null
    val maxEdge=(target.coerceIn(1,512)*2).coerceIn(128,1024)
    if(bounds.outWidth!=bounds.outHeight){
        val region=runCatching{android.graphics.BitmapRegionDecoder.newInstance(bytes,0,bytes.size,false)}.getOrNull()
        if(region!=null)try{
            val side=minOf(bounds.outWidth,bounds.outHeight);val left=(bounds.outWidth-side)/2;val top=(bounds.outHeight-side)/2
            var sample=1;while(side/sample>maxEdge)sample*=2
            val cropped=region.decodeRegion(android.graphics.Rect(left,top,left+side,top+side),BitmapFactory.Options().apply{inSampleSize=sample})
            if(cropped!=null)return@decode cropped
        }finally{region.recycle()}
    }
    var sample=1
    while(maxOf(bounds.outWidth,bounds.outHeight)/sample>maxEdge)sample*=2
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})
}.getOrNull()

@Composable internal fun SelfAvatar(bytes:ByteArray?,size:Int,tag:String,modifier:Modifier=Modifier){
    val target=(size*LocalDensity.current.density).toInt().coerceAtLeast(1)
    val image=remember(bytes,target){bytes?.let{decodeAvatar(it,target)?.asImageBitmap()}}
    Box(modifier.size(size.dp).clip(RoundedCornerShape((size*.30f).dp)).testTag(tag).semantics{contentDescription=if(image==null)"默认本人头像"else"本人头像"},contentAlignment=Alignment.Center){
        if(image!=null)Image(image,null,Modifier.fillMaxSize().testTag("$tag-image"),contentScale=ContentScale.Crop)
        else DefaultRoleAvatar(size,Modifier.testTag("$tag-default"))
    }
}

/** Set photo if the role has one; otherwise a soft default silhouette, never a character from the name. */
@Composable internal fun Avatar(friend:Friend,size:Int=48,modifier:Modifier=Modifier){
    val bytes=LocalRoleAvatars.current[friend.id]
    val target=(size*LocalDensity.current.density).toInt().coerceAtLeast(1)
    val image=remember(bytes,target){bytes?.let{decodeAvatar(it,target)?.asImageBitmap()}}
    val shape=RoundedCornerShape((size*.30f).dp)
    Box(modifier.size(size.dp).clip(shape).testTag("role-avatar-${friend.id}"),contentAlignment=Alignment.Center){
        if(image!=null)Image(image,friend.name,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        else DefaultRoleAvatar(size)
    }
}

/** Cream ground, light gold-brown outline, simple head-and-shoulders silhouette. */
@Composable internal fun DefaultRoleAvatar(size:Int,modifier:Modifier=Modifier){
    val shape=RoundedCornerShape((size*.30f).dp)
    Box(modifier.size(size.dp).clip(shape).background(AvatarPaper).border(1.dp,AvatarStroke,shape)){
        Canvas(Modifier.fillMaxSize()){
            val w=this.size.width;val h=this.size.height
            val head=Offset(w*.5f,h*.38f)
            drawCircle(AvatarSilhouette,radius=w*.22f,center=head)
            val body=Path().apply{
                val top=h*.58f
                moveTo(w*.18f,h)
                cubicTo(w*.18f,top,w*.32f,top,w*.5f,top)
                cubicTo(w*.68f,top,w*.82f,top,w*.82f,h)
                close()
            }
            drawPath(body,AvatarSilhouette)
        }
    }
}

@Composable internal fun PrimaryAction(text:String,modifier:Modifier=Modifier,enabled:Boolean=true,onClick:()->Unit){
    val press=androidx.compose.runtime.remember{androidx.compose.foundation.interaction.MutableInteractionSource()}
    Button(onClick=onClick,interactionSource=press,enabled=enabled,modifier=modifier.heightIn(min=UiSpace.action).pressScale(press),shape=RoundedCornerShape(16.dp),colors=freshButtonColors(),contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp)){Text(text,style=MaterialTheme.typography.labelLarge)}
}
@Composable internal fun CompactSaveAction(enabled:Boolean,onClick:()->Unit){
    Box(Modifier.widthIn(min=76.dp).heightIn(min=52.dp).clickable(enabled=enabled,role=Role.Button,onClick=onClick).testTag("config-save").semantics{contentDescription="保存配置"},contentAlignment=Alignment.Center){
        Text("保存",Modifier.clip(RoundedCornerShape(8.dp)).background(SelectedBackground).padding(horizontal=14.dp,vertical=6.dp),style=MaterialTheme.typography.labelLarge,color=GreenText.copy(alpha=if(enabled)1f else .4f))
    }
}
@Composable internal fun AppField(value:String,onChange:(String)->Unit,label:String,tag:String="",singleLine:Boolean=false,minLines:Int=1,enabled:Boolean=true,colors:TextFieldColors?=null){
    OutlinedTextField(value,onChange,label={Text(label)},singleLine=singleLine,minLines=minLines,maxLines=if(singleLine)1 else 6,enabled=enabled,modifier=Modifier.fillMaxWidth().testTag(tag),shape=RoundedCornerShape(14.dp),textStyle=MaterialTheme.typography.bodyLarge,colors=colors?:OutlinedTextFieldDefaults.colors(focusedContainerColor=Paper,unfocusedContainerColor=Paper,focusedBorderColor=GreenText,unfocusedBorderColor=Line))
}

@Composable internal fun SectionLabel(text:String,modifier:Modifier=Modifier){
    Text(text,modifier.padding(start=4.dp,top=4.dp,bottom=4.dp),style=MaterialTheme.typography.labelLarge,color=Muted)
}
