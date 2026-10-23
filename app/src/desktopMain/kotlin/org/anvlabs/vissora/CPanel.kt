package org.anvlabs.vissora

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*

import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Popup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

import org.json.JSONObject
import org.json.JSONArray
import java.nio.ByteBuffer
import androidx.compose.ui.awt.SwingPanel
import java.awt.Canvas
import java.awt.Color as AwtColor
import java.io.File

import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image as SkiaImage
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

val latestFrames = ConcurrentHashMap<String, AtomicReference<ByteArray?>>()
var wsSession: DefaultClientWebSocketSession? = null
var oivData = JSONArray()
data class BoxData(
    val fw: Float,
    val fh: Float,
    val boxes: List<List<Float>>,
    val conf: List<Double>
)
var boxDataState by mutableStateOf(BoxData(1f, 1f, emptyList(), emptyList()))

var streamData by mutableStateOf("""
[
    {
        "name": "cam 1",
        "source": "../assets/videos/video3.mp4",
        "loc": "D.A.V Public School",
        "url": "rtsp://127.0.0.1:8554/camera",
        "detection": false,
        "nv": 0,
        "nf": 0,
        "fps": 60
    },
    {
        "name": "cam 2",
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

class VideoStream {
    var handle: Long = 0L
    companion object {
        init {
            val relativePaths = listOf("app/native/windows/libs", "native/windows/libs")
            val baseDir = relativePaths.map { File(it).absoluteFile }.firstOrNull { it.exists() && File(it, "libmain.dll").exists() }?: File("app/native/windows/libs").absoluteFile
            val dllOrder = listOf(
                "avutil-61.dll",
                "swresample-7.dll",
                "swscale-10.dll",
                "avcodec-63.dll",
                "avformat-63.dll",
                "avfilter-12.dll",
                "avdevice-63.dll",
                "ncnn.dll",
                "libmain.dll"
            )

            for (dllName in dllOrder) {
                val dllFile = File(baseDir, dllName)
                if (dllFile.exists()) {
                    try {
                        System.load(dllFile.absolutePath)
                    } catch (e: UnsatisfiedLinkError) {
                        System.err.println("Failed to load ${dllFile.absolutePath}: ${e.message}")
                        throw e
                    }
                }
            }
        }
    }

    external fun openStream(name: String, url: String): Long
    external fun decodeNextFrame(handle: Long, directBuffer: ByteBuffer): Int
    external fun getWidth(handle: Long): Int
    external fun getHeight(handle: Long): Int
    external fun processYOLO(handle: Long, classes: IntArray)
    external fun closeStream(handle: Long)
}

@Composable
fun FfmpegPlayerView(stream: VideoStream = VideoStream(), cam: String, mediaUrl: String, modifier: Modifier = Modifier, play: Boolean = true) {
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(mediaUrl, play) {
        if (!play) {
            withContext(Dispatchers.Main) {
                imageBitmap = null
            }
            return@LaunchedEffect
        }
        
        withContext(Dispatchers.IO) {
            val handle = stream.openStream(cam, mediaUrl)
            stream.handle = handle

            if (handle == 0L) {
                println("Failed to open FFmpeg stream for: $mediaUrl")
                return@withContext
            }

            val width = stream.getWidth(handle)
            val height = stream.getHeight(handle)
            
            if (width <= 0 || height <= 0) {
                println("Invalid video stream dimensions: ${width}x${height}")
                stream.closeStream(handle)
                return@withContext
            }

            val rowBytes = width * 4 // RGBA stride
            val capacity = rowBytes * height
            val directBuffer = ByteBuffer.allocateDirect(capacity)

            val imageInfo = ImageInfo(
                width = width,
                height = height,
                colorType = ColorType.RGBA_8888,
                alphaType = ColorAlphaType.UNPREMUL
            )

            try {
                while (isActive) {
                    val result = stream.decodeNextFrame(handle, directBuffer)
                    if (result == 0) {
                        val bytes = ByteArray(capacity)
                        directBuffer.position(0)
                        directBuffer.get(bytes)

                        val bitmap = Bitmap().apply {
                            allocPixels(imageInfo)
                            installPixels(bytes)
                            setImmutable()
                        }
                        val composeBitmap = bitmap.asComposeImageBitmap()

                        // Safely post bitmap update to Main thread to prevent concurrent composition crashes
                        withContext(Dispatchers.Main) {
                            imageBitmap = composeBitmap
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
        Image(
            bitmap = imageBitmap!!,
            contentDescription = "FFmpeg Video Stream",
            contentScale = ContentScale.Fit,
            modifier = modifier.background(Color.Black)
        )
    } else {
        Box(modifier = modifier.background(Color(0xff333333))){
            Column(modifier=Modifier.align(Alignment.Center), horizontalAlignment=Alignment.CenterHorizontally){
                Text(text=cam, fontSize=16.sp, color=Color(0xffcccccc))
                Text(text=mediaUrl, fontSize=12.sp, color=Color(0xff999999))
                if(play){
                    Spacer(modifier=Modifier.height(15.dp))
                    Text(text="Please wait...", color=Color.White)
                }
            }
        }
    }
}

fun indexOf(jsonArray: JSONArray, item: String): Int {
    for (i in 0 until jsonArray.length()) {
        if (jsonArray.getString(i) == item) {
            return i
        }
    }
    return -1
}

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

@Suppress("DEPRECATION")
@Composable
fun CPanelWindow() {
    var openNewStreamDialog by remember { mutableStateOf(false) }
    val frameMap = remember {
        mutableStateMapOf<String, ImageBitmap>()
    }
    val InterFont = FontFamily(
        Font(
            resource = "fonts/inter.ttf",
            weight = FontWeight.Normal
        )
    )
    val openSans = FontFamily(
        Font(
            resource = "fonts/open_sans.ttf",
            weight = FontWeight.Normal
        )
    )
    var pw by remember { mutableIntStateOf(0) }
    var ph by remember { mutableIntStateOf(0) }
    var np by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

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
        //println("Loading model: " + native.setupModel(File("../assets/models").getAbsolutePath()))
        oivData = JSONArray(File("../assets/oiv.json").readText())
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
                Text(text="Dashboard", color = Color(0xffffffff), fontSize=30.sp, fontFamily=openSans, fontWeight=FontWeight.Bold)
                Row(horizontalArrangement=Arrangement.spacedBy(0.dp)){
                    Button(modifier=Modifier.wrapContentWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xffcccccc),
                            contentColor = Color(0xff1a1a1a)
                        ),
                        onClick = {
                            classes = intArrayOf()
                            track = true
                            fullscreen = true
                        }
                    ) {
                        Text("Track Objects", fontWeight = FontWeight.Bold, fontFamily=InterFont)
                    }
                }
            }
            Divider(color=Color(0xFF333333), thickness=1.dp, modifier=Modifier.fillMaxWidth().padding(top=5.dp))
            Spacer(modifier=Modifier.height(10.dp))
            Text(text="Below are the list of cameras under inspection. Select any for edits and usage.", color = Color(0xffe2e2e2))
            Spacer(modifier=Modifier.height(50.dp))
            
            LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(25.dp), verticalArrangement = Arrangement.spacedBy(25.dp)){
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
                                    painter = painterResource("icons/add.svg"),
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
                                streams.add(stream)

                                Box(modifier=Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                                    pw = coordinates.size.width
                                    ph = coordinates.size.height
                                }){
                                    FfmpegPlayerView(
                                        stream = stream,
                                        cam = jsonData.getString("name"),
                                        mediaUrl = jsonData.getString("url"),
                                        modifier = Modifier.fillMaxSize(),
                                        play = play
                                    )
                                    Canvas(modifier = Modifier.fillMaxSize()){
                                        val data = boxDataState
                                        val fw = data.fw
                                        val fh = data.fh
                                        
                                        for(i in data.conf.indices){
                                            if(data.conf[i] > 0.3){
                                                val box = data.boxes[i]
                                                drawRect(
                                                    color = Color.Blue,
                                                    topLeft = Offset(
                                                        x = (box[0] / fw) * pw, 
                                                        y = (box[1] / fh) * ph
                                                    ),
                                                    size = Size(
                                                        width = (box[2] - box[0]) * (pw/fw), 
                                                        height = (box[3] - box[1]) * (ph/fh)
                                                    ),
                                                    style = Stroke(width=2f)
                                                )
                                            }
                                        }
                                    }
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
                                                painter = painterResource("icons/car.svg"),
                                                modifier=Modifier.size(15.dp),
                                                tint = Color(0xFF38BDF8),
                                                contentDescription = null
                                            )
                                            Text(text=jsonData.getInt("nv").toString(), color=Color(0xFF38BDF8), fontSize=12.sp, fontFamily=openSans, fontWeight=FontWeight.Bold)
                                        }
                                        Row(modifier=Modifier.width(65.dp).height(35.dp).clip(RoundedCornerShape(10.dp)).padding(start=5.dp, end=5.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(7.dp)){ 
                                            Icon(
                                                painter = painterResource("icons/person.svg"),
                                                modifier=Modifier.size(15.dp),
                                                tint = Color(0xff00cc66),
                                                contentDescription = null
                                            )
                                            Text(text=jsonData.getInt("nf").toString(), color=Color(0xff00cc66), fontSize=12.sp, fontFamily=openSans, fontWeight=FontWeight.Bold)
                                        }
                                    }
                                    Row(horizontalArrangement=Arrangement.spacedBy(-15.dp)){
                                        IconButton(
                                            onClick = { 
                                                play = !play 
                                                parsedData.getJSONObject(index).put("detection", play)
                                                streamData = parsedData.toString()
                                                scope.launch {
                                                    wsSession?.send(Frame.Text(streamData))
                                                }
                                            },
                                            colors = IconButtonDefaults.iconButtonColors(
                                                containerColor = Color.Transparent, 
                                                contentColor = Color.White
                                            )
                                        ) {
                                            Icon(
                                                painter = if (play) painterResource("icons/pause.svg") else painterResource("icons/play.svg"),
                                                modifier = Modifier.size(17.dp),
                                                tint = Color.White,
                                                contentDescription = null
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                //stream.processYOLO(stream.handle)
                                            },
                                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent, contentColor = Color.White)
                                        ) {
                                            Icon(
                                                painter = painterResource("icons/power.svg"),
                                                modifier=Modifier.size(17.dp),
                                                tint = Color.White,
                                                contentDescription = null
                                            )
                                        }
                                        IconButton(
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
                                            },
                                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent, contentColor = Color.White)
                                        ) {
                                            Icon(
                                                painter = painterResource("icons/full_screen.svg"),
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

        if(fullscreen){
            var processing by remember { mutableStateOf(false) }
            var installed by remember { mutableStateOf(false) }
            var job by remember { mutableStateOf<Job?>(null) }
            var fc by remember { mutableStateOf(0) } 
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { fullscreen = false },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .width(if(track) rootMaxWidth * 0.6f else rootMaxWidth * 0.8f)   // Exactly 80% of window width
                        .height(rootMaxHeight * 0.8f) // Exactly 80% of window height
                        .clickable(enabled = false, onClick = {}), // Prevent click propagation
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    if(!track){
                        Row(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            FfmpegPlayerView(
                                stream = globalStream,
                                cam = globalCam,
                                mediaUrl = globalURL,
                                modifier = Modifier.fillMaxSize(),
                                play = globalPlay
                            )
                        }
                    }
                    else if(processing && fc == np){
                        job?.cancel()
                        job = null
                        val images = File("images").listFiles()
                        Column(modifier=Modifier.fillMaxSize().background(Color(0xff333333)).padding(20.dp), verticalArrangement=Arrangement.spacedBy(20.dp)){
                            Row(horizontalArrangement=Arrangement.spacedBy(20.dp), verticalAlignment=Alignment.CenterVertically){
                                Button(modifier=Modifier.wrapContentWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFcccccc),
                                        contentColor = Color.Black
                                    ),
                                    onClick = {
                                        processing = false
                                    }
                                ) {
                                    Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                        Icon(
                                            painter = painterResource("icons/left.svg"),
                                            modifier=Modifier.size(20.dp),
                                            contentDescription = null
                                        )
                                        Text("Back", fontWeight = FontWeight.Bold, fontFamily=InterFont)
                                    }
                                }
                            }
                            LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(15.dp), verticalArrangement = Arrangement.spacedBy(15.dp)){
                                items(fc){ index ->
                                    val bitmap = java.nio.file.Files.newInputStream(images[index].toPath()).use { stream ->
                                        androidx.compose.ui.res.loadImageBitmap(stream)
                                    }
                                    val txt = images[index].getName().split("@")
                                    Column(horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(20.dp), modifier=Modifier.clip(RoundedCornerShape(20.dp))){
                                        Image(
                                            bitmap = bitmap,
                                            contentDescription = "Loaded from local",
                                            contentScale = ContentScale.Fit,
                                            modifier=Modifier.fillMaxSize().clip(RoundedCornerShape(15.dp)).background(Color.Black).aspectRatio(1f)
                                        )
                                        Text("${txt[0]}, ${txt[1]} entities", fontSize=14.sp, fontFamily=openSans)
                                    }
                                }
                            }
                        }
                    }
                    else{
                        val filteredItems = (0 until oivData.length())
                        .map { index -> oivData.getString(index) }
                        .filter {
                            it.contains(searchClass, ignoreCase = true) &&
                            (!installed || classes.contains(indexOf(oivData, it)))
                        }
                        
                        Column(modifier=Modifier.fillMaxSize().background(Color(0xff333333))){
                            Column(modifier=Modifier.fillMaxWidth().fillMaxHeight(.88f)){
                                TextField(
                                    value = searchClass,
                                    onValueChange = { searchClass = it },
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    placeholder = { Text("Search objects", color = Color.Gray, fontFamily=InterFont) },
                                    singleLine = true,
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource("icons/search.svg"),
                                            contentDescription = "Search",
                                            tint = Color.Gray
                                        )
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            colors = IconButtonDefaults.iconButtonColors(
                                                containerColor = Color.Transparent,
                                                contentColor = Color(0xff999999)
                                            ),
                                            onClick = {
                                                installed = !installed
                                            }
                                        ) {
                                            Icon(
                                                painter = if(installed) painterResource("icons/check_filled.svg") else painterResource("icons/done.svg"),
                                                contentDescription = "installed",
                                                tint = Color(0xff999999)
                                            )
                                        }
                                    },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        disabledContainerColor = Color.Transparent,
                                        errorContainerColor = Color.Transparent,
                                        
                                        // Bottom line colors
                                        focusedIndicatorColor = Color(0xFFcccccc), // Your theme's blue highlight
                                        unfocusedIndicatorColor = Color.DarkGray,
                                        
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    )
                                )
                                LazyColumn(modifier=Modifier.fillMaxWidth().weight(1f)){
                                    items(filteredItems.size){ index ->
                                        val cls = indexOf(oivData, filteredItems[index])
                                        Row(modifier=Modifier.fillMaxWidth().padding(start=20.dp, end=20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement=Arrangement.SpaceBetween){
                                            Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(20.dp)){
                                                val className = filteredItems[index]
                                                Icon(
                                                    painter = painterResource("icons/info.svg"),
                                                    contentDescription = "info",
                                                    tint = Color(0xff999999)
                                                )
                                                Text(text=className, color=Color(0xff999999), fontSize=12.sp, fontFamily=openSans)
                                            }
                                            IconButton(
                                                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent, contentColor = Color(0xFFcccccc)),
                                                onClick = { 
                                                    if(!classes.contains(cls)){
                                                        classes = classes + cls
                                                    }
                                                    else{
                                                        classes = classes.filter { it != cls }.toIntArray()
                                                    }
                                                }
                                            ){
                                                Icon(
                                                    painter = if(classes.contains(cls)) painterResource("icons/check_circle.svg") else painterResource("icons/circle.svg"),
                                                    modifier=Modifier.size(20.dp),
                                                    contentDescription = null
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Row(modifier=Modifier.fillMaxWidth().weight(1f).padding(20.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.SpaceBetween){
                                Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(20.dp)){
                                    if(processing){
                                        CircularProgressIndicator(color=Color.White, strokeWidth = 3.dp, modifier = Modifier.size(20.dp))
                                        Text(text="Collecting results..", color=Color(0xffcccccc), fontSize=14.sp, fontFamily=InterFont)
                                    }
                                }
                                Row(modifier=Modifier, verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.End){
                                    TextButton(
                                        onClick = {
                                            track = false
                                            fullscreen = false
                                        }
                                    ) {
                                        Text(text="Cancel", fontFamily=InterFont, color=Color.White)
                                    }
                                    Spacer(modifier=Modifier.width(20.dp))
                                    TextButton(
                                        onClick = {
                                            processing = true
                                            val folder = File("images")
                                            folder.listFiles()?.forEach { file ->
                                                file.deleteRecursively()
                                            }
                                            streams.forEach { stream ->
                                                stream.processYOLO(stream.handle, classes)
                                            }
                                            job = scope.launch {
                                                repeat(10) {
                                                    fc = File("images").listFiles().size
                                                    delay(1000)
                                                }
                                            }
                                        }
                                    ) {
                                        Text(text="Search", fontFamily=InterFont, color=Color.White)
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