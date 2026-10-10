package com.nightwielder.wuwatracker

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val Context.dataStore by preferencesDataStore("wuwa_tracker")
private const val CHANNEL_ID = "wuwa_reminders"
private const val RESET_ALARM = 1001
private const val FEED_WORK = "wuwa_feed_sync"
private const val GITHUB_FEED_URL = "https://the-nightwielder.github.io/Wuthering-Waves-Tracker/feed.json"

enum class Server(val label: String, val zone: String) {
    AMERICA("America", "America/New_York"), EUROPE("Europe", "Europe/London"),
    ASIA("Asia", "Asia/Shanghai"), SEA("SEA", "Asia/Singapore"), HMT("HMT", "Asia/Hong_Kong")
}

data class TrackerEvent(val title: String, val type: String, val start: Instant, val end: Instant?, val note: String, val sourceUrl: String = "", val status: String = "COMMUNITY", val confidence: Double = 0.0)
data class FeedItem(val title: String, val url: String, val summary: String, val status: String, val source: String, val publishedAt: String, val confidence: Double)
data class VersionIntel(val version: String, val status: String, val confidence: Double, val sourceUrls: List<String>)
data class BannerIntel(val title: String, val version: String?, val phase: Int?, val startAt: Instant?, val endAt: Instant?, val status: String, val confidence: Double, val sourceUrls: List<String>, val sourceLabel: String = "Website", val weaponClaim: String? = null, val resonatorImages: Map<String,String> = emptyMap(), val weaponImages: Map<String,String> = emptyMap())
data class ResonatorIntel(val name: String, val version: String?, val phase: Int?, val element: String?, val weapon: String?, val status: String, val confidence: Double, val sourceUrls: List<String>, val sourceLabel: String = "Website", val weaponClaim: String? = null)

data class UiState(
    val server: Server = Server.SEA, val dailyDone: Boolean = false, val luniteDone: Boolean = false,
    val reminderEnabled: Boolean = true, val reminderLead: Int = 0, val version: String = "Unknown",
    val events: List<TrackerEvent> = defaultEvents(), val feedItems: List<FeedItem> = emptyList(),
    val versions: List<VersionIntel> = emptyList(), val banners: List<BannerIntel> = emptyList(),
    val resonators: List<ResonatorIntel> = emptyList(), val feedUrl: String = GITHUB_FEED_URL, val lastSync: String = "",
    val serverSelected: Boolean = false, val customReminderAt: Instant? = null,
    val collapsedSections: Set<String> = emptySet(), val monthlyTower: Boolean = false, val monthlyWastes: Boolean = false, val monthlyMatrix: Boolean = false
)

private fun defaultEvents() = listOf(
    TrackerEvent("Tower of Adversity", "Endgame", Instant.EPOCH, null, "Permanent/recurring endgame. Use the in-game timer for the active rotation."),
    TrackerEvent("Whimpering Wastes", "Endgame", Instant.EPOCH, null, "Permanent/recurring content. Use the in-game timer for the active cycle."),
    TrackerEvent("Endstate Matrix", "Endgame", Instant.EPOCH, null, "Version/phase-linked challenge. Use the in-game timer for exact availability.")
)

private fun nextReset(server: Server, now: Instant = Instant.now()): ZonedDateTime {
    val local = now.atZone(ZoneId.of(server.zone)); var reset = local.toLocalDate().atTime(4, 0).atZone(ZoneId.of(server.zone))
    if (!local.isBefore(reset)) reset = reset.plusDays(1); return reset
}
private fun cycleKey(server: Server, now: Instant = Instant.now()) = server.name + "-" + YearMonth.from(now.atZone(ZoneId.of(server.zone)))
private fun resetKey(server: Server, now: Instant = Instant.now()) = "${server.name}-${nextReset(server, now).minusNanos(1).toLocalDate()}"
private fun durationText(d: Duration): String {
    val s = d.seconds.coerceAtLeast(0); val days = s / 86400; val hours = (s % 86400) / 3600; val mins = (s % 3600) / 60; val secs = s % 60
    return if (days > 0) "%dd %02dh %02dm".format(days, hours, mins) else "%02dh %02dm %02ds".format(hours, mins, secs)
}
private fun relativeTime(raw:String):String{
    val instant=runCatching{Instant.parse(raw)}.getOrNull()?:return "time unavailable"
    val seconds=Duration.between(instant,Instant.now()).seconds
    if(seconds<60)return "just now"
    val minutes=seconds/60
    if(minutes<60)return "$minutes minute${if(minutes==1L)"" else "s"} ago"
    val hours=minutes/60
    if(hours<24)return "$hours hour${if(hours==1L)"" else "s"} ago"
    val days=hours/24
    return "$days day${if(days==1L)"" else "s"} ago"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); createNotificationChannel()
        setContent { WuWaTrackerRoot() }
    }
    private fun createNotificationChannel() = getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "WuWa reminders", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Daily reset and information reminders" }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WuWaTrackerRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current; val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(UiState()) }; var now by remember { mutableStateOf(Instant.now()) }
    var tab by remember { mutableIntStateOf(0) }; var syncing by remember { mutableStateOf(false) }; var syncMessage by remember { mutableStateOf("") }
    var showServerPicker by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showServerPicker = !state.serverSelected
    }
    LaunchedEffect(Unit) {
        state = loadState(context).copy(feedUrl = GITHUB_FEED_URL)
        saveState(context, state)
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            showServerPicker = !state.serverSelected
        }
        refreshGithubFeed(context, scope, { syncing = it }, { message -> syncMessage = message }, { fresh -> state = fresh })
        while (true) { now = Instant.now(); kotlinx.coroutines.delay(1000) }
    }
    var dailyResetServer by remember { mutableStateOf<Server?>(null) }
    LaunchedEffect(resetKey(state.server, now)) {
        val serverChanged = dailyResetServer != null && dailyResetServer != state.server
        dailyResetServer = state.server
        if (!serverChanged && (state.dailyDone || state.luniteDone)) { state = state.copy(dailyDone = false, luniteDone = false); saveState(context, state) }
    }
    LaunchedEffect(state.server, state.reminderEnabled, state.reminderLead) { if (state.reminderEnabled && state.reminderLead >= 0) scheduleResetAlarm(state.server, state.reminderLead) else cancelResetAlarm() }
    LaunchedEffect(state.customReminderAt, state.reminderEnabled, state.reminderLead) {
        if (state.reminderEnabled && state.reminderLead == -1) state.customReminderAt?.let { scheduleCustomReminder(it) }
        else cancelCustomReminder()
    }
    val update: ((UiState) -> UiState) -> Unit = { transform -> state = transform(state); scope.launch { saveState(context, state) } }
    val onRefreshFeed = { if (!syncing) refreshGithubFeed(context, scope, { syncing = it }, { message -> syncMessage = message }, { fresh -> state = fresh }) }
    var monthlyResetServer by remember { mutableStateOf<Server?>(null) }
    LaunchedEffect(cycleKey(state.server, now)) {
        val serverChanged = monthlyResetServer != null && monthlyResetServer != state.server
        monthlyResetServer = state.server
        if (!serverChanged && (state.monthlyTower || state.monthlyWastes || state.monthlyMatrix)) { state = state.copy(monthlyTower = false, monthlyWastes = false, monthlyMatrix = false); saveState(context, state) }
    }
    val reset = nextReset(state.server, now); val localReset = reset.withZoneSameInstant(ZoneId.systemDefault())
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text("WuWa Tracker", fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xD916131D))) }, bottomBar = {
            NavigationBar(containerColor = Color(0xEE16131D)) { listOf("Home", "Endgame", "Intel", "Settings").forEachIndexed { i, label ->
                NavigationBarItem(tab == i, { tab = i }, icon = { Text(listOf("⌂", "⚔", "◷", "⚙")[i]) }, label = { Text(label) })
            } }
        }) { padding ->
            when (tab) {
                0 -> HomeScreen(state, now, durationText(Duration.between(now, reset.toInstant())), localReset, update, Modifier.padding(padding))
                1 -> EndgameScreen(state, now, Modifier.padding(padding))
                2 -> IntelScreen(state, now, syncing, syncMessage, onRefreshFeed, { key -> update { st -> st.copy(collapsedSections = if (key in st.collapsedSections) st.collapsedSections - key else st.collapsedSections + key) } }, Modifier.padding(padding))
                else -> SettingsScreen(state, update, Modifier.padding(padding))
            }
        }
        if (showServerPicker) AlertDialog(
            onDismissRequest = {},
            title = { Text("Choose your game server") },
            text = { Column { Text("Reset countdowns and reminders use this server’s daily reset."); Spacer(Modifier.height(12.dp)); Server.entries.forEach { server -> TextButton(onClick = { update { it.copy(server = server, serverSelected = true) }; showServerPicker = false }) { Text(server.label) } } } },
            confirmButton = {}
        )
    }
}

@Composable private fun ArtworkScreen(modifier: Modifier, background: Int, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize()) {
        Image(painterResource(background), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.28f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x990C0B11), Color(0xB0100E15), Color(0x990D0C12)))))
        content()
    }
}
@Composable private fun HomeScreen(state: UiState, now: Instant, countdown: String, localReset: ZonedDateTime, onUpdate: ((UiState) -> UiState) -> Unit, modifier: Modifier) {
    val fmt=DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    val localClock=DateTimeFormatter.ofPattern("h:mm a",Locale.getDefault()).withZone(ZoneId.systemDefault()).format(now)
    ArtworkScreen(modifier,R.drawable.bg_home){ LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Text("Today",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=Color.White)}
        item{Card{Column(Modifier.padding(16.dp),Arrangement.spacedBy(6.dp)){Text("Next server reset");Text(countdown,style=MaterialTheme.typography.headlineMedium);Text(state.server.label+" • 04:00 server time");Text("Your local time now: "+localClock);Text("Next reset locally: "+fmt.format(localReset))}}}
        item{Text("Daily Checklist",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,color=Color.White)}
        item{TaskCard("Daily Activities","Reach 100 Activity Points • +60 Astrites",state.dailyDone){v->onUpdate{it.copy(dailyDone=v)}}}
        item{TaskCard("Lunite Subscription","Claim today's reward • +90 Astrites",state.luniteDone){v->onUpdate{it.copy(luniteDone=v)}}}
        item{Text("Monthly / Version-Cycle Checklist",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,color=Color.White)}
        item{Text("Home checks reset with the server's calendar month. Endgame tab is unchanged.",style=MaterialTheme.typography.bodySmall)}
        item{TaskCard("Tower of Adversity","Complete this Tower cycle",state.monthlyTower){v->onUpdate{it.copy(monthlyTower=v)}}}
        item{TaskCard("Whimpering Wastes","Complete this challenge cycle",state.monthlyWastes){v->onUpdate{it.copy(monthlyWastes=v)}}}
        item{TaskCard("Endstate Matrix","Complete this challenge cycle",state.monthlyMatrix){v->onUpdate{it.copy(monthlyMatrix=v)}}}
    }}
}
@Composable private fun EndgameScreen(state: UiState, now: Instant, modifier: Modifier) {
    ArtworkScreen(modifier,R.drawable.bg_endgame){LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Text("Endgame",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=Color(0xFFF3E9FF))}
        item{Text("Permanent and recurring content. Exact active phases should be verified in-game.",color=Color(0xFFE2DDEB))}
        items(state.events.filter{it.type.equals("Endgame",true)}){EventCard(it,now)}
    }}
}
@Composable private fun IntelScreen(state: UiState, now: Instant, syncing: Boolean, syncMessage: String, onRefresh: () -> Unit, onToggle:(String)->Unit, modifier: Modifier) {
    val appContext=androidx.compose.ui.platform.LocalContext.current.applicationContext
    val groups=listOf(IntelGroup("Official Confirmations","OFFICIAL","No recent official updates for this version."),IntelGroup("Unconfirmed Leaks","LEAK","No recent version-relevant leak reports."),IntelGroup("Community Reports","COMMUNITY","No recent version-relevant community reports."))
    val version=state.version.takeIf{it!="Unknown"}?:"3.7"
    val feed=state.feedItems.filter{it.relevant(version,now)}.sortedByDescending{runCatching{Instant.parse(it.publishedAt)}.getOrDefault(Instant.EPOCH)}
    val banners=state.banners.filter{it.relevant(version,now)}
    fun oneCardPerPhase(rows:List<BannerIntel>)=rows.groupBy{"${it.version.orEmpty()}|${it.phase ?: 0}"}.values.mapNotNull{phaseRows->phaseRows.maxByOrNull{(if(it.weaponClaim.isNullOrBlank())0 else 100+it.weaponClaim.length)+(it.weaponImages.size*10)+(it.resonatorImages.size*5)+(if(it.sourceLabel.contains("WuWaBuild",true))25 else 0)}}
    val active=oneCardPerPhase(banners.filter{it.startAt!=null&&it.startAt<=now&&(it.endAt==null||it.endAt>now)}).sortedBy{it.phase}
    val upcoming=oneCardPerPhase(banners.filter{it.startAt?.isAfter(now)==true}).sortedBy{it.startAt}
    fun cacheEnd(b:BannerIntel):Instant? {
        b.endAt?.let{return it}
        val laterPhase=state.banners.asSequence().filter{it.version==b.version&&it.phase!=null&&b.phase!=null&&it.phase>b.phase&&it.startAt!=null&&(b.startAt==null||it.startAt.isAfter(b.startAt))}.mapNotNull{it.startAt}.minOrNull()
        if(laterPhase!=null)return laterPhase
        return b.version?.let{version->state.banners.asSequence().filter{it.version!=null&&compareVersion(it.version,version)>0&&it.startAt!=null&&(b.startAt==null||it.startAt.isAfter(b.startAt))}.mapNotNull{it.startAt}.minOrNull()}
    }
    LaunchedEffect(state.banners){withContext(Dispatchers.IO){pruneArtworkCache(appContext)}}
    ArtworkScreen(modifier,R.drawable.bg_intel){LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Text("Intelligence",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=Color(0xFFF3E9FF))}
        item{InfoCard("Public intelligence feed",if(syncing)"Refreshing…" else feed.size.toString()+" recent reports · "+banners.size+" relevant banner claims",listOf(syncMessage.takeIf{it.isNotBlank()},state.lastSync.takeIf{it.isNotBlank()}?.let{"Last sync: "+relativeTime(it)}).filterNotNull().joinToString(" · ").ifBlank{"Updated from free public sources."})}
        item{Button(onClick=onRefresh,enabled=!syncing,modifier=Modifier.fillMaxWidth()){Text(if(syncing)"Refreshing…" else "Refresh intelligence")}}
        item{Text("Current version · "+version,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=Color(0xFFFFD994))}
        item{Text("Active Resonator / Weapon Banners",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,color=Color(0xFFE7DAFF))}
        if(active.isEmpty()) item{InfoCard("Live schedule","Banner details will appear here when the public feed refreshes.","No external site is needed to view the schedule.")}
        items(active){b->BannerOverviewCard(b,true,cacheEnd(b)?.toEpochMilli()?:Long.MAX_VALUE)}
        item{Text("Upcoming Resonator / Weapon Banners",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,color=Color(0xFFBFE8E2))}
        if(upcoming.isEmpty()) item{InfoCard("Upcoming schedule","No upcoming schedule details are available in the feed right now.","Check again after the next feed refresh.")}
        items(upcoming){b->BannerOverviewCard(b,false,cacheEnd(b)?.toEpochMilli()?:Long.MAX_VALUE)}
        groups.forEach{g->
            val candidates=feed.filter{it.status.equals(g.status,true)&&(g.status!="LEAK"||it.isNextVersionLeak(version))}
            val rows=(if(g.status=="LEAK")candidates.distinctBy{it.leakDedupKey(version,state.resonators)}else candidates).take(12)
            val shownArticleUrls=rows.map{it.url}.toSet()
            val gb=banners.filter{it.status.equals(g.status,true)&&(g.status!="LEAK"||(it.version?.let{v->compareVersion(v,version)>0}==true&&it.sourceUrls.none{url->url in shownArticleUrls}))}.take(8)
            val gr=state.resonators.filter{it.status.equals(g.status,true)&&if(g.status=="LEAK")it.version?.let{v->compareVersion(v,version)>0}==true&&it.sourceUrls.none{url->url in shownArticleUrls} else (it.version?.let{v->compareVersion(v,version)>=0}==true || feed.any{n->n.title.contains(it.name,true)||n.summary.contains(it.name,true)})}.take(8)
            val closed=g.status in state.collapsedSections
            item(key="section-"+g.status){SectionHeader(g,closed){onToggle(g.status)}}
            if(!closed){if(rows.isEmpty()&&gb.isEmpty()&&gr.isEmpty())item{EmptyIntelCard(g.title,g.emptyMessage)}
                items(rows,key={"news-"+g.status+"-"+it.url}){FeedCard(it)}
                items(gb,key={"banner-"+g.status+"-"+it.title}){b->IntelCard(b.title,listOfNotNull(b.version?.let{"Version "+it},b.phase?.let{phaseLabel(it)},b.status,"Source: "+b.sourceLabel).joinToString(" · "),b.sourceUrls.firstOrNull())}
                items(gr,key={"res-"+g.status+"-"+it.name}){r->IntelCard(r.name,listOfNotNull(r.version?.let{"Version "+it},r.phase?.let{phaseLabel(it)},r.element,r.weapon,g.status,"Source: "+r.sourceLabel).joinToString(" · "),r.sourceUrls.firstOrNull())}
            }
        }
    }}
}
private data class IntelGroup(val title:String,val status:String,val emptyMessage:String)
@Composable private fun SectionHeader(g:IntelGroup,closed:Boolean,toggle:()->Unit){Card(Modifier.fillMaxWidth().clickable(onClick=toggle),colors=CardDefaults.cardColors(containerColor=when(g.status){"OFFICIAL"->Color(0xFF263746);"LEAK"->Color(0xFF482D3C);else->Color(0xFF303044)})){Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(g.title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=Color.White);Text(if(closed)"Tap to expand" else g.status,style=MaterialTheme.typography.labelSmall,color=Color.LightGray)};ChevronControl(closed)}}}
@Composable private fun ChevronControl(collapsed:Boolean){Box(Modifier.size(44.dp),contentAlignment=Alignment.Center){Canvas(Modifier.size(22.dp)){val stroke=2.5.dp.toPx();if(collapsed){drawLine(Color.White,androidx.compose.ui.geometry.Offset(size.width*.22f,size.height*.40f),androidx.compose.ui.geometry.Offset(size.width*.50f,size.height*.68f),strokeWidth=stroke,cap=StrokeCap.Round);drawLine(Color.White,androidx.compose.ui.geometry.Offset(size.width*.50f,size.height*.68f),androidx.compose.ui.geometry.Offset(size.width*.78f,size.height*.40f),strokeWidth=stroke,cap=StrokeCap.Round)}else{drawLine(Color.White,androidx.compose.ui.geometry.Offset(size.width*.22f,size.height*.60f),androidx.compose.ui.geometry.Offset(size.width*.50f,size.height*.32f),strokeWidth=stroke,cap=StrokeCap.Round);drawLine(Color.White,androidx.compose.ui.geometry.Offset(size.width*.50f,size.height*.32f),androidx.compose.ui.geometry.Offset(size.width*.78f,size.height*.60f),strokeWidth=stroke,cap=StrokeCap.Round)}}}}
@Composable private fun BannerOverviewCard(b:BannerIntel,current:Boolean,cacheExpiresAt:Long){
    var expanded by remember(b.title,current){mutableStateOf(false)}
    val names=b.title.split(Regex("\\s*,\\s*|\\s+and\\s+" )).map{it.trim()}.filter{it.isNotBlank()}.distinctBy{it.lowercase(Locale.ROOT)}.take(3)
    val feedWeapons=b.weaponClaim?.split(Regex("\\s*,\\s*|\\s+and\\s+|\\s*;\\s*"))?.map{it.trim()}?.filter{it.isNotBlank()}?.distinctBy{it.lowercase(Locale.ROOT)}.orEmpty()
    val timelineFallback=if(b.version=="3.7")mapOf("hsin" to "Blooming Jadehaven","chisa" to "Kumokiri","iuno" to "Moongazer’s Sigil","suoming" to "Unspoken Rue","lynae" to "Spectrum Blaster","lucilla" to "Freeze Frame")else emptyMap()
    val mappedFallback=names.mapNotNull{timelineFallback[it.lowercase(Locale.ROOT)]}
    val usedFallback=mappedFallback.any{fallback->feedWeapons.none{it.equals(fallback,true)}}
    val weapons=(feedWeapons+mappedFallback).distinctBy{it.lowercase(Locale.ROOT)}.take(3)
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=if(current)Color(0xFF292438) else Color(0xFF26323C))){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
        Row(Modifier.fillMaxWidth().clickable{expanded=!expanded},verticalAlignment=Alignment.CenterVertically){val lead=names.firstOrNull().orEmpty();if(!expanded){ResonatorArtwork(lead,b.resonatorImages.artworkFor(lead),cacheExpiresAt);Column(Modifier.weight(1f).padding(start=12.dp)){Text(if(current)"ACTIVE BANNER" else "UPCOMING BANNER",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary);Text(lead.ifBlank{b.title},style=MaterialTheme.typography.titleLarge.copy(fontSize=18.sp),fontWeight=FontWeight.Bold,color=if(current)Color(0xFFFFD994) else Color(0xFFBFE8E2));Text(listOfNotNull(b.version?.let{"Version $it"},b.phase?.let{phaseLabel(it)},b.startAt?.let{(if(current)"From " else "Starts ")+dateOnly(it)},b.endAt?.let{"Until "+dateOnly(it)}).joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=Color(0xFFE2DDEA))}}else{Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(if(current)"ACTIVE BANNER DETAILS" else "UPCOMING BANNER DETAILS",style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold,color=if(current)Color(0xFFFFD994) else Color(0xFFBFE8E2));Text(listOfNotNull(b.version?.let{"Version $it"},b.phase?.let{phaseLabel(it)},b.startAt?.let{dateOnly(it)},b.endAt?.let{"Until "+dateOnly(it)}).joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=Color(0xFFE2DDEA))}};ChevronControl(!expanded)}
        if(expanded){
            Text("Resonators",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Bold,color=if(current)Color(0xFFFFD994) else Color(0xFFBFE8E2))
            names.forEachIndexed{index,name->
                Row(Modifier.fillMaxWidth().padding(vertical=5.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){ResonatorArtwork(name,b.resonatorImages.artworkFor(name),cacheExpiresAt);Column{Text(name,fontWeight=FontWeight.SemiBold,color=Color.White);Text(listOfNotNull(b.phase?.let{phaseLabel(it)},b.startAt?.let{dateOnly(it)},b.endAt?.let{"until "+dateOnly(it)}).joinToString(" · ").ifBlank{"Dates not provided by source"},style=MaterialTheme.typography.bodySmall,color=Color(0xFFE2DDEA))}}
            }
            HorizontalDivider()
            Text("Weapons",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Bold,color=if(current)Color(0xFFFFD994) else Color(0xFFBFE8E2))
            if(weapons.isEmpty())Text("Weapon names will appear after the updated schedule feed is published.",style=MaterialTheme.typography.bodySmall,color=Color(0xFFE2DDEA)) else weapons.forEachIndexed{index,name->Row(Modifier.fillMaxWidth().padding(vertical=5.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){WeaponArtwork(name,b.weaponImages[name],cacheExpiresAt);Column{Text(name,fontWeight=FontWeight.SemiBold,color=Color.White);Text("${b.version?.let{"Version $it · "}.orEmpty()}${b.phase?.let{phaseLabel(it)?.plus(" · ")}.orEmpty()}${b.startAt?.let{dateOnly(it)}?:"Schedule date unavailable"}",style=MaterialTheme.typography.bodySmall,color=Color(0xFFE2DDEA))}}}
            if(usedFallback)Text("Weapon pairings are community schedule references.",style=MaterialTheme.typography.labelSmall,color=Color.LightGray)
            Text("Verify exact server timing in-game.",style=MaterialTheme.typography.labelSmall,color=Color.LightGray)
        }
    }}
}
@Composable private fun ArtworkInitial(name:String,weapon:Boolean=false){Box(Modifier.size(68.dp).clip(if(weapon)RoundedCornerShape(12.dp)else CircleShape).background(Color(0xFF393441)),contentAlignment=Alignment.Center){Text(name.take(1).uppercase(),style=MaterialTheme.typography.headlineMedium,color=Color.White)}}
private fun Map<String,String>.artworkFor(name:String):String?=entries.firstOrNull{it.key.equals(name,true)}?.value
@Composable private fun ResonatorArtwork(name:String,url:String?,expiresAt:Long){RemoteArtwork(url,name,expiresAt)}
private data class CachedArtUrl(val url:String?,val cachedAt:Long)
private val fandomArtUrlCache=ConcurrentHashMap<String,CachedArtUrl>()
private const val ART_CACHE_TTL_MS=6*60*60*1000L
private const val ART_MAX_BYTES=5*1024*1024
private fun isFandomArtUrl(value:String?):Boolean=runCatching{val u=URL(value);u.protocol=="https"&&(u.host=="fandom.com"||u.host.endsWith(".fandom.com")||u.host.endsWith(".wikia.nocookie.net")||u.host.endsWith(".wikia.com"))}.getOrDefault(false)
private fun fandomPageImage(name:String,kind:String):String?{
    val key="$kind:${name.trim().lowercase(Locale.ROOT)}";val now=System.currentTimeMillis()
    fandomArtUrlCache[key]?.takeIf{now-it.cachedAt<(if(it.url==null)5*60*1000L else ART_CACHE_TTL_MS)}?.let{return it.url}
    val image=runCatching{val query=URLEncoder.encode(name,"UTF-8");val api=URL("https://wutheringwaves.fandom.com/api.php?action=query&titles=$query&prop=pageimages&format=json&pithumbsize=640&redirects=1");val conn=api.openConnection().apply{useCaches=false;connectTimeout=7000;readTimeout=7000;setRequestProperty("Cache-Control","no-cache");setRequestProperty("User-Agent","WuWaTracker/1.0 (Android)")};val json=conn.getInputStream().bufferedReader().use{it.readText()};val pages=JSONObject(json).getJSONObject("query").getJSONObject("pages");val keys=pages.keys();if(!keys.hasNext())null else pages.getJSONObject(keys.next()).optJSONObject("thumbnail")?.optString("source")?.takeIf(::isFandomArtUrl)}.getOrNull()
    fandomArtUrlCache[key]=CachedArtUrl(image,now);return image
}
private fun artworkFile(context:Context,identity:String,url:String):File{val digest=MessageDigest.getInstance("SHA-256").digest("$identity|$url".toByteArray()).joinToString(""){"%02x".format(it)};return File(File(context.cacheDir,"banner-artwork"),digest+".img")}
private fun pruneArtworkCache(context:Context){val dir=File(context.cacheDir,"banner-artwork");val files=dir.listFiles()?:return;val now=System.currentTimeMillis();files.filter{it.name.endsWith(".img")}.forEach{file->val expiryFile=File(dir,file.name+".expiry");val expiry=expiryFile.takeIf{it.isFile}?.readText()?.toLongOrNull()?:0L;if(expiry!=Long.MAX_VALUE&&expiry<=now){file.delete();expiryFile.delete()}};dir.listFiles()?.filter{it.name.endsWith(".tmp")&&now-it.lastModified()>24*60*60*1000L}?.forEach{it.delete()}}
private fun loadArtwork(context:Context,identity:String,url:String,crop:Float,expiresAt:Long,verticalBias:Float=.5f):ImageBitmap?=runCatching{
    val file=artworkFile(context,identity,url);val expiryFile=File(file.parentFile,file.name+".expiry");val now=System.currentTimeMillis()
    val storedExpiry=expiryFile.takeIf{it.isFile}?.readText()?.toLongOrNull()?:0L
    var bitmap:Bitmap?=if(file.isFile&&storedExpiry!=0L&&(storedExpiry==Long.MAX_VALUE||now<storedExpiry))BitmapFactory.decodeFile(file.absolutePath)else null
    if(bitmap==null){file.delete();expiryFile.delete();val connection=(URL(url).openConnection() as HttpURLConnection).apply{useCaches=false;instanceFollowRedirects=true;connectTimeout=8000;readTimeout=10000;setRequestProperty("Cache-Control","no-cache");setRequestProperty("User-Agent","WuWaTracker/1.0 (Android)")}
        try{if(connection.responseCode !in 200..299)throw IllegalStateException("Artwork request failed");if(connection.contentLengthLong>ART_MAX_BYTES)throw IllegalStateException("Artwork is too large");val bytes=connection.inputStream.use{input->val output=ByteArrayOutputStream();val buffer=ByteArray(8192);var total=0;while(true){val count=input.read(buffer);if(count<0)break;total+=count;if(total>ART_MAX_BYTES)throw IllegalStateException("Artwork is too large");output.write(buffer,0,count)};output.toByteArray()};bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:throw IllegalStateException("Unsupported artwork");file.parentFile?.mkdirs();val temp=File(file.parentFile,file.name+"."+Thread.currentThread().id+".tmp");FileOutputStream(temp).use{it.write(bytes)};if(temp.renameTo(file)){file.setLastModified(now);expiryFile.writeText(expiresAt.toString())}else temp.delete()}finally{connection.disconnect()}
    }
    bitmap?.let{source->val side=(minOf(source.width,source.height)*crop).toInt().coerceAtLeast(1);val left=((source.width-side)/2).coerceIn(0,source.width-side);val top=((source.height-side)*verticalBias).toInt().coerceIn(0,source.height-side);Bitmap.createBitmap(source,left,top,side,side).asImageBitmap()}
}.getOrNull()
  @Composable private fun WeaponArtwork(name:String,url:String?,expiresAt:Long){val context=androidx.compose.ui.platform.LocalContext.current.applicationContext;var image by remember(name,url,expiresAt){mutableStateOf<ImageBitmap?>(null)};LaunchedEffect(name,url,expiresAt){image=withContext(Dispatchers.IO){val preferred=url?.takeIf(::isFandomArtUrl);val first=preferred?.let{loadArtwork(context,"weapon:${name.trim().lowercase(Locale.ROOT)}",it,.72f,expiresAt)};first?:fandomPageImage(name,"weapon")?.let{loadArtwork(context,"weapon:${name.trim().lowercase(Locale.ROOT)}",it,.72f,expiresAt)}}};if(image!=null)Image(image!!,name,contentScale=ContentScale.Crop,modifier=Modifier.size(68.dp).clip(RoundedCornerShape(12.dp)))else ArtworkInitial(name,true)}
  @Composable private fun RemoteArtwork(url:String?,description:String,expiresAt:Long){val context=androidx.compose.ui.platform.LocalContext.current.applicationContext;var image by remember(url,description,expiresAt){mutableStateOf<ImageBitmap?>(null)};LaunchedEffect(url,description,expiresAt){image=withContext(Dispatchers.IO){val preferred=url?.takeIf(::isFandomArtUrl);val first=preferred?.let{loadArtwork(context,"resonator:${description.trim().lowercase(Locale.ROOT)}",it,.96f,expiresAt,0f)};first?:fandomPageImage(description,"resonator")?.let{loadArtwork(context,"resonator:${description.trim().lowercase(Locale.ROOT)}",it,.96f,expiresAt,0f)}}};if(image!=null)Image(image!!,description,contentScale=ContentScale.Crop,modifier=Modifier.size(68.dp).clip(CircleShape))else ArtworkInitial(description)}
@Composable private fun SourceLinkCard(title:String,body:String,url:String){Card(Modifier.fillMaxWidth().clickable{openUrl(url)}){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(title,fontWeight=FontWeight.SemiBold);Text(body,style=MaterialTheme.typography.bodySmall);Text("Open source ↗",color=MaterialTheme.colorScheme.primary)}}}
private fun dateOnly(i:Instant)=DateTimeFormatter.ofPattern("MMM d, yyyy",Locale.getDefault()).withZone(ZoneId.systemDefault()).format(i)
private fun phaseLabel(phase:Int?):String?=when(phase){1->"Phase I";2->"Phase II";3->"Phase III";4->"Phase IV";else->null}
private fun BannerIntel.relevant(version:String,now:Instant):Boolean = if(this.version!=null) compareVersion(this.version,version)>=0 else (endAt?.isAfter(now)==true || startAt?.isAfter(now)==true)
private fun FeedItem.isNextVersionLeak(currentVersion:String):Boolean {
    val text=title+" "+summary
    val versionPattern=Regex("(?i)(?:(?:version|ver\\.?|wuthering\\s+waves|wuwa)\\s*|\\bv\\s*)(\\d+\\.\\d+)")
    val versions=versionPattern.findAll(text).map{it.groupValues[1]}.toMutableList()
    if(versions.isEmpty()&&Regex("(?i)wuthering\\s+waves|\\bwuwa\\b").containsMatchIn(text))versions+=Regex("\\b(\\d+\\.\\d+)\\b").findAll(text).map{it.groupValues[1]}.toList()
    val parts=Regex("\\d+\\.\\d+").find(currentVersion)?.value?.split(".")?.mapNotNull{it.toIntOrNull()}?:return false
    val next="${parts.getOrElse(0){0}}.${parts.getOrElse(1){0}+1}"
    if(versions.isNotEmpty())return versions.any{compareVersion(it,next)==0}
    return Regex("(?i)\\b(?:next\\s+(?:version|patch|update|resonator)|upcoming\\s+(?:version|resonator|banner)|beta\\s+(?:for|of)\\s+(?:the\\s+)?next\\s+(?:version|patch))\\b").containsMatchIn(text)
}
private fun FeedItem.leakDedupKey(currentVersion:String,resonators:List<ResonatorIntel>):String{
    val nextVersion=Regex("(?i)\\b(?:version\\s*|v\\s*|wuwa\\s*|wuthering\\s+waves\\s*)(\\d+\\.\\d+)").find(title)?.groupValues?.get(1)?:currentVersion
    val subjects=resonators.map{it.name.trim()}.filter{it.isNotBlank()&&Regex("(?i)(?<![a-z])"+Regex.escape(it)+"(?![a-z])").containsMatchIn(title)}.distinctBy{it.lowercase(Locale.ROOT)}
    val stop=setOf("wuthering","waves","wuwa","version","banner","banners","leak","leaks","pulling","strategy","release","date","expected","rerun","reruns","prediction","predictions","new","character","upcoming","phase","update","guide","more","and","the","for","with","from","official","confirmed","unconfirmed","u7buy","ldshop","uutop","mone")
    val words=title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]")," ").split(Regex("\\s+")).filter{it.length>2&&it !in stop&& !Regex("\\d+\\.\\d+").matches(it)}
    val topic=subjects.joinToString(","){it.lowercase(Locale.ROOT)}.ifBlank{words.take(4).joinToString(" ").ifBlank{"general"}}
    val publisher=source.substringAfterLast("·",source).lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"),"")
    return "$publisher|$nextVersion|$topic"
}
private fun compareVersion(a:String,b:String):Int{val x=Regex("\\d+\\.\\d+").find(a)?.value?.split(".")?.map{it.toInt()}?:return 0;val y=Regex("\\d+\\.\\d+").find(b)?.value?.split(".")?.map{it.toInt()}?:return 0;for(i in 0 until maxOf(x.size,y.size)){val n=x.getOrElse(i){0}.compareTo(y.getOrElse(i){0});if(n!=0)return n};return 0}
private fun FeedItem.relevant(version:String,now:Instant):Boolean{val mentions=Regex("(?i)(?:(?:version|ver\\.?|wuthering\\s+waves)\\s*|\\bv\\s*)(\\d+\\.\\d+)").findAll(title+" "+summary).map{it.groupValues[1]}.toList();if(mentions.any{compareVersion(it,version)<0}&&mentions.none{compareVersion(it,version)>=0})return false;val date=runCatching{Instant.parse(publishedAt)}.getOrNull()?:return mentions.any{compareVersion(it,version)>=0};return mentions.any{compareVersion(it,version)>=0}||Duration.between(date,now).abs()<=Duration.ofDays(60)}


@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SettingsScreen(state: UiState, update: ((UiState) -> UiState) -> Unit, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var serverExpanded by remember { mutableStateOf(false) }; var leadExpanded by remember { mutableStateOf(false) }
    val options = listOf(0 to "At reset", 15 to "15 minutes before", 30 to "30 minutes before", 60 to "1 hour before", -1 to "Custom date & time")
    ArtworkScreen(modifier,R.drawable.bg_settings) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFFF3E9FF)) }
        item { ExposedDropdownMenuBox(serverExpanded, { serverExpanded = !serverExpanded }) { OutlinedTextField(state.server.label, {}, readOnly = true, label = { Text("Game server") }, modifier = Modifier.menuAnchor().fillMaxWidth()); ExposedDropdownMenu(serverExpanded, { serverExpanded = false }) { Server.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { update { it.copy(server = s, serverSelected = true) }; serverExpanded = false }) } } } }
        item { Text("Reset timezone: ${ZoneId.of(state.server.zone)}") }
        item { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Enable reset reminder"); Text("Scheduled from server reset, displayed in local time.") }; Switch(state.reminderEnabled, { checked -> update { it.copy(reminderEnabled = checked) } }) } }
        item { ExposedDropdownMenuBox(leadExpanded, { leadExpanded = !leadExpanded }) { OutlinedTextField(options.first { it.first == state.reminderLead }.second, {}, readOnly = true, label = { Text("Reminder timing") }, modifier = Modifier.menuAnchor().fillMaxWidth()); ExposedDropdownMenu(leadExpanded, { leadExpanded = false }) { options.forEach { (v, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { update { it.copy(reminderLead = v) }; leadExpanded = false; if (v == -1) showCustomReminderPicker(context) { selected -> update { it.copy(customReminderAt = selected) } } }) } } } }
        if (state.reminderLead == -1) item { TextButton(onClick = { showCustomReminderPicker(context) { selected -> update { it.copy(customReminderAt = selected) } } }) { Text(state.customReminderAt?.let { "Custom reminder: ${DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(it)}" } ?: "Choose reminder date and time") } }
        item { Text("Internet Intelligence", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color(0xFFFFD994)) }
        item { InfoCard("Live intelligence", GITHUB_FEED_URL, "Sources include official notices, Reddit leaks, WuWa Banners, GenGamer, u7buy and LDShop. Leaks stay unconfirmed.") }
        item { Text("Security", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color(0xFFBFE8E2)) }
        item { InfoCard("Local-first", "No game login, password, cookies or account tokens are collected.", "Only the configured HTTPS feed is requested by the app.") }
        item { InfoCard("Leak safety", "Leaks remain clearly marked as unconfirmed.", "Check the linked source before treating a claim as confirmed.") }
    }
}

}

@Composable private fun EmptyIntelCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(body, style = MaterialTheme.typography.bodySmall) } }
}

private fun showCustomReminderPicker(context: Context, onSelected: (Instant) -> Unit) {
    val initial = LocalDateTime.now().plusHours(1)
    DatePickerDialog(context, { _, year, month, day ->
        TimePickerDialog(context, { _, hour, minute ->
            val at = LocalDate.of(year, month + 1, day).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant()
            if (at.isAfter(Instant.now())) onSelected(at)
        }, initial.hour, initial.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
    }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply { datePicker.minDate = System.currentTimeMillis() }.show()
}

@Composable private fun FeedCard(item: FeedItem) { val label = if (item.status == "LEAK") "LEAK · UNCONFIRMED" else item.status; val tint=when(item.status.uppercase()){"OFFICIAL"->Color(0xFF314A5B);"LEAK"->Color(0xFF523241);else->Color(0xFF3A354D)};Card(Modifier.fillMaxWidth().clickable { openUrl(item.url) },colors=CardDefaults.cardColors(containerColor=tint)) { Column(Modifier.padding(16.dp), Arrangement.spacedBy(6.dp)) { Text("$label", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,color=Color(0xFFFFD68A)); Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); if (item.summary.isNotBlank() && !(item.status.equals("LEAK",true)&&item.summary.trim().equals(item.title.trim(),true))) Text(item.summary); Text("Source: ${item.source} · Tap to open matching article", style = MaterialTheme.typography.bodySmall) } } }
@Composable private fun IntelCard(title: String, body: String, url: String?) { Card(Modifier.fillMaxWidth().clickable(enabled = url?.startsWith("https://") == true) { openUrl(url!!) }) { Column(Modifier.padding(16.dp), Arrangement.spacedBy(4.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(body); if (url != null) Text("Source evidence • tap to open", style = MaterialTheme.typography.bodySmall) } } }
@Composable private fun TaskCard(title: String, subtitle: String, done: Boolean, onDone: (Boolean) -> Unit) { Card { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle) }; Checkbox(done, onDone) } } }
@Composable private fun EventCard(event: TrackerEvent, now: Instant) { val active = event.start <= now && (event.end == null || event.end.isAfter(now)); val remaining = if (event.start == Instant.EPOCH) "Ongoing / recurring" else if (active && event.end != null) "Ends in ${durationText(Duration.between(now, event.end))}" else if (event.start > now) "Starts in ${durationText(Duration.between(now, event.start))}" else "Ended"; Card(Modifier.fillMaxWidth().clickable(enabled = event.sourceUrl.isNotBlank()) { openUrl(event.sourceUrl) }) { Column(Modifier.padding(16.dp), Arrangement.spacedBy(5.dp)) { Text(event.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(remaining); Text(event.note, style = MaterialTheme.typography.bodySmall); if (event.status == "LEAK") Text("LEAK · UNCONFIRMED", style = MaterialTheme.typography.labelSmall) } } }
@Composable private fun InfoCard(title: String, body: String, note: String) { Card { Column(Modifier.padding(16.dp), Arrangement.spacedBy(5.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(body); Text(note, style = MaterialTheme.typography.bodySmall) } } }

private data class FeedResult(val items: List<FeedItem>, val events: List<TrackerEvent>, val versions: List<VersionIntel>, val banners: List<BannerIntel>, val resonators: List<ResonatorIntel>, val latestVersion: String?)
private fun UiState.withFeed(f: FeedResult) = copy(feedItems = f.items, events = mergeEvents(defaultEvents(), f.events), versions = f.versions, banners = f.banners, resonators = f.resonators, version = f.latestVersion ?: version, lastSync = Instant.now().toString())

private suspend fun loadState(context: Context): UiState {
    val p = context.dataStore.data.first()
    val server = runCatching { Server.valueOf(p[stringPreferencesKey("server")] ?: Server.SEA.name) }.getOrDefault(Server.SEA)
    val key = resetKey(server)
    val stored = p[stringPreferencesKey("reset_key")]
    val raw = p[stringPreferencesKey("cached_feed")]
    val f = parseFeed(raw)
    return UiState(
        server = server,
        dailyDone = if (stored == key) (p[booleanPreferencesKey("daily")] ?: false) else false,
        luniteDone = if (stored == key) (p[booleanPreferencesKey("lunite")] ?: false) else false,
        reminderEnabled = p[booleanPreferencesKey("reminder")] ?: true,
        reminderLead = p[intPreferencesKey("lead")]?.let { if (it == -1) -1 else it.coerceIn(0, 60) } ?: 0,
        version = p[stringPreferencesKey("version")] ?: "Unknown",
        events = mergeEvents(defaultEvents(), f.events),
        feedItems = f.items,
        versions = f.versions,
        banners = f.banners,
        resonators = f.resonators,
        feedUrl = GITHUB_FEED_URL,
        lastSync = p[stringPreferencesKey("last_sync")] ?: "",
        serverSelected = p[booleanPreferencesKey("server_selected")] ?: false,
        customReminderAt = p[stringPreferencesKey("custom_reminder_at")]?.let { instant(it) },
        collapsedSections = if (p[stringPreferencesKey("collapse_migration")] != "expanded-default-v2") emptySet() else p[stringPreferencesKey("collapsed_sections")]?.split(",")?.filter{it.isNotBlank()}?.toSet() ?: emptySet(),
        monthlyTower = p[stringPreferencesKey("cycle_key")] == cycleKey(server) && (p[booleanPreferencesKey("monthly_tower")] ?: false),
        monthlyWastes = p[stringPreferencesKey("cycle_key")] == cycleKey(server) && (p[booleanPreferencesKey("monthly_wastes")] ?: false),
        monthlyMatrix = p[stringPreferencesKey("cycle_key")] == cycleKey(server) && (p[booleanPreferencesKey("monthly_matrix")] ?: false)
    )
}

private suspend fun saveState(context: Context, state: UiState) { context.dataStore.edit { p ->
    p[stringPreferencesKey("server")] = state.server.name; p[stringPreferencesKey("reset_key")] = resetKey(state.server); p[booleanPreferencesKey("daily")] = state.dailyDone; p[booleanPreferencesKey("lunite")] = state.luniteDone; p[booleanPreferencesKey("reminder")] = state.reminderEnabled; p[intPreferencesKey("lead")] = state.reminderLead; p[stringPreferencesKey("feed_url")] = state.feedUrl; p[stringPreferencesKey("last_sync")] = state.lastSync; p[stringPreferencesKey("version")] = state.version; p[stringPreferencesKey("cached_feed")] = stateToJson(state).toString(); p[booleanPreferencesKey("server_selected")] = state.serverSelected; p[stringPreferencesKey("collapsed_sections")] = state.collapsedSections.joinToString(","); p[stringPreferencesKey("collapse_migration")] = "expanded-default-v2"; p[stringPreferencesKey("cycle_key")] = cycleKey(state.server); p[booleanPreferencesKey("monthly_tower")] = state.monthlyTower; p[booleanPreferencesKey("monthly_wastes")] = state.monthlyWastes; p[booleanPreferencesKey("monthly_matrix")] = state.monthlyMatrix; state.customReminderAt?.let { p[stringPreferencesKey("custom_reminder_at")] = it.toString() } ?: p.remove(stringPreferencesKey("custom_reminder_at"))
} }

private fun stateToJson(state: UiState) = JSONObject().apply {
    put("latestVersion", state.version); put("news", JSONArray().apply { state.feedItems.forEach { put(JSONObject().apply { put("title",it.title); put("url",it.url); put("summary",it.summary); put("status",it.status); put("sourceLabel",it.source); put("publishedAt",it.publishedAt); put("confidence",it.confidence) }) } })
    put("events", JSONArray().apply { state.events.filter { it.start != Instant.EPOCH }.forEach { put(JSONObject().apply { put("title",it.title); put("type",it.type); put("startAt",it.start.toString()); if (it.end != null) put("endAt",it.end.toString()); put("note",it.note); put("sourceUrl",it.sourceUrl); put("status",it.status); put("confidence",it.confidence) }) } })
    put("versions", JSONArray().apply { state.versions.forEach { put(JSONObject().apply { put("version",it.version); put("status",it.status); put("confidence",it.confidence); put("sourceUrls",JSONArray(it.sourceUrls)) }) } })
    put("banners", JSONArray().apply { state.banners.forEach { put(JSONObject().apply { put("title",it.title); put("version",it.version); put("phase",it.phase); put("startAt",it.startAt?.toString()); put("endAt",it.endAt?.toString()); put("status",it.status); put("confidence",it.confidence); put("sourceLabel",it.sourceLabel); put("weapon",it.weaponClaim); put("resonatorImages",JSONObject(it.resonatorImages)); put("weaponImages",JSONObject(it.weaponImages)); put("sourceUrls",JSONArray(it.sourceUrls)) }) } })
    put("resonators", JSONArray().apply { state.resonators.forEach { put(JSONObject().apply { put("name",it.name); put("version",it.version); put("phase",it.phase); put("element",it.element); put("weapon",it.weapon); put("status",it.status); put("confidence",it.confidence); put("sourceUrls",JSONArray(it.sourceUrls)) }) } })
}

private fun parseFeed(raw: String?): FeedResult { if (raw.isNullOrBlank()) return FeedResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null); return runCatching { val root=JSONObject(raw); val bannerRows=parseBanners(root.optJSONArray("scheduleSnapshots") ?: JSONArray()) + parseBanners(root.optJSONArray("banners") ?: JSONArray()) + parseBanners(root.optJSONArray("activeBanners") ?: JSONArray()) + parseBanners(root.optJSONArray("upcomingBanners") ?: JSONArray()); val banners=bannerRows.groupBy { it.title.lowercase(Locale.ROOT)+it.version+it.phase }.values.mapNotNull { rows -> rows.maxByOrNull { (if(it.weaponClaim.isNullOrBlank())0 else 10+it.weaponClaim.length)+it.weaponImages.size*3+it.resonatorImages.size } }; FeedResult(parseNews(root.optJSONArray("news") ?: root.optJSONArray("items") ?: JSONArray()), parseEvents(root.optJSONArray("events") ?: JSONArray()), parseVersions(root.optJSONArray("versions") ?: JSONArray()), banners, parseResonators(root.optJSONArray("resonators") ?: JSONArray()), root.optString("latestVersion").takeIf { it.isNotBlank() } ?: root.optString("version").takeIf { it.isNotBlank() }) }.getOrDefault(FeedResult(emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),null)) }
private fun sourceLabel(type: String, name: String, id: String): String = when {
    type.startsWith("X") -> if (name.isNotBlank() && name != id) "X · $name" else "X (indexed)"
    type == "Reddit" -> if (name.isNotBlank() && name != id) "Reddit · $name" else "Reddit"
    type == "Official website" -> if (name.isNotBlank() && name != id) "Official website · $name" else "Official website"
    type == "Website" -> if (name.isNotBlank() && name != id) "Website · $name" else "Website"
    id.startsWith("x-") -> "X (indexed)"
    id.startsWith("reddit-") -> "Reddit"
    else -> "Website · $id"
}
private fun firstEvidenceLabel(o: JSONObject): String {
    val evidence = o.optJSONArray("sources")?.optJSONObject(0) ?: return "Website"
    return sourceLabel(evidence.optString("sourceType"), evidence.optString("sourceName"), evidence.optString("sourceId"))
}
private fun parseNews(a: JSONArray): List<FeedItem> = buildList {
    for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        val id = o.optString("sourceId", "unknown")
        val storedLabel = o.optString("sourceLabel")
        val source = storedLabel.takeIf { it.isNotBlank() } ?: sourceLabel(o.optString("sourceType"), o.optString("sourceName"), id)
        add(FeedItem(o.optString("title"), o.optString("url"), o.optString("summary"), o.optString("status", "COMMUNITY"), source, o.optString("publishedAt"), o.optDouble("confidence", 0.0)))
    }
}
private fun parseEvents(a: JSONArray): List<TrackerEvent> = buildList { for(i in 0 until a.length()){ val o=a.optJSONObject(i)?:continue; val start=runCatching{Instant.parse(o.optString("startAt",o.optString("start")))}.getOrNull()?:continue; val end=runCatching{Instant.parse(o.optString("endAt",o.optString("end")))}.getOrNull(); add(TrackerEvent(o.optString("title"),o.optString("type","Event"),start,end,o.optString("note","Source-derived event; verify exact timing in-game."),o.optString("sourceUrl"),o.optString("status","COMMUNITY"),o.optDouble("confidence",0.0))) } }
private fun parseVersions(a: JSONArray): List<VersionIntel> = buildList { for(i in 0 until a.length()){val o=a.optJSONObject(i)?:continue;add(VersionIntel(o.optString("version"),o.optString("status","COMMUNITY"),o.optDouble("confidence",0.0),strings(o.optJSONArray("sourceUrls") ?: o.optJSONArray("sources"))))} }
private fun parseBanners(a: JSONArray): List<BannerIntel> = buildList {
    for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        add(BannerIntel(
            o.optString("title"),
            o.optString("version").takeIf { it.isNotBlank() && it != "null" },
            if (o.isNull("phase")) null else o.optInt("phase"),
            instant(o.optString("startAt")),
            instant(o.optString("endAt")),
            o.optString("status", "COMMUNITY"),
            o.optDouble("confidence", 0.0),
            strings(o.optJSONArray("sourceUrls") ?: o.optJSONArray("sources")),
            (o.optString("sourceLabel").takeIf{it.isNotBlank()&&it!="null"} ?: firstEvidenceLabel(o)),
            o.optString("weapon").takeIf { it.isNotBlank() && it != "null" },
            stringMap(o.optJSONObject("resonatorImages")),
            stringMap(o.optJSONObject("weaponImages"))
        ))
    }
}
private fun parseResonators(a: JSONArray): List<ResonatorIntel> = buildList {
    for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        add(ResonatorIntel(
            o.optString("name"),
            o.optString("version").takeIf { it.isNotBlank() && it != "null" },
            if (o.isNull("phase")) null else o.optInt("phase"),
            o.optString("element").takeIf { it.isNotBlank() && it != "null" },
            o.optString("weapon").takeIf { it.isNotBlank() && it != "null" },
            o.optString("status", "COMMUNITY"),
            o.optDouble("confidence", 0.0),
            strings(o.optJSONArray("sourceUrls") ?: o.optJSONArray("sources")),
            (o.optString("sourceLabel").takeIf{it.isNotBlank()&&it!="null"} ?: firstEvidenceLabel(o)),
            o.optString("weapon").takeIf { it.isNotBlank() && it != "null" }
        ))
    }
}
private fun instant(s:String?)=runCatching{if(s.isNullOrBlank()||s=="null")null else Instant.parse(s)}.getOrNull()
private fun strings(a:JSONArray?):List<String> = if(a==null) emptyList() else buildList{for(i in 0 until a.length()) add(a.optString(i))}
private fun stringMap(o:JSONObject?):Map<String,String> = if(o==null) emptyMap() else buildMap{val keys=o.keys();while(keys.hasNext()){val key=keys.next();o.optString(key).takeIf{it.startsWith("https://")}?.let{put(key,it)}}}
private fun mergeEvents(base:List<TrackerEvent>, remote:List<TrackerEvent>)=(base+remote).distinctBy{"${it.type}:${it.title}"}

private suspend fun fetchFeed(url: String): FeedResult = withContext(Dispatchers.IO) {
    require(url.startsWith("https://")) { "HTTPS required" }
    val conn=(URL(url).openConnection() as HttpURLConnection).apply { requestMethod="GET"; connectTimeout=15000; readTimeout=20000; instanceFollowRedirects=false; setRequestProperty("Accept","application/json"); setRequestProperty("User-Agent","WuWaTracker/1.3") }
    try { if(conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}"); val body=conn.inputStream.bufferedReader().use{it.readText()}; parseFeed(body) } finally { conn.disconnect() }
}
private fun refreshGithubFeed(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    setLoading: (Boolean) -> Unit,
    setMessage: (String) -> Unit,
    setState: (UiState) -> Unit
) {
    setLoading(true)
    scope.launch {
        try {
            val feed = fetchFeed(GITHUB_FEED_URL)
            val fresh = loadState(context).copy(feedUrl = GITHUB_FEED_URL).withFeed(feed)
            saveState(context, fresh)
            setState(fresh)
            setMessage(if (feed.items.isEmpty()) "GitHub feed refreshed; it contains no matching reports yet." else "GitHub feed refreshed · ${feed.items.size} reports")
        } catch (error: Exception) {
            setMessage("GitHub feed refresh failed · ${error.message ?: "check your connection"}")
        } finally {
            setLoading(false)
        }
    }
}
private fun enqueueFeedSync(context: Context, immediate: Boolean=false) { if(immediate) { val r=OneTimeWorkRequestBuilder<FeedSyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(); WorkManager.getInstance(context).enqueueUniqueWork("wuwa_feed_now",ExistingWorkPolicy.REPLACE,r) } }
class FeedSyncWorker(appContext: Context, params: WorkerParameters):CoroutineWorker(appContext,params){ 
    override suspend fun doWork():Result { 
        val p=applicationContext.dataStore.data.first(); 
        val url=GITHUB_FEED_URL
        return runCatching{
            val f=fetchFeed(url)
            applicationContext.dataStore.edit{
                it[stringPreferencesKey("cached_feed")]=JSONObject().apply{
                    put("latestVersion",f.latestVersion);
                    put("news",JSONArray().apply{f.items.forEach{put(JSONObject().apply{put("title",it.title);put("url",it.url);put("summary",it.summary);put("status",it.status);put("sourceLabel",it.source);put("publishedAt",it.publishedAt);put("confidence",it.confidence)})}});
                    put("events",JSONArray().apply{f.events.forEach{put(JSONObject().apply{put("title",it.title);put("type",it.type);put("startAt",it.start.toString());put("endAt",it.end?.toString());put("note",it.note);put("sourceUrl",it.sourceUrl);put("status",it.status);put("confidence",it.confidence)})}});
                    put("versions",JSONArray().apply{f.versions.forEach{put(JSONObject().apply{put("version",it.version);put("status",it.status);put("confidence",it.confidence);put("sourceUrls",JSONArray(it.sourceUrls))})}});
                    put("banners",JSONArray().apply{f.banners.forEach{put(JSONObject().apply{put("title",it.title);put("version",it.version);put("phase",it.phase);put("startAt",it.startAt?.toString());put("endAt",it.endAt?.toString());put("status",it.status);put("confidence",it.confidence);put("sourceLabel",it.sourceLabel);put("weapon",it.weaponClaim);put("resonatorImages",JSONObject(it.resonatorImages));put("weaponImages",JSONObject(it.weaponImages));put("sourceUrls",JSONArray(it.sourceUrls))})}});
                    put("resonators",JSONArray().apply{f.resonators.forEach{put(JSONObject().apply{put("name",it.name);put("version",it.version);put("phase",it.phase);put("element",it.element);put("weapon",it.weapon);put("status",it.status);put("confidence",it.confidence);put("sourceUrls",JSONArray(it.sourceUrls))})}})
                }.toString();
                it[stringPreferencesKey("last_sync")]=Instant.now().toString();
                f.latestVersion?.let{v->it[stringPreferencesKey("version")]=v}
            };
            Result.success()
        }.getOrElse{if(runAttemptCount<3)Result.retry()else Result.failure()} 
    } 
}
class BootReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){if(intent.action==Intent.ACTION_BOOT_COMPLETED){val p=goAsync();kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch{try{val s=loadState(context);if(s.reminderEnabled&&s.reminderLead>=0)scheduleResetAlarm(s.server,s.reminderLead);if(s.reminderEnabled&&s.reminderLead==-1)s.customReminderAt?.takeIf{it.isAfter(Instant.now())}?.let(::scheduleCustomReminder);if(s.feedUrl.isNotBlank())enqueueFeedSync(context)}finally{p.finish()}}}}}
private fun scheduleResetAlarm(server:Server,leadMinutes:Int=0){if(leadMinutes < 0)return;val context=App.instance?:return;val now=Instant.now();val reset=nextReset(server,now);var trigger=reset.toInstant().minusSeconds(leadMinutes*60L);if(!trigger.isAfter(now)){val followingReset=nextReset(server,reset.toInstant().plusSeconds(1));trigger=followingReset.toInstant().minusSeconds(leadMinutes*60L)};val intent=Intent(context,ResetAlarmReceiver::class.java).putExtra("server",server.name).putExtra("lead",leadMinutes);val pending=PendingIntent.getBroadcast(context,RESET_ALARM,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,trigger.toEpochMilli(),pending)}
private fun scheduleCustomReminder(at:Instant){if(!at.isAfter(Instant.now()))return;val context=App.instance?:return;val intent=Intent(context,ResetAlarmReceiver::class.java).putExtra("custom",true);val pending=PendingIntent.getBroadcast(context,RESET_ALARM+1,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at.toEpochMilli(),pending)}
private fun cancelCustomReminder(){val context=App.instance?:return;val intent=Intent(context,ResetAlarmReceiver::class.java).putExtra("custom",true);val pending=PendingIntent.getBroadcast(context,RESET_ALARM+1,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);context.getSystemService(AlarmManager::class.java).cancel(pending)}
private fun cancelResetAlarm(){val context=App.instance?:return;val intent=Intent(context,ResetAlarmReceiver::class.java);val pending=PendingIntent.getBroadcast(context,RESET_ALARM,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);context.getSystemService(AlarmManager::class.java).cancel(pending)}
class ResetAlarmReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){val custom=intent.getBooleanExtra("custom",false);val server=runCatching{Server.valueOf(intent.getStringExtra("server")?:Server.SEA.name)}.getOrDefault(Server.SEA);val lead=intent.getIntExtra("lead",0);if(ActivityCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)NotificationManagerCompat.from(context).notify(if(custom)2002 else 2001,NotificationCompat.Builder(context,CHANNEL_ID).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("Wuthering Waves reminder").setContentText(if(custom)"Your custom reminder is due." else if(lead==0)"${server.label} server daily reset is now live." else "${server.label} server reset is in $lead minutes. Finish your dailies and claim Lunite.").setAutoCancel(true).build());if(!custom)scheduleResetAlarm(server,lead)}}
class App:Application(){override fun onCreate(){super.onCreate();instance=this;WorkManager.getInstance(this).enqueueUniquePeriodicWork(FEED_WORK,ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<FeedSyncWorker>(24,TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())}companion object{var instance:App?=null}}
private fun openUrl(url:String){if(!url.startsWith("https://"))return;val ctx=App.instance?:return;runCatching{ctx.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}}
