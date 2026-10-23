package org.anvlabs.vissora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import android.content.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.platform.LocalContext
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.interaction.MutableInteractionSource
import android.view.TextureView
import android.graphics.Bitmap
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextOverflow

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView

val latestFrames = ConcurrentHashMap<String, AtomicReference<ByteArray?>>()
var wsSession: DefaultClientWebSocketSession? = null
var oivData = JSONArray()
var streamData by mutableStateOf("""
[
    {
        "name": "cam 1",
        "source": "../assets/videos/video3.mp4",
        "loc": "D.A.V Public School",
        "url": "rtsp://192.168.29.145:8554/camera",
        "detection": false,
        "nv": 0,
        "nf": 0,
        "fps": 60
    },
    {
        "name": "cam 2",
        "source": "../assets/videos/video2.mp4",
        "loc": "D.A.V Public School",
        "url": "rtsp://rtsp.rtsplink.com/live/people?token=d3fd34335e2fdedc389f54e8bd8dfe7a5362aa36733fa007ae912f9dcc949709",
        "stream": true,
        "detection": false,
        "nv": 0,
        "nf": 0,
        "fps": 60
    },
    {
        "name": "cam 3",
        "source": "../assets/videos/video2.mp4",
        "loc": "D.A.V Public School",
        "url": "rtsp://127.0.0.1:8554/camera",
        "stream": true,
        "detection": false,
        "nv": 0,
        "nf": 0,
        "fps": 60
    },
    {
        "name": "cam 4",
        "source": "../assets/videos/video2.mp4",
        "loc": "D.A.V Public School",
        "url": "rtsp://127.0.0.1:8554/camera",
        "stream": true,
        "detection": false,
        "nv": 0,
        "nf": 0,
        "fps": 60
    }
]
""")

fun indexOf(jsonArray: JSONArray, item: String): Int {
    for (i in 0 until jsonArray.length()) {
        if (jsonArray.getString(i) == item) {
            return i
        }
    }
    return -1
}

fun showToast(context: Context, mes: String) {
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(context, mes, Toast.LENGTH_SHORT).show()
    }
}

lateinit var assetsDir: File

suspend fun startServer(onConnected: suspend (DefaultClientWebSocketSession) -> Unit) {
    val client = HttpClient(CIO) { install(WebSockets) }
    try {
        client.webSocket(host = "127.0.0.1", port = 8080, path = "/ws") {
            onConnected(this)
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val response = JSONObject(frame.readText())
                        val parsedData = JSONArray(streamData)
                        withContext(Dispatchers.IO) {
                            for(i in 0 until parsedData.length()){
                                if(parsedData.getJSONObject(i).getString("name") == response.getString("n") && parsedData.getJSONObject(i).getBoolean("detection")){
                                    print("received")
                                    parsedData.getJSONObject(i).put("nv", response.getInt("nv"))
                                    parsedData.getJSONObject(i).put("nf", response.getInt("nf"))
                                    break
                                }
                            }
                            streamData = parsedData.toString()
                        }
                    }
                    else -> {}
                }
            }
        }
    } catch (e: Exception) {
        println(e)
    } finally {
        client.close()
    }
}

class VideoStream {
    var handle: Long = 0L
    var name: String = ""
    var inference: Boolean = false
    external fun registerStream(name: String, width: Int, height: Int, channels: Int): Long
    external fun scanFaces(id: Long, buffer: ByteBuffer, embPath: String): Boolean
    external fun openStream(name: String, url: String): Long
    external fun decodeNextFrame(handle: Long, directBuffer: ByteBuffer, embPath: String, assetPath: String): Int
    external fun getWidth(handle: Long): Int
    external fun getHeight(handle: Long): Int
    external fun processYOLO(handle: Long, classes: IntArray)
    external fun closeStream(handle: Long)
}

@Composable
fun FfmpegPlayerView(stream: VideoStream = VideoStream(), cam: String, mediaUrl: String, modifier: Modifier = Modifier, play: Boolean = true) {
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var frameTrigger by remember { mutableIntStateOf(0) } // State to force UI redraws

    LaunchedEffect(mediaUrl, play) {
        if (!play) {
            withContext(Dispatchers.Main) { imageBitmap = null }
            return@LaunchedEffect
        }
        
        withContext(Dispatchers.IO) {
            val handle = stream.openStream(cam, mediaUrl)
            stream.handle = handle

            if (handle == 0L) return@withContext

            val width = stream.getWidth(handle)
            val height = stream.getHeight(handle)
            
            if (width <= 0 || height <= 0) {
                stream.closeStream(handle)
                return@withContext
            }

            val capacity = width * height * 4
            val directBuffer = ByteBuffer.allocateDirect(capacity)

            // 1. ALLOCATE ONCE outside the loop to stop GC thrashing
            val reusableBitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val composeBitmap = reusableBitmap.asImageBitmap() // Share memory pointer
            
            withContext(Dispatchers.Main) {
                imageBitmap = composeBitmap
            }

            try {
                while (isActive) {
                    val result = stream.decodeNextFrame(handle, directBuffer, File(assetsDir, "faces/emb").absolutePath, assetsDir.absolutePath)
                        if (result == 0) {
                            directBuffer.position(0)
                            
                            // Overwrite memory
                            reusableBitmap.copyPixelsFromBuffer(directBuffer)
                            
                            // MUST be on Main thread, otherwise Compose ignores the redraw
                            withContext(Dispatchers.Main) {
                                frameTrigger++ 
                            }
                        } else {
                            delay(5)
                        }
                }
            } finally {
                stream.closeStream(handle)
            }
        }
    }

    if (imageBitmap != null && play) {
        val currentFrame = frameTrigger // Read state
        
        Canvas(modifier = modifier.background(Color.Black)) {
            currentFrame.hashCode() // Tell Compose to track this state for redraws

            // Calculate Fit Scaling manually
            val scale = minOf(size.width / imageBitmap!!.width, size.height / imageBitmap!!.height)
            val dstW = (imageBitmap!!.width * scale).roundToInt()
            val dstH = (imageBitmap!!.height * scale).roundToInt()
            val dstX = ((size.width - dstW) / 2).roundToInt()
            val dstY = ((size.height - dstH) / 2).roundToInt()

            drawImage(
                image = imageBitmap!!,
                dstOffset = IntOffset(dstX, dstY),
                dstSize = IntSize(dstW, dstH),
                filterQuality = FilterQuality.Low // Fixes pixelated edges in Compose
            )
        }
    } else {
        Box(modifier = modifier.background(Color(0xff333333))){
            Column(modifier=Modifier.align(Alignment.Center), horizontalAlignment=Alignment.CenterHorizontally){
                Text(text=cam, fontSize=13.sp, color=Color(0xffcccccc))
                BoxWithConstraints {
                    Text(
                        text = mediaUrl,
                        fontSize = 10.sp,
                        color = Color(0xff999999),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = maxWidth * 0.8f)
                    )
                }
                if(play){
                    Spacer(modifier=Modifier.height(15.dp))
                    Text(text="Please wait...", color=Color.White, fontSize=10.sp)
                }
            }
        }
    }
}

@Composable
fun TvFocusableIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    defaultContentColor: Color = Color.White,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isActive = isFocused || isHovered

    IconButton(
        onClick = onClick,
        modifier = modifier,
        interactionSource = interactionSource,
        colors = IconButtonDefaults.iconButtonColors(
            // Change this to your preferred highlight color
            containerColor = if (isActive) Color.White.copy(alpha = 0.3f) else Color.Transparent,
            contentColor = defaultContentColor
        )
    ) {
        content()
    }
}

@Composable
fun CPanelWindow(){
	var openNewStreamDialog by remember { mutableStateOf(false) }
    val frameMap = remember {
        mutableStateMapOf<String, ImageBitmap>()
    }
    val InterFont = FontFamily(
        Font(
            resId = R.font.inter,
            weight = FontWeight.Normal
        )
    )
    val openSans = FontFamily(
        Font(
            resId = R.font.open_sans,
            weight = FontWeight.Normal
        )
    )
    var pw by remember { mutableIntStateOf(0) }
    var ph by remember { mutableIntStateOf(0) }
    var np by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    var classes by remember {mutableStateOf(intArrayOf())}
    var searchClass by remember { mutableStateOf("") }
    var fullscreen by remember { mutableStateOf(false) }
    var track by remember { mutableStateOf(false) }
    var globalStream by remember { mutableStateOf(VideoStream()) }
    val streams = remember { mutableListOf<VideoStream>() }
    var globalCam by remember { mutableStateOf("") }
    var globalURL by remember { mutableStateOf("") }
    var globalPlay by remember { mutableStateOf(true) }

    // Parse streamData ONLY when it actually changes, not on every recomposition
    val parsedData = remember(streamData) { JSONArray(streamData) }
    LaunchedEffect(Unit) {
        val mediaDirs = context.externalMediaDirs
        val baseDir = mediaDirs.firstOrNull() ?: context.filesDir
        assetsDir = File(baseDir, "assets").apply { mkdirs() }
        //println("Loading model: " + native.setupModel(File("../assets/models").getAbsolutePath()))
        oivData = JSONArray(File(baseDir, "assets/oiv.json").readText())
        scope.launch {
            startServer { ctx -> 
                wsSession = ctx
                ctx.send(Frame.Text(streamData))
            }
        }
    }

    LaunchedEffect(parsedData) {
        np = 0
        for(i in 0 until parsedData.length()){
            val obj = parsedData.getJSONObject(i)
            latestFrames[obj.getString("name")] = AtomicReference(null)
            if(obj.getBoolean("detection")){
                np += 1
            }
        }
    }

    // 1. Wrap everything in BoxWithConstraints to measure exact desktop window bounds
    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(top=30.dp, end=20.dp)) {
        val rootMaxWidth = maxWidth
        val rootMaxHeight = maxHeight

        Column(modifier = Modifier.fillMaxSize().padding(15.dp)){
            Row(modifier=Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                Text(text="Dashboard", color = Color(0xffffffff), fontSize=22.sp, fontFamily=openSans, fontWeight=FontWeight.Bold)
                Row(horizontalArrangement=Arrangement.spacedBy(0.dp)){
                    val interactionSource = remember { MutableInteractionSource() }
                    val isFocused by interactionSource.collectIsFocusedAsState()
                    val isHovered by interactionSource.collectIsHoveredAsState()
                    val isActive = isFocused || isHovered

                    Button(
                        modifier = Modifier.wrapContentWidth(),
                        interactionSource = interactionSource, // Attach the interaction source
                        colors = ButtonDefaults.buttonColors(
                            // Swap colors based on focus/hover state
                            containerColor = if (isActive) Color.White else Color(0xffcccccc),
                            contentColor = Color(0xff1a1a1a)
                        ),
                        onClick = {
                            classes = intArrayOf()
                            track = true
                            fullscreen = true
                        }
                    ) {
                        Text("Track Objects", fontWeight = FontWeight.Bold, fontFamily = InterFont)
                    }
                }
            }
            Divider(color=Color(0xFF333333), thickness=1.dp, modifier=Modifier.fillMaxWidth().padding(top=5.dp))
            Spacer(modifier=Modifier.height(10.dp))
            Text(text="Below are the list of cameras under inspection. Select any for edits and usage.", color = Color(0xffe2e2e2), fontSize=11.sp)
            Spacer(modifier=Modifier.height(20.dp))
            
            LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(25.dp), verticalArrangement = Arrangement.spacedBy(25.dp)){
                items(parsedData.length() + 1){ index ->
                    val emptyCell = index >= parsedData.length()
                    var jsonData = JSONObject()
                    if(!emptyCell){
                        jsonData = parsedData.getJSONObject(index)
                    }
                    Column(modifier=Modifier.clip(RoundedCornerShape(12.dp))){
                        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f/0.65f)){
                            if(emptyCell){
                                Icon(
                                    painter = painterResource(R.drawable.add),
                                    modifier=Modifier.size(30.dp).align(Alignment.Center),
                                    tint = Color(0xffcccccc),
                                    contentDescription = null
                                )
                            } else {
                                val infiniteTransition = rememberInfiniteTransition(label = "infinite opacity transition")
                                val alphaAnim by infiniteTransition.animateFloat(
                                    initialValue = 0f,
                                    targetValue = 1f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(durationMillis = 1000, easing = LinearEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "alpha animation"
                                )
                                var play by remember { mutableStateOf(false) }
                                var captured by remember { mutableStateOf(false) }
                                val stream = remember { VideoStream() }
                                stream.name = jsonData.getString("name")
                                streams.add(stream)

                                Box(modifier=Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                                    pw = coordinates.size.width
                                    ph = coordinates.size.height
                                }){
                                    FfmpegPlayerView(
                                        stream=stream,
                                        cam = jsonData.getString("name"),
                                        mediaUrl = jsonData.getString("url"),
                                        modifier = Modifier.fillMaxSize(),
                                        play = play
                                    )
                                }
                            Row(modifier=Modifier.wrapContentWidth().align(Alignment.TopStart).padding(start=10.dp, top=5.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                                    Text(modifier=Modifier.weight(0.3f), text=jsonData.getInt("fps").toString(), color=Color(0xffccffcc), fontFamily=InterFont, fontSize=13.sp, fontWeight=FontWeight.Medium)
                                }
                                if(play){
                                    Canvas(modifier=Modifier.size(30.dp).graphicsLayer(alpha = alphaAnim).align(Alignment.TopEnd).padding(5.dp)){
                                        drawCircle(radius = 5.dp.toPx(), color=Color.Red)
                                    }
                                }
                                Row(modifier=Modifier.fillMaxWidth().background(Color.Black.copy(alpha=0.7f)).align(Alignment.BottomCenter).padding(start=10.dp, end=10.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.SpaceBetween){
                                    Row(horizontalArrangement=Arrangement.spacedBy(-15.dp)){
                                        Row(modifier=Modifier.width(65.dp).height(35.dp).clip(RoundedCornerShape(10.dp)).padding(start=5.dp, end=5.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(7.dp)){ 
                                            Icon(
                                                painter = painterResource(R.drawable.car),
                                                modifier=Modifier.size(15.dp),
                                                tint = Color(0xFF38BDF8),
                                                contentDescription = null
                                            )
                                            Text(text=jsonData.getInt("nv").toString(), color=Color(0xFF38BDF8), fontSize=12.sp, fontFamily=openSans, fontWeight=FontWeight.Bold)
                                        }
                                        Row(modifier=Modifier.width(65.dp).height(35.dp).clip(RoundedCornerShape(10.dp)).padding(start=5.dp, end=5.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(7.dp)){ 
                                            Icon(
                                                painter = painterResource(R.drawable.person),
                                                modifier=Modifier.size(15.dp),
                                                tint = Color(0xff00cc66),
                                                contentDescription = null
                                            )
                                            Text(text=jsonData.getInt("nf").toString(), color=Color(0xff00cc66), fontSize=12.sp, fontFamily=openSans, fontWeight=FontWeight.Bold)
                                        }
                                    }
                                    Row(horizontalArrangement=Arrangement.spacedBy(-15.dp)){
                                        TvFocusableIconButton(
                                            onClick = { 
                                                play = !play 
                                                parsedData.getJSONObject(index).put("detection", play)
                                                streamData = parsedData.toString()
                                                scope.launch {
                                                    wsSession?.send(Frame.Text(streamData))
                                                }
                                            }
                                           
                                        ) {
                                            Icon(
                                                painter = if (play) painterResource(R.drawable.pause) else painterResource(R.drawable.play),
                                                modifier = Modifier.size(17.dp),
                                                tint = Color.White,
                                                contentDescription = null
                                            )
                                        }
                                        TvFocusableIconButton(
                                            onClick = {
                                                //stream.processYOLO(stream.handle)
                                            }
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.online),
                                                modifier=Modifier.size(17.dp),
                                                tint = Color.White,
                                                contentDescription = null
                                            )
                                        }
                                        TvFocusableIconButton(
                                            onClick = {
                                                play = false
                                                parsedData.getJSONObject(index).put("detection", play)
                                                streamData = parsedData.toString()
                                                scope.launch {
                                                    wsSession?.send(Frame.Text(streamData))
                                                }
                                                fullscreen = !fullscreen
                                                globalStream = stream
                                                globalCam = jsonData.getString("name")
                                                globalURL = jsonData.getString("url")
                                                globalPlay = true
                                            }
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.full_screen),
                                                modifier=Modifier.size(17.dp),
                                                tint = Color.White,
                                                contentDescription = null
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (fullscreen) {
            var processing by remember { mutableStateOf(false) }
            var installed by remember { mutableStateOf(false) }
            var job by remember { mutableStateOf<Job?>(null) }
            var fc by remember { mutableStateOf(0) }

            // 1. Use actual Dialog window to automatically trap D-pad focus
            Dialog(
                onDismissRequest = { fullscreen = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier
                        .width(if (track) rootMaxWidth * 0.6f else rootMaxWidth * 0.8f)
                        .height(rootMaxHeight * 0.8f),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    if (!track) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            FfmpegPlayerView(
                                stream = globalStream,
                                cam = globalCam,
                                mediaUrl = globalURL,
                                modifier = Modifier.fillMaxSize(),
                                play = globalPlay
                            )
                        }
                    } else if (processing && fc == np) {
                        job?.cancel()
                        job = null
                        val images = File(assetsDir, "images").listFiles() ?: emptyArray()
                        Column(
                            modifier = Modifier.fillMaxSize().background(Color(0xff333333)).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val backInteraction = remember { MutableInteractionSource() }
                                val backActive = backInteraction.collectIsFocusedAsState().value || backInteraction.collectIsHoveredAsState().value
                                Button(
                                    modifier = Modifier.wrapContentWidth(),
                                    interactionSource = backInteraction,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (backActive) Color.White else Color(0xFFcccccc),
                                        contentColor = Color.Black
                                    ),
                                    onClick = { processing = false }
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(painter = painterResource(R.drawable.left), modifier = Modifier.size(20.dp), contentDescription = null)
                                        Text("Back", fontWeight = FontWeight.Bold, fontFamily = InterFont)
                                    }
                                }
                            }
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(15.dp),
                                verticalArrangement = Arrangement.spacedBy(15.dp)
                            ) {
                                items(fc) { index ->
                                    val bitmap = java.io.FileInputStream(images[index]).use { stream ->
                                        BitmapFactory.decodeStream(stream).asImageBitmap()
                                    }
                                    val txt = images[index].name.split("@")
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.clip(RoundedCornerShape(20.dp))) {
                                        Image(
                                            bitmap = bitmap,
                                            contentDescription = "Loaded from local",
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(15.dp)).background(Color.Black).aspectRatio(1f)
                                        )
                                        Text("${txt[0]}, ${txt[1]} entities", fontSize = 14.sp, fontFamily = openSans)
                                    }
                                }
                            }
                        }
                    } else {
                        val filteredItems = (0 until oivData.length())
                            .map { index -> oivData.getString(index) }
                            .filter {
                                it.contains(searchClass, ignoreCase = true) &&
                                (!installed || classes.contains(indexOf(oivData, it)))
                            }

                        Column(modifier = Modifier.fillMaxSize().background(Color(0xff333333))) {
                            // 2. Give the main content weight(1f) instead of hardcoded .88f height
                            Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                TextField(
                                    value = searchClass,
                                    onValueChange = { searchClass = it },
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    placeholder = { Text("Search objects", color = Color.Gray, fontFamily = InterFont, fontSize = 15.sp) },
                                    singleLine = true,
                                    leadingIcon = {
                                        Icon(painter = painterResource(R.drawable.search), contentDescription = "Search", tint = Color.Gray)
                                    },
                                    trailingIcon = {
                                        TvFocusableIconButton(onClick = { installed = !installed }) {
                                            Icon(
                                                painter = if (installed) painterResource(R.drawable.check) else painterResource(R.drawable.done),
                                                contentDescription = "installed",
                                                tint = Color(0xff999999)
                                            )
                                        }
                                    },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                        disabledContainerColor = Color.Transparent, errorContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color(0xFFcccccc), unfocusedIndicatorColor = Color.DarkGray,
                                        focusedTextColor = Color.White, unfocusedTextColor = Color.White
                                    )
                                )
                                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                    items(filteredItems.size) { index ->
                                        val cls = indexOf(oivData, filteredItems[index])
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                val className = filteredItems[index]
                                                Icon(painter = painterResource(R.drawable.info), contentDescription = "info", tint = Color(0xff999999))
                                                Text(text = className, color = Color(0xff999999), fontSize = 11.sp, fontFamily = openSans)
                                            }
                                            TvFocusableIconButton(
                                                onClick = {
                                                    classes = if (!classes.contains(cls)) classes + cls else classes.filter { it != cls }.toIntArray()
                                                }
                                            ) {
                                                Icon(
                                                    painter = if (classes.contains(cls)) painterResource(R.drawable.check_circle) else painterResource(R.drawable.circle),
                                                    modifier = Modifier.size(20.dp),
                                                    contentDescription = null
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // 3. Give the bottom row wrapContentHeight() and add focus states for TextButtons
                            Row(
                                modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    if (processing) {
                                        CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(20.dp))
                                        Text(text = "Collecting results..", color = Color(0xffcccccc), fontSize = 14.sp, fontFamily = InterFont)
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                                    val cancelInteraction = remember { MutableInteractionSource() }
                                    val cancelActive = cancelInteraction.collectIsFocusedAsState().value || cancelInteraction.collectIsHoveredAsState().value
                                    TextButton(
                                        onClick = { track = false; fullscreen = false },
                                        interactionSource = cancelInteraction,
                                        colors = ButtonDefaults.textButtonColors(containerColor = if (cancelActive) Color.White.copy(alpha = 0.2f) else Color.Transparent)
                                    ) {
                                        Text(text = "Cancel", fontFamily = InterFont, color = Color.White)
                                    }
                                    Spacer(modifier = Modifier.width(20.dp))
                                    
                                    val searchInteraction = remember { MutableInteractionSource() }
                                    val searchActive = searchInteraction.collectIsFocusedAsState().value || searchInteraction.collectIsHoveredAsState().value
                                    if (!processing) {
                                        TextButton(
                                            onClick = {
                                                processing = true
                                                streams.forEach { stream ->
                                                    stream.processYOLO(stream.handle, classes)
                                                }
                                                val folder = File(assetsDir, "images")
                                                folder.mkdirs()
                                                folder.listFiles()?.forEach { file -> file.deleteRecursively() }
                                                job = scope.launch {
                                                    repeat(10) {
                                                        fc = folder.listFiles()?.size ?: 0
                                                        delay(1000)
                                                    }
                                                }
                                            },
                                            interactionSource = searchInteraction,
                                            colors = ButtonDefaults.textButtonColors(containerColor = if (searchActive) Color.White.copy(alpha = 0.2f) else Color.Transparent)
                                        ) {
                                            Text(text = "Search", fontFamily = InterFont, color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}