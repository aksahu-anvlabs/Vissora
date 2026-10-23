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

import java.io.File
import java.util.zip.ZipInputStream
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    init {
        System.loadLibrary("main")
    }
    external fun setupModel(path: String): String
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

fun Brush.Companion.angledGradient(colors: List<Color>, angleInDegrees: Float, size: Size): Brush {
    val angleRad = (angleInDegrees * (PI / 180f)).toFloat()

    val centerX = size.width / 2f
    val centerY = size.height / 2f

    val x = cos(angleRad)
    val y = sin(angleRad)

    val maxRadius = kotlin.math.hypot(centerX, centerY)

    val start = Offset(x = centerX - x * maxRadius, y = centerY - y * maxRadius)
    val end = Offset(x = centerX + x * maxRadius, y = centerY + y * maxRadius)

    return Brush.linearGradient(colors = colors, start = start, end = end)
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
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

    val context = LocalContext.current
    var index by remember { mutableStateOf(0) }
    var btnData = listOf("CPanel", "Reverse Tracer", "Embeddings", "Settings", "Close")
    var iconData = listOf(painterResource(R.drawable.record), painterResource(R.drawable.tracker), painterResource(R.drawable.code), painterResource(R.drawable.cog), painterResource(R.drawable.close))
    val defaultPadding: PaddingValues = ButtonDefaults.ContentPadding

    LaunchedEffect(Unit){
        if(!File(context.externalMediaDirs[0], "assets").exists()){
            val destination = context.externalMediaDirs[0]
            try {
                destination.mkdirs()

                context.assets.open("assets.zip").use { input ->
                    ZipInputStream(input).use { zip ->
                        var entry = zip.nextEntry

                        while (entry != null) {
                            val outputFile = File(destination, entry.name)

                            // Prevent ZIP path traversal attacks.
                            val canonicalDestination = destination.canonicalPath + File.separator
                            if (!outputFile.canonicalPath.startsWith(canonicalDestination)) {
                                throw SecurityException("Invalid ZIP entry: ${entry.name}")
                            }

                            if (entry.isDirectory) {
                                outputFile.mkdirs()
                            } else {
                                outputFile.parentFile?.mkdirs()
                                outputFile.outputStream().use { output ->
                                    zip.copyTo(output)
                                }
                            }

                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }

                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
        val activity = context as? MainActivity
        activity?.setupModel(File(context.externalMediaDirs[0], "assets/models").absolutePath)
    }

    MaterialTheme(
        colorScheme = darkColorScheme()
    ) {

        Row(
            modifier = Modifier.fillMaxSize().background(Color(0xff0a0a0a)),
            horizontalArrangement = Arrangement.Start
        ) {
            Column(
                modifier = Modifier.fillMaxHeight().padding(20.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1d1d1d)).weight(0.35f)){
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier=Modifier.fillMaxSize().padding(20.dp)){
                    Spacer(modifier=Modifier.height(20.dp))
                    Column(modifier=Modifier.fillMaxWidth().padding(defaultPadding), verticalArrangement=Arrangement.spacedBy(20.dp), horizontalAlignment=Alignment.Start){
                        Box(modifier=Modifier.size(45.dp).clip(RoundedCornerShape(12.dp)).drawWithCache{
                            val gradient = Brush.angledGradient(
                                colors = listOf(
                                    Color(0xFF38BDF8),
                                    Color(0xFF00CC66)
                                ),
                                angleInDegrees = 45f,
                                size = size
                            )
                            onDrawBehind {
                                drawRect(brush = gradient)
                            }
                        }){
                            Icon(
                                painter = painterResource(R.drawable.logo),
                                modifier=Modifier.size(40.dp).align(Alignment.Center),
                                tint = Color.Black,
                                contentDescription = null
                            )
                        }
                        Column(verticalArrangement=Arrangement.spacedBy(0.dp)){
                            Text(text="Vissora TV", fontSize=13.5.sp, color=Color(0xffcccccc), fontFamily = openSans, fontWeight=FontWeight.Bold)
                            Text(text="v1.1.3", color=Color(0xff999999), fontFamily = InterFont, fontSize=11.5.sp)
                        }
                    }
                    Spacer(modifier=Modifier.height(30.dp))
                    for(i in 0 until btnData.size){
                        if(i == 3){
                            Divider(color = Color(0xff333333), thickness = 1.dp, modifier=Modifier.fillMaxWidth().padding(defaultPadding))
                        }
                        Button(modifier=Modifier.widthIn(min=200.dp, max=300.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = if(i == index) Color.White else Color(0xff999999)
                            ),
                            onClick = {
                                index = i
                            }
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(20.dp)){
                                Icon(
                                    painter = iconData[i],
                                    modifier=Modifier.size(20.dp),
                                    tint = if(i == index) Color.White else Color(0xff999999),
                                    contentDescription = null
                                )
                                Text(btnData[i], fontWeight=FontWeight.Normal, fontFamily=InterFont, fontSize=11.sp)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.width(15.dp))
            Column(modifier = Modifier.weight(0.65f)) {
                if(index == 0){
                    CPanelWindow()
                }
                else if(index == 1){
                    AccessWindow()
                }
                else if(index == 2){
                    EmbWindow()
                }
                else if(index == 4){
                    (context as? android.app.Activity)?.finish()
                }
            }
        }
    }
}