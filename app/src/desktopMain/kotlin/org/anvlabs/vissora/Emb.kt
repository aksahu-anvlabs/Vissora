package org.anvlabs.vissora

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*

import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size

import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import org.json.JSONObject
import org.json.JSONArray
import java.nio.ByteBuffer
import java.io.File

import kotlin.io.path.outputStream
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive

class NExecutor{
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
    external fun setupModel(path: String): String
    external fun genEmb(path: String, out: String): Int
}

@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbWindow(){
    val InterFont = FontFamily(Font(resource = "fonts/inter.ttf", weight = FontWeight.Normal))
    val openSans = FontFamily(Font(resource = "fonts/open_sans.ttf", weight = FontWeight.Normal))
    var name by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(-1) }
    var versionCounter by remember { mutableStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    var faceDialog by remember { mutableStateOf(false) }
    var editDialog by remember { mutableStateOf(false) }
    var delDialog by remember { mutableStateOf(false) }
    var faceData by remember { mutableStateOf(JSONArray()) }
    val native by remember { mutableStateOf(NExecutor()) }
    var scope = rememberCoroutineScope()
    var person by remember { mutableStateOf(File("../assets/faces/raw").listFiles()) }

    LaunchedEffect(Unit){
    	//faceData = JSONArray(File("../assets/faces.json").readText())
        println("Loading model: " + native.setupModel(File("../assets/models").getAbsolutePath()))
        //println("Gen emb: " + native.genEmb(File("../assets/faces/raw/modi.png").getAbsolutePath(), File("../assets/faces/emb/modi").getAbsolutePath()))
    }

    if(editDialog || delDialog || faceDialog){
    	AlertDialog(
            onDismissRequest = { 
                editDialog = false 
                delDialog = false
                faceDialog = false
            },
            icon = {
                Icon(painter = painterResource("icons/info.svg"), contentDescription = "edit")
            },
            title = {
                Text(text = if(faceDialog) "Add Face" else if(editDialog) "Rename face" else "Delete Face", fontFamily=openSans)
            },
            text = {
            	if(editDialog || faceDialog){
	                OutlinedTextField(
				        value = if(editDialog) rename else name,
				        onValueChange = { if(editDialog) rename = it else name = it },
				        label = { Text("Name") },
				        modifier = Modifier,
				        colors = OutlinedTextFieldDefaults.colors(
				            focusedBorderColor = Color(0xFFcccccc),
				            unfocusedBorderColor = Color(0xFFcccccc), 
				            focusedLabelColor = Color(0xFFcccccc),
				            unfocusedLabelColor = Color(0xFFcccccc),
				            cursorColor = Color(0xFFcccccc)
				        )
				    )
				}
				else{
					Text("Are you sure you want to remove $name ?", fontFamily=InterFont, fontSize=14.sp, color=Color(0xffcccccc), modifier=Modifier.padding(start=10.dp, end=10.dp))
				}
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if(faceDialog){
                            scope.launch{
                                val path = openFilePicker()
                                if(path != null && name.trim() != "") {
                                    val src = File(path)
                                    src.copyTo(File("../assets/faces/raw/$name.jpg"), overwrite=true)
                                    native.genEmb(File("../assets/faces/raw/$name.jpg").getAbsolutePath(), File("../assets/faces/emb/$name").getAbsolutePath())
                                }
                            }
                        }
                        else if(editDialog){
                            File("../assets/faces/raw/$name.jpg").renameTo(File("../assets/faces/raw/$rename.jpg"))
                            File("../assets/faces/emb/$name").renameTo(File("../assets/faces/emb/$rename"))
                        }
                        else{
                            File("../assets/faces/raw/$name.jpg").delete()
                            File("../assets/faces/emb/$name").delete()
                        }
                        person = File("../assets/faces/raw").listFiles()
                        editDialog = false
                        delDialog = false
                        faceDialog = false
                    }
                ) {
                    Text(if(faceDialog) "Add" else if(editDialog) "Save" else "Yes, delete", fontFamily=InterFont, color=Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        editDialog = false
                        delDialog = false
                        faceDialog = false
                    }
                ) {
                    Text("Cancel", fontFamily=InterFont, color=Color.White)
                }
            }
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(top=30.dp, end=20.dp)) {
        val rootMaxWidth = maxWidth
        val rootMaxHeight = maxHeight
		Column(modifier=Modifier.fillMaxSize().padding(20.dp)){
			Row(modifier=Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
				Text(text="Facial Embeddings", color = Color(0xffffffff), fontSize=30.sp, fontFamily=openSans, fontWeight=FontWeight.Bold)
				Row(horizontalArrangement=Arrangement.spacedBy(0.dp)){
					IconButton(
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent, contentColor = Color.Black),
                        onClick = {
                        	editDialog = true
                        }
                    ){
                        Icon(
                            painter = painterResource("icons/edit.svg"),
                            modifier=Modifier.size(20.dp),
                            tint = Color.White.copy(alpha=0.9f),
                            contentDescription = null
                        )
                    }
                    IconButton(
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent, contentColor = Color.Black),
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val path = openFilePicker()
                                if (path != null && name.trim().isNotEmpty()) {
                                    val targetFile = File("../assets/faces/raw/$name.jpg")
                                    File(path).copyTo(targetFile, overwrite = true)
                                    native.genEmb(targetFile.absolutePath, File("../assets/faces/emb/$name").absolutePath)
                                    
                                    withContext(Dispatchers.Main) {
                                        person = File("../assets/faces/raw").listFiles() ?: emptyArray()
                                        versionCounter++
                                    }
                                }
                            }
                        }
                    ){
                        Icon(
                            painter = painterResource("icons/update.svg"),
                            modifier=Modifier.size(20.dp),
                            tint = Color.White.copy(alpha=0.9f),
                            contentDescription = null
                        )
                    }
                    IconButton(
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent, contentColor = Color.Black),
                        onClick = { delDialog = true }
                    ){
                        Icon(
                            painter = painterResource("icons/delete.svg"),
                            modifier=Modifier.size(20.dp),
                            tint = Color.White.copy(alpha=0.9f),
                            contentDescription = null
                        )
                    }
                    Spacer(modifier=Modifier.width(10.dp))
					Button(modifier=Modifier.wrapContentWidth(),
		                colors = ButtonDefaults.buttonColors(
		                    containerColor = Color(0xffcccccc),
		                    contentColor = Color(0xff1a1a1a)
		                ),
		                onClick = {
                            name = ""
                            faceDialog = true
		                }
		            ) {
		                Text("Upload Face", fontWeight = FontWeight.Bold, fontFamily=InterFont)
		            }
		        }
			}
			Divider(color=Color(0xFF333333), thickness=1.dp, modifier=Modifier.fillMaxWidth().padding(top=5.dp))
			Spacer(modifier=Modifier.height(30.dp))
			LazyVerticalGrid(columns = GridCells.Fixed(6), modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(25.dp), verticalArrangement = Arrangement.spacedBy(25.dp)){
                items(person.size){ index ->
                    val faceImage = person[index]
                    if (faceImage.exists()){
                        Column(horizontalAlignment=Alignment.CenterHorizontally, modifier=Modifier.wrapContentWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha=if(selected >= 0 && selected == index) 0.4f else 0.1f)).clickable{ selected = index; name = faceImage.nameWithoutExtension; rename = faceImage.nameWithoutExtension }.padding(10.dp)){
                            val bitmap = remember(faceImage, versionCounter){faceImage.inputStream().buffered().use { androidx.compose.ui.res.loadImageBitmap(it) }}  
                            Image(
                                bitmap = bitmap,
                                contentDescription = "Face Image",
                                modifier = Modifier.size(100.dp).clip(RoundedCornerShape(12.dp))
                            )
                            Spacer(modifier=Modifier.height(10.dp))
                            Text(faceImage.nameWithoutExtension, fontWeight = FontWeight.Bold, fontFamily=InterFont, color=Color.White, fontSize=11.sp)
                        }
                    }
                }
            }
		}
		if(fullscreen and false){
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
                    modifier = Modifier.wrapContentWidth().padding(20.dp)
                        .clickable(enabled = false, onClick = {}), // Prevent click propagation
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().background(Color(0xff333333))
                    ) {
                    	
                    }
                }
            }
        }
	}
}