package org.anvlabs.vissora

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.core.content.FileProvider
import android.graphics.BitmapFactory

import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.LocalTime

import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.client.request.forms.*
import io.ktor.utils.io.jvm.javaio.copyTo

val client = HttpClient(CIO)

suspend fun uploadFile(filePath: String, requestBody: String): Pair<Int, String> {
    val file = File(filePath)

    require(file.isFile) { "File does not exist: $filePath" }

    val response = client.post("http://0.0.0.0:5000/match") {
        setBody(
            MultiPartFormDataContent(
                formData {
                    append("requestBody", requestBody)
                    append(
                        "file",
                        file.readBytes(),
                        Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                        }
                    )
                }
            )
        )
    }
    val statusCode: Int = response.status.value
    val responseText: String = response.bodyAsText()

    return Pair(statusCode, responseText)
}

suspend fun downloadFileToTempDir(context: Context, downloadUrl: String, fileName: String): File {
    val targetFile = File(context.cacheDir, fileName)
    client.prepareGet(downloadUrl).execute { httpResponse ->
        val channel = httpResponse.bodyAsChannel()
        withContext(Dispatchers.IO) {
            targetFile.outputStream().use { fileStream ->
                channel.copyTo(fileStream)
            }
        }
    }
    return targetFile
}

fun showSystemColorPicker(): String? {
    return null
}

fun openImageInSystemViewer(context: Context, targetFile: File) {
    if (targetFile.exists()) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", targetFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}

suspend fun makePostRequest(url: String, requestBody: String = ""): Pair<Int, String> {
    val response: HttpResponse = client.post(url) {
        if (requestBody.isNotEmpty()) {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
    }
    val statusCode: Int = response.status.value
    val responseText: String = response.bodyAsText()

    return Pair(statusCode, responseText)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerModal(onDateSelected: (Long?) -> Unit, onDismiss: () -> Unit) {
    val datePickerState = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onDateSelected(datePickerState.selectedDateMillis)
                    onDismiss()
                }
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerModal(
    onTimeSelected: (hour: Int, minute: Int) -> Unit, 
    onDismiss: () -> Unit
) {
    val timePickerState = rememberTimePickerState(
        initialHour = 17,
        initialMinute = 10,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onTimeSelected(timePickerState.hour, timePickerState.minute)
                    onDismiss()
                }
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                TimePicker(state = timePickerState)
            }
        }
    )
}

suspend fun openFilePicker(): String? {
    return null 
}

fun Modifier.checkerboardBackground(tileSize: Dp = 10.dp, colorA: Color = Color.Red, colorB: Color = Color.White): Modifier = this.drawBehind {
    val tilePx = tileSize.toPx()
    val columns = (size.width / tilePx).toInt() + 1
    val rows = (size.height / tilePx).toInt() + 1

    drawRect(color = colorA)

    for (row in 0 until rows) {
        for (col in 0 until columns) {
            if ((row + col) % 2 == 1) {
                drawRect(
                    color = colorB,
                    topLeft = Offset(col * tilePx, row * tilePx),
                    size = Size(tilePx, tilePx)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessWindow(){
    val context = LocalContext.current
    var VRNText by remember { mutableStateOf("") }
    var AIPrompt by remember { mutableStateOf("") }
    var filePath by remember { mutableStateOf("No file selected") }
    var fullscreen by remember { mutableStateOf(false) }
    var vnum by remember { mutableStateOf(0) }
    var selDate by remember { mutableStateOf(0) }
    var selTime by remember { mutableStateOf(0) }
    var sd by remember { mutableStateOf(0L) }
    var ed by remember { mutableStateOf(0L) }
    var st by remember { mutableStateOf(Pair(0, 0)) }
    var et by remember { mutableStateOf(Pair(0, 0)) }
    var color by remember { mutableStateOf("000000") }
    val scope = rememberCoroutineScope()
    var anprRes by remember { mutableStateOf("{}") }
    var facedt by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf("Any") }
    var mainData by remember { mutableStateOf(JSONArray()) }
    var trans by remember { mutableStateOf(true) }

    LaunchedEffect(Unit){
        ed = System.currentTimeMillis()
        sd = LocalDate.now(ZoneOffset.UTC).minusDays(5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val now = LocalTime.now()
        et = Pair(now.hour, now.minute)
        st = Pair(0, 0)

        // scope.launch{
        //     val (status, body) = makePostRequest(
        //         url = "http://0.0.0.0:5000/vech_info",
        //         requestBody = "{}"
        //     )
        //     if(status == 200){
        //         anprRes = body
        //     }
        // }
    }

    val InterFont = FontFamily(Font(resId = R.font.inter, weight = FontWeight.Normal))
    val openSans = FontFamily(Font(resId = R.font.open_sans, weight = FontWeight.Normal))

    if(selDate != 0){
        DatePickerModal({ x ->
            if(selDate < 0){ sd = x ?: 0 }
            else{ ed = x ?: 0 }
        }){
            selDate = 0
        }
    }

    if(selTime != 0){
        TimePickerModal({ x, y ->
            if(selTime < 0){ st = Pair(x, y) }
            else{ et = Pair(x, y) }
        }){
            selTime = 0
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(top=30.dp, end=20.dp)) {
        val rootMaxWidth = maxWidth
        val rootMaxHeight = maxHeight
        Column(modifier=Modifier.fillMaxSize().padding(20.dp)){
            Row(modifier=Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                Text(text="Reverse Search", color = Color(0xffffffff), fontSize=22.sp, fontFamily=openSans, fontWeight=FontWeight.Bold)
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    val searchInteractionSource = remember { MutableInteractionSource() }
                    val searchIsFocused by searchInteractionSource.collectIsFocusedAsState()
                    val searchIsHovered by searchInteractionSource.collectIsHoveredAsState()
                    val searchIsActive = searchIsFocused || searchIsHovered

                    Button(modifier=Modifier.wrapContentWidth(),
                        interactionSource = searchInteractionSource,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if(searchIsActive) Color.White else Color(0xffcccccc),
                            contentColor = Color(0xff1a1a1a)
                        ),
                        onClick = {
                            // facedt = false
                            // vnum = -1
                            // fullscreen = true
                            // val formatter = SimpleDateFormat("dd-MM-yy", Locale.getDefault())
                            // scope.launch{
                            //     val (status, body) = makePostRequest(
                            //         url = "http://0.0.0.0:5000/get_vechile",
                            //         requestBody = """{"sd":"${formatter.format(Date(sd))}", "ed":"${formatter.format(Date(ed))}", "st":"${st.first}:${st.second}:00", "et":"${et.first}:${et.second}:00", "vrn": "$VRNText", "mode": "$selected", "color":"${if(trans) "" else color}"}"""
                            //     )
                            //     if(status == 200){
                            //         mainData = JSONArray(body)
                            //         vnum = mainData.length()
                            //     }
                            // }
                        }
                    ) {
                        Text("Search Vechile", fontWeight = FontWeight.Bold, fontFamily=InterFont, fontSize=12.sp)
                    }

                    val scanInteractionSource = remember { MutableInteractionSource() }
                    val scanIsFocused by scanInteractionSource.collectIsFocusedAsState()
                    val scanIsHovered by scanInteractionSource.collectIsHoveredAsState()
                    val scanIsActive = scanIsFocused || scanIsHovered

                    Button(modifier=Modifier.wrapContentWidth(),
                        interactionSource = scanInteractionSource,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (scanIsActive) Color.White else Color(0xffcccccc),
                            contentColor = Color(0xff1a1a1a)
                        ),
                        onClick = {
                            facedt = true
                            val formatter = SimpleDateFormat("dd-MM-yy", Locale.getDefault())
                            scope.launch{
                                val path = openFilePicker() 
                                if (path != null) {
                                    vnum = -1
                                    fullscreen = true
                                    val (status, body) = uploadFile(
                                        path,
                                        requestBody = """{"sd":"${formatter.format(Date(sd))}", "ed":"${formatter.format(Date(ed))}", "st":"${st.first}:${st.second}:00", "et":"${et.first}:${et.second}:00"}"""
                                    )
                                    if(status == 200){
                                        mainData = JSONArray(body)
                                        vnum = mainData.length()
                                    }
                                }
                            }   
                        }
                    ) {
                        Text("Scan Face", fontWeight = FontWeight.Bold, fontFamily=InterFont, fontSize=12.sp)
                    }
                }
            }
            Divider(color=Color(0xFF333333), thickness=1.dp, modifier=Modifier.fillMaxWidth().padding(top=5.dp))
            Spacer(modifier=Modifier.height(10.dp))
            Text(text="Search for information on vechile and number plates detected within a timeline.", color = Color(0xffe2e2e2), fontFamily=openSans, fontSize=12.sp)
            Spacer(modifier=Modifier.height(0.dp))
            Row(modifier=Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(20.dp)){
                Column(modifier=Modifier.weight(0.8f).padding(start=30.dp, end=30.dp)){
                    // Row(modifier=Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.SpaceBetween){
                    //     Row(modifier=Modifier.wrapContentWidth(), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(20.dp)){
                    //         Button(
                    //             onClick = { selDate = -1 },
                    //             colors = ButtonDefaults.buttonColors(
                    //                 containerColor = Color(0xff333333),
                    //                 contentColor = Color.White
                    //             )
                    //         ) {
                    //             Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(15.dp)){
                    //                 Icon(
                    //                     painter = painterResource(R.drawable.calender),
                    //                     modifier=Modifier.size(12.dp),
                    //                     contentDescription = null
                    //                 )
                    //                 if(sd != 0L){
                    //                     val formatter = SimpleDateFormat("dd.MM.yy", Locale.getDefault())
                    //                     Text(text=formatter.format(Date(sd)), fontFamily=InterFont, fontSize=11.sp)
                    //                 }
                    //             }
                    //         }
                    //         Text(text="ST", color=Color(0xffcccccc), fontSize=10.sp)
                    //         Button(
                    //             onClick = { selTime = -1 },
                    //             colors = ButtonDefaults.buttonColors(
                    //                 containerColor = Color(0xff333333),
                    //                 contentColor = Color.White
                    //             )
                    //         ) {
                    //             Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(15.dp)){
                    //                 Icon(
                    //                     painter = painterResource(R.drawable.clock),
                    //                     modifier=Modifier.size(12.dp),
                    //                     contentDescription = null
                    //                 )
                    //                 Text(text = String.format("%02d:%02d:00", st.first, st.second), fontFamily=InterFont, fontSize=11.sp)
                    //             }
                    //         }
                    //     }
                    // }
                    Spacer(modifier=Modifier.height(20.dp))
                    Text(text="Plate no.", color=Color.White, fontSize=11.sp, fontFamily=openSans)
                    Spacer(modifier=Modifier.height(10.dp))
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = VRNText,
                        onValueChange = { newValue -> VRNText = newValue.uppercase() },
                        placeholder = { Text("OD 65 3923") },
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF333333),
                            focusedContainerColor = Color(0xFF1A1A1A),
                            unfocusedContainerColor = Color(0xFF1A1A1A)
                        )
                    )
                    Spacer(modifier=Modifier.height(10.dp))
                    Text(text="Vechile info", color=Color.White, fontSize=11.sp, fontFamily=openSans)
                    Spacer(modifier=Modifier.height(10.dp))
                    var expanded by remember { mutableStateOf(false) }
                    var options = listOf("Any", "Bicycle", "Car", "Motorcycle", "Bus", "Truck")
                    Row(modifier=Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                        Box(modifier = Modifier.fillMaxWidth(1.0f)) {
                            TextField(
                                modifier = Modifier.fillMaxWidth(),
                                readOnly = true,
                                value = "Vechile type: $selected",
                                textStyle = TextStyle(
                                    fontFamily = InterFont,
                                    fontSize = 12.sp,
                                    color = Color.White
                                ),
                                onValueChange = {},
                                trailingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.arrow_down), 
                                        contentDescription = "Expand",
                                        tint = Color.White
                                    )
                                },
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                    errorIndicatorColor = Color.Transparent,
                                    focusedContainerColor = Color(0xFF333333),
                                    unfocusedContainerColor = Color(0xFF333333),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )

                            Box(
                                modifier = Modifier.matchParentSize().clickable { expanded = !expanded }
                            )

                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                modifier = Modifier.background(Color(0xFF222222))
                            ) {
                                options.forEach { selectionOption ->
                                    DropdownMenuItem(
                                        text = { Text(selectionOption, color = Color.White) },
                                        onClick = {
                                            selected = selectionOption
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier=Modifier.height(20.dp))
                    Row(modifier=Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                        Column(modifier=Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFcccccc)).clickable{
                            val selectedHex = showSystemColorPicker()
                            if (selectedHex != null){
                                trans = false
                                color = selectedHex
                            }
                        }){
                            if(!trans){
                                val parsedColor = android.graphics.Color.parseColor("#$color")
                                Column(modifier=Modifier.fillMaxSize().padding(5.dp).clip(RoundedCornerShape(8.dp)).background(Color(parsedColor))){}
                            }
                            else{
                                Column(modifier=Modifier.fillMaxSize().padding(5.dp).clip(RoundedCornerShape(8.dp)).checkerboardBackground(
                                    tileSize = 12.dp,
                                    colorA = Color.Black.copy(alpha=0.5f),
                                    colorB = Color.White.copy(alpha=0.5f)
                                )){}
                            }
                        }
                    }
                    Spacer(modifier=Modifier.height(5.dp))
                    Row(modifier=Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(5.dp)){
                        Checkbox(
                            checked = trans,
                            onCheckedChange = { trans = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color.White,
                                checkmarkColor = Color.Black,
                                uncheckedColor = Color.Gray
                            )
                        )
                        Text(text="Transparency", color=Color.White, fontSize=15.sp, fontFamily=openSans)
                    }
                }
                Column(modifier=Modifier.weight(1.0f).padding(start=40.dp, end=40.dp), horizontalAlignment=Alignment.CenterHorizontally){
                    // Row(modifier=Modifier.wrapContentWidth(), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(30.dp)){
                    //     Button(
                    //         onClick = { selDate = 1 },
                    //         colors = ButtonDefaults.buttonColors(
                    //             containerColor = Color(0xff333333),
                    //             contentColor = Color.White
                    //         )
                    //     ) {
                    //         Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(15.dp)){
                    //             Icon(
                    //                 painter = painterResource(R.drawable.calender),
                    //                 modifier=Modifier.size(14.dp),
                    //                 contentDescription = null
                    //             )
                    //             if(ed != 0L){
                    //                 val formatter = SimpleDateFormat("dd.MM.yy", Locale.getDefault())
                    //                 Text(text=formatter.format(Date(ed)), fontFamily=InterFont)
                    //             }
                    //         }
                    //     }
                    //     Text(text="ET", color=Color(0xffcccccc))
                    //     Button(
                    //         onClick = { selTime = 1 },
                    //         colors = ButtonDefaults.buttonColors(
                    //             containerColor = Color(0xff333333),
                    //             contentColor = Color.White
                    //         )
                    //     ) {
                    //         Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(15.dp)){
                    //             Icon(
                    //                 painter = painterResource(R.drawable.clock),
                    //                 modifier=Modifier.size(14.dp),
                    //                 contentDescription = null
                    //             )
                    //             Text(text = String.format("%02d:%02d:00", et.first, et.second), fontFamily=InterFont)
                    //         }
                    //     }
                    // }
                    Spacer(modifier=Modifier.height(20.dp))
                    Text(text="ANPR Results", color = Color(0xffffffff), fontSize=12.sp, fontFamily=InterFont, fontWeight=FontWeight.Bold)
                    Divider(color=Color(0xFF333333), thickness=1.dp, modifier=Modifier.fillMaxWidth().padding(top=5.dp))
                    Spacer(modifier=Modifier.height(5.dp))
                    Row{
                        Text(modifier=Modifier.weight(0.3f), text="Plate", color=Color.White, fontFamily=InterFont, fontSize=10.sp, textAlign=TextAlign.Center)
                        Text(modifier=Modifier.weight(0.2f), text="cam", color=Color.White, fontFamily=InterFont, fontSize=10.sp, textAlign=TextAlign.Center)
                        Text(modifier=Modifier.weight(0.2f), text="date", color=Color.White, fontFamily=InterFont, fontSize=10.sp, textAlign=TextAlign.Center)
                        Text(modifier=Modifier.weight(0.2f), text="time", color=Color.White, fontFamily=InterFont, fontSize=10.sp, textAlign=TextAlign.Center)
                    }
                    val obj = JSONObject(anprRes)
                    val dtIterator = obj.keys()
                    while (dtIterator.hasNext()) {
                        val dt = dtIterator.next() as String
                        val dates = obj.getJSONObject(dt)
                        
                        val camIterator = dates.keys()
                        while (camIterator.hasNext()) {
                            val cam = camIterator.next() as String
                            val camObj = dates.getJSONObject(cam)
                            
                            if (camObj.has("dt")) {
                                val dets = camObj.getJSONArray("dt")
                                
                                for (i in 0 until dets.length()) {
                                    val data = dets.getJSONObject(i)
                                    val pt = data.optString("pt", "").trim()
                                    
                                    if (pt.isNotEmpty()) {
                                        val tm = data.getDouble("tm").toLong()
                                        
                                        val dateTime = java.time.Instant.ofEpochSecond(tm).atZone(java.time.ZoneId.systemDefault())
                                        val formattedDate = dateTime.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"))
                                        val formattedTime = dateTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
                                        
                                        Spacer(modifier = Modifier.height(5.dp))
                                        Row {
                                            Text(modifier = Modifier.weight(0.3f), text = pt, color = Color(0xFFCCCCCC), fontFamily = InterFont, fontSize = 12.sp, textAlign = TextAlign.Center)
                                            Text(modifier = Modifier.weight(0.2f), text = cam, color = Color(0xFFCCCCCC), fontFamily = InterFont, fontSize = 12.sp, textAlign = TextAlign.Center)
                                            Text(modifier = Modifier.weight(0.2f), text = formattedDate, color = Color(0xFFCCCCCC), fontFamily = InterFont, fontSize = 12.sp, textAlign = TextAlign.Center)
                                            Text(modifier = Modifier.weight(0.2f), text = formattedTime, color = Color(0xFFCCCCCC), fontFamily = InterFont, fontSize = 12.sp, textAlign = TextAlign.Center)
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
                        .width(rootMaxWidth * 0.8f)   
                        .height(rootMaxHeight * 0.8f) 
                        .clickable(enabled = false, onClick = {}), 
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().background(Color(0xff333333))
                    ) {
                        Box(modifier = Modifier.weight(.9f).fillMaxSize().padding(15.dp)){
                            if(vnum > 0){
                                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(15.dp), verticalArrangement = Arrangement.spacedBy(15.dp)){
                                    items(vnum){ index ->
                                        Column(verticalArrangement=Arrangement.spacedBy(10.dp), horizontalAlignment=Alignment.CenterHorizontally){
                                            Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f/0.65f).clip(RoundedCornerShape(8.dp))){
                                                var path by remember(index) { mutableStateOf<File?>(null) }

                                                LaunchedEffect(index) {
                                                    path = downloadFileToTempDir(
                                                        context,
                                                        "http://10.0.2.2:5000/files/${index}.jpg", // Make sure this is accessible from Emulator
                                                        "${index}.jpg"
                                                    )
                                                }

                                                val bitmap = remember(path) {
                                                    path?.let { filePath ->
                                                        filePath.inputStream().use { stream ->
                                                            BitmapFactory.decodeStream(stream)?.asImageBitmap()
                                                        }
                                                    }
                                                }

                                                bitmap?.let {
                                                    Image(
                                                        bitmap = it,
                                                        contentDescription = "Loaded from remote URL",
                                                        contentScale = ContentScale.Fit,
                                                        modifier=Modifier.fillMaxSize().background(Color.Black)
                                                    )
                                                    Row(modifier=Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp), horizontalArrangement=Arrangement.End){
                                                        TvFocusableIconButton(
                                                            defaultContentColor = Color.Black,
                                                            onClick = {
                                                                openImageInSystemViewer(context, path!!)
                                                            }
                                                        ) {
                                                            Icon(
                                                                painter = painterResource(R.drawable.full_screen),
                                                                modifier=Modifier.size(20.dp),
                                                                tint = Color.Black,
                                                                contentDescription = null
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            var cam = mainData.getJSONArray(index)
                                            val dateTime = java.time.Instant.ofEpochSecond(cam.getLong(1)).atZone(java.time.ZoneId.systemDefault())
                                            val formattedDate = dateTime.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"))
                                            val formattedTime = dateTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
                                            Text(text="${cam.getString(0)}, $formattedDate | $formattedTime", fontSize=12.sp, fontFamily=InterFont)
                                        }
                                    }
                                }
                            }
                            else{
                                Column(modifier=Modifier.align(Alignment.Center), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(20.dp)){
                                    Text(text=if(vnum == 0) "No results." else "Processing your request..", fontSize=16.sp, color=Color(0xffcccccc))
                                    if(vnum != 0){
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(30.dp),
                                            color = Color(0xffcccccc),
                                            strokeWidth = 4.dp
                                        )
                                    }
                                }
                            }
                        }
                        Row(modifier=Modifier.fillMaxWidth().weight(.1f).background(Color(0xff1a1a1a)), horizontalArrangement=Arrangement.Center, verticalAlignment=Alignment.CenterVertically){
                            Text(text=if(facedt && vnum > 0) "$vnum results" else if(vnum > 0) "$vnum vechiles detected" else "", color=Color.White, fontSize=14.sp)
                        }
                    }
                }
            }
        }
    }
}