package com.sftpsync.app.ui

import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sftpsync.app.models.*
import com.sftpsync.app.ui.theme.*
import com.sftpsync.app.ui.viewmodel.*
import com.sftpsync.app.utils.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(viewModel: SyncViewModel = remember { SyncViewModel() }) {
    val state = viewModel.state
    val scope = rememberCoroutineScope()

    // Periodically verify permission on Android
    LaunchedEffect(Unit) {
        if (getPlatformName() == "Android") {
            viewModel.setAndroidPermissionGranted(checkManageStoragePermission())
        }
    }

    SftpSyncTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Responsive layout container
                val isDesktop = getPlatformName() == "Desktop"
                
                if (isDesktop) {
                    // Desktop split-screen layout
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Left Sidebar for profile listing (300dp wide)
                        SidebarPanel(
                            state = state,
                            onProfileSelect = { viewModel.selectProfile(it) },
                            onNewProfile = { viewModel.startNewProfile() },
                            onNavigateToLogs = { viewModel.navigateTo(AppScreen.LOGS) },
                            onNavigateToSettings = { viewModel.navigateTo(AppScreen.SETTINGS) },
                            modifier = Modifier.width(280.dp).fillMaxHeight()
                        )
                        
                        VerticalDivider(color = Slate700)
                        
                        // Right Main Content Panel
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            MainContentPanel(
                                state = state,
                                viewModel = viewModel,
                                isDesktop = true
                            )
                        }
                    }
                } else {
                    // Android full-screen layout with bottom nav/sidebar simulation
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Top Header App Bar
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.Sync,
                                        contentDescription = null,
                                        tint = CyanGlow,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "SFTP BiSync",
                                        fontWeight = FontWeight.Bold,
                                        color = TextWhite,
                                        fontSize = 18.sp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(BlueGlow.copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            "Android",
                                            color = BlueGlow,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Slate900,
                                titleContentColor = TextWhite
                            ),
                            actions = {
                                IconButton(onClick = { viewModel.navigateTo(AppScreen.SETTINGS) }) {
                                    Icon(
                                        Icons.Filled.Settings,
                                        contentDescription = "설정",
                                        tint = if (state.currentScreen == AppScreen.SETTINGS) CyanGlow else TextLight
                                    )
                                }
                                if (state.currentScreen != AppScreen.LOGS) {
                                    IconButton(onClick = { viewModel.navigateTo(AppScreen.LOGS) }) {
                                        Icon(Icons.Filled.History, contentDescription = "히스토리", tint = TextLight)
                                    }
                                } else {
                                    IconButton(onClick = { viewModel.navigateTo(AppScreen.DASHBOARD) }) {
                                        Icon(Icons.Filled.Dashboard, contentDescription = "대시보드", tint = TextLight)
                                    }
                                }
                            }
                        )

                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            MainContentPanel(
                                state = state,
                                viewModel = viewModel,
                                isDesktop = false
                            )
                        }
                    }
                }

                // Android Permission Dialog overlay
                if (getPlatformName() == "Android" && !state.androidPermissionGranted) {
                    PermissionOverlay(
                        onRequestPermission = {
                            requestManageStoragePermission()
                            // Force check in background
                            scope.launch {
                                var checks = 0
                                while (checks < 20) {
                                    kotlinx.coroutines.delay(1500)
                                    val granted = checkManageStoragePermission()
                                    viewModel.setAndroidPermissionGranted(granted)
                                    if (granted) break
                                    checks++
                                }
                            }
                        }
                    )
                }

                // Directory Approval Dialog overlay
                state.directoryApprovalRequest?.let { request ->
                    DirectoryApprovalOverlay(
                        request = request
                    )
                }
            }
        }
    }
}

@Composable
fun SidebarPanel(
    state: UiState,
    onProfileSelect: (SyncProfile) -> Unit,
    onNewProfile: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Slate900)
            .padding(16.dp)
    ) {
        // App Title/Badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 24.dp)
        ) {
            Icon(
                Icons.Filled.Sync,
                contentDescription = null,
                tint = CyanGlow,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    "SFTP BiSync",
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    fontSize = 20.sp
                )
                Text(
                    "Desktop Client",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }

        Text(
            "동기화 프로필",
            color = TextMuted,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Profiles list
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.profiles) { profile ->
                val isSelected = state.selectedProfile?.id == profile.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Slate800 else Color.Transparent)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) Slate700 else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onProfileSelect(profile) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Cloud,
                        contentDescription = null,
                        tint = if (isSelected) CyanGlow else TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            profile.name,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) TextWhite else TextLight,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            profile.host,
                            color = TextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Profile Action Buttons
        Button(
            onClick = onNewProfile,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Slate800),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(12.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = CyanGlow)
            Spacer(modifier = Modifier.width(8.dp))
            Text("새 프로필 생성", color = TextLight, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onNavigateToLogs,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Slate700),
            contentPadding = PaddingValues(12.dp)
        ) {
            Icon(Icons.Filled.History, contentDescription = null, tint = TextLight)
            Spacer(modifier = Modifier.width(8.dp))
            Text("전체 동기화 이력", color = TextLight)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onNavigateToSettings,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Slate700),
            contentPadding = PaddingValues(12.dp)
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null, tint = TextLight)
            Spacer(modifier = Modifier.width(8.dp))
            Text("애플리케이션 설정", color = TextLight)
        }
    }
}

@Composable
fun MainContentPanel(
    state: UiState,
    viewModel: SyncViewModel,
    isDesktop: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate900)
            .padding(if (isDesktop) 24.dp else 16.dp)
    ) {
        when (state.currentScreen) {
            AppScreen.DASHBOARD -> DashboardScreen(
                state = state,
                viewModel = viewModel,
                isDesktop = isDesktop
            )
            AppScreen.PROFILE_EDITOR -> ProfileEditorScreen(
                state = state,
                viewModel = viewModel,
                isDesktop = isDesktop
            )
            AppScreen.LOGS -> LogsScreen(
                state = state,
                viewModel = viewModel
            )
            AppScreen.SETTINGS -> SettingsScreen(
                state = state,
                viewModel = viewModel
            )
        }
    }
}

@Composable
fun DashboardScreen(
    state: UiState,
    viewModel: SyncViewModel,
    isDesktop: Boolean
) {
    val profile = state.selectedProfile
    val scope = rememberCoroutineScope()

    if (profile == null) {
        // Empty placeholder state
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                tint = Slate600,
                modifier = Modifier.size(80.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "활성화된 동기화 프로필이 없습니다",
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { viewModel.startNewProfile() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanGlow
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("첫 동기화 프로필 만들기", fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top profile title banner
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
        ) {
            Icon(
                Icons.Filled.CloudQueue,
                contentDescription = null,
                tint = CyanGlow,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile.name,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                        fontSize = 24.sp
                    )
                    
                    if (profile.autoSyncEnabled) {
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        val infiniteTransition = rememberInfiniteTransition()
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.7f,
                            targetValue = 1.0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(2500, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            )
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(SuccessGreen.copy(alpha = 0.12f))
                                .border(1.5.dp, SuccessGreen.copy(alpha = alpha), RoundedCornerShape(12.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(SuccessGreen.copy(alpha = alpha))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "실시간 동기화 중",
                                color = SuccessGreen,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
                Text(
                    "원격지: ${profile.username}@${profile.host}:${profile.port}",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
            
            // Profile setup actions
            IconButton(onClick = { viewModel.navigateTo(AppScreen.PROFILE_EDITOR) }) {
                Icon(Icons.Filled.Settings, contentDescription = "프로필 수정", tint = TextLight)
            }
            
            if (!isDesktop) {
                // Profile selector dropdown button for Android (which doesn't have a sidebar)
                var expanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Filled.List, contentDescription = "프로필 변경", tint = TextLight)
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.background(Slate800)
                    ) {
                        state.profiles.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name, color = TextLight) },
                                onClick = {
                                    viewModel.selectProfile(p)
                                    expanded = false
                                }
                            )
                        }
                        Divider(color = Slate700)
                        DropdownMenuItem(
                            text = { Text("+ 새 프로필 생성", color = CyanGlow, fontWeight = FontWeight.Bold) },
                            onClick = {
                                viewModel.startNewProfile()
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        // Split Layout for Large screen Dashboard, Vertical for Android
        val contentModifier = Modifier.weight(1f).fillMaxWidth()
        
        if (isDesktop) {
            Row(modifier = contentModifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                // Left 55% for Connection Status Cards
                Column(modifier = Modifier.weight(0.55f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatusOverviewCard(profile, state)
                    LogTimelineCard(state, profile)
                }
                
                // Right 45% for the Big Sync Button
                Column(modifier = Modifier.weight(0.45f)) {
                    SyncActionButtonCard(state, viewModel, profile)
                }
            }
        } else {
            LazyColumn(
                modifier = contentModifier,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { SyncActionButtonCard(state, viewModel, profile) }
                item { StatusOverviewCard(profile, state) }
                item { LogTimelineCard(state, profile) }
            }
        }
    }
}

@Composable
fun StatusOverviewCard(profile: SyncProfile, state: UiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Slate800),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Slate700)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "동기화 경로 설정",
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Local Directory Info
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(BlueGlow.copy(alpha = 0.15f))
                        .clickable {
                            openFolderInExplorer(profile.localPath)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = "폴더 열기", tint = BlueGlow, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("로컬 폴더 경로", color = TextMuted, fontSize = 11.sp)
                    Text(
                        profile.localPath,
                        color = TextLight,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { openFolderInExplorer(profile.localPath) }) {
                    Icon(Icons.Filled.Launch, contentDescription = "폴더 열기", tint = BlueGlow, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Remote Directory Info
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(CyanGlow.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.CloudQueue, contentDescription = null, tint = CyanGlow, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("SFTP 원격지 경로", color = TextMuted, fontSize = 11.sp)
                    Text(
                        profile.remotePath,
                        color = TextLight,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Divider(color = Slate700)
            Spacer(modifier = Modifier.height(16.dp))

            // Extra Settings tags
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Slate700)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        val strategyLabel = when (profile.conflictStrategy) {
                            ConflictStrategy.NEWER_WINS -> "최근 수정본 우선"
                            ConflictStrategy.LOCAL_WINS -> "로컬 우선"
                            ConflictStrategy.REMOTE_WINS -> "원격 우선"
                            ConflictStrategy.KEEP_BOTH -> "양쪽 모두 보존"
                        }
                        Text("충돌 정책: $strategyLabel", color = TextLight, fontSize = 11.sp)
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Slate700)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("예외 규칙: ${profile.exclusions.size}개", color = TextLight, fontSize = 11.sp)
                    }
                }
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Slate700)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        val condLabel = when (profile.syncCondition) {
                            SyncCondition.TIME_DIFFERENT -> "시간 다름"
                            SyncCondition.TIME_AND_SIZE_DIFFERENT -> "시간 & 용량 다름"
                            SyncCondition.SIZE_DIFFERENT -> "용량 다름"
                        }
                        Text("동기화 조건: $condLabel", color = TextLight, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SyncActionButtonCard(
    state: UiState,
    viewModel: SyncViewModel,
    profile: SyncProfile
) {
    Card(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        colors = CardDefaults.cardColors(containerColor = Slate800),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Slate700)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Rotating animation logic for Sync Icon
            val infiniteTransition = rememberInfiniteTransition()
            val angle by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1800, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )

            // Dynamic Sync Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(CyanGlow, BlueGlow, VioletGlow)
                        )
                    )
                    .clickable(enabled = !state.isSyncing) { viewModel.startSync(profile) }
            ) {
                // Inner button body
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(150.dp)
                        .clip(CircleShape)
                        .background(Slate800)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Filled.Sync,
                            contentDescription = "동기화 버튼",
                            tint = if (state.isSyncing) CyanGlow else TextWhite,
                            modifier = Modifier
                                .size(56.dp)
                                .rotate(if (state.isSyncing) angle else 0f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            if (state.isSyncing) "동기화 중..." else "양방향 동기화",
                            fontWeight = FontWeight.Bold,
                            color = TextWhite,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Status label & progress bar
            if (state.isSyncing) {
                Text(
                    state.syncStatusText,
                    color = CyanGlow,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                LinearProgressIndicator(
                    progress = state.syncProgress,
                    color = CyanGlow,
                    trackColor = Slate700,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${(state.syncProgress * 100).toInt()}% 완료",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            } else {
                Text(
                    "서버와 로컬 폴더를 연결하여\n최신 변경사항을 양방향 동기화합니다.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
fun LogTimelineCard(state: UiState, profile: SyncProfile) {
    val filteredLogs = state.logs.filter { it.profileId == profile.id }.take(5)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Slate800),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Slate700)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "최근 동기화 로그",
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    fontSize = 16.sp
                )
                
                Text(
                    "더보기",
                    color = BlueGlow,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable {
                        val logPath = getAppConfigDir() + "/logs.json"
                        openFileInSystemViewer(logPath)
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("아직 동기화 이력이 없습니다.", color = TextMuted, fontSize = 12.sp)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    filteredLogs.forEach { log ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val (tagColor, tagLabel) = when (log.actionType) {
                                "UPLOAD" -> CyanGlow to "↑"
                                "DOWNLOAD" -> BlueGlow to "↓"
                                "DELETE_LOCAL", "DELETE_REMOTE" -> ErrorRed to "✗"
                                "CONFLICT_KEEP_BOTH", "CONFLICT" -> WarningYellow to "⚠"
                                else -> SuccessGreen to "✓"
                            }

                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(tagColor.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    tagLabel,
                                    color = tagColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    log.relativePath.ifEmpty { "프로필 연결" },
                                    color = TextLight,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    log.message,
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileEditorScreen(
    state: UiState,
    viewModel: SyncViewModel,
    isDesktop: Boolean
) {
    val profile = state.editingProfile ?: return
    val scope = rememberCoroutineScope()

    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var host by remember(profile.id) { mutableStateOf(profile.host) }
    var port by remember(profile.id) { mutableStateOf(profile.port.toString()) }
    var username by remember(profile.id) { mutableStateOf(profile.username) }
    var authType by remember(profile.id) { mutableStateOf(profile.authType) }
    var password by remember(profile.id) { mutableStateOf(profile.password) }
    var privateKey by remember(profile.id) { mutableStateOf(profile.privateKeyContent ?: "") }
    var passphrase by remember(profile.id) { mutableStateOf(profile.passphrase ?: "") }
    var localPath by remember(profile.id) { mutableStateOf(profile.localPath) }
    var remotePath by remember(profile.id) { mutableStateOf(profile.remotePath) }
    var conflictStrategy by remember(profile.id) { mutableStateOf(profile.conflictStrategy) }
    var syncCondition by remember(profile.id) { mutableStateOf(profile.syncCondition) }
    var autoSyncEnabled by remember(profile.id) { mutableStateOf(profile.autoSyncEnabled) }

    var passwordVisible by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Title block
        item {
            Text(
                "동기화 프로필 편집",
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                fontSize = 22.sp
            )
        }

        // Connection Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Slate700)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("1. SFTP 서버 접속 설정", fontWeight = FontWeight.Bold, color = CyanGlow, fontSize = 14.sp)
                    
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("프로필 명칭") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyanGlow)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("서버 호스트 (IP / Domain)") },
                            modifier = Modifier.weight(0.7f),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyanGlow)
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it },
                            label = { Text("포트") },
                            modifier = Modifier.weight(0.3f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyanGlow)
                        )
                    }

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("사용자명 (Username)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyanGlow)
                    )

                    // Auth Type Tabs
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Slate900)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { authType = AuthType.PASSWORD }
                                .background(if (authType == AuthType.PASSWORD) Slate700 else Color.Transparent)
                                .padding(10.dp)
                        ) {
                            Text("비밀번호 방식", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { authType = AuthType.PRIVATE_KEY }
                                .background(if (authType == AuthType.PRIVATE_KEY) Slate700 else Color.Transparent)
                                .padding(10.dp)
                        ) {
                            Text("개인키 (Private Key) 방식", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    if (authType == AuthType.PASSWORD) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("비밀번호") },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(image, contentDescription = null, tint = TextMuted)
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyanGlow)
                        )
                    } else {
                        OutlinedTextField(
                            value = privateKey,
                            onValueChange = { privateKey = it },
                            label = { Text("PEM 개인키 내용") },
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            maxLines = 10,
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyanGlow)
                        )
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text("키 비밀번호 (Passphrase - 선택사항)") },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyanGlow)
                        )
                    }
                }
            }
        }

        // Directories and Policies Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Slate700)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("2. 동기화 폴더 및 충돌 정책 설정", fontWeight = FontWeight.Bold, color = BlueGlow, fontSize = 14.sp)
                    
                    // Local Folder Picker
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = localPath,
                            onValueChange = { localPath = it },
                            label = { Text("로컬 동기화 대상 폴더") },
                            modifier = Modifier.weight(1f),
                            trailingIcon = {
                                if (localPath.isNotEmpty()) {
                                    IconButton(onClick = { openFolderInExplorer(localPath) }) {
                                        Icon(Icons.Filled.Launch, contentDescription = "폴더 열기", tint = BlueGlow)
                                    }
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyanGlow)
                        )
                        if (getPlatformName() == "Desktop") {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val picked = pickFolder(localPath)
                                        if (picked != null) {
                                            localPath = picked
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Slate700),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = "폴더 선택")
                            }
                        } else {
                            // Android: SAF folder tree picker (internal + SD card)
                            Button(
                                onClick = {
                                    scope.launch {
                                        val picked = pickFolder(localPath)
                                        if (picked != null) {
                                            localPath = picked
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Slate700),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = "폴더 선택")
                            }
                        }
                    }

                    // Android folder preset shortcuts
                    if (getPlatformName() == "Android") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val presetDownload = "/storage/emulated/0/Download/SftpSync"
                            val presetDocuments = "/storage/emulated/0/Documents/SftpSync"
                            
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Slate700)
                                    .clickable { localPath = presetDownload }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("Downloads 지정", color = TextLight, fontSize = 11.sp)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Slate700)
                                    .clickable { localPath = presetDocuments }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("Documents 지정", color = TextLight, fontSize = 11.sp)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = remotePath,
                        onValueChange = { remotePath = it },
                        label = { Text("SFTP 원격 동기화 대상 폴더") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyanGlow)
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Auto-sync switch option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Slate900)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("실시간 자동 동기화", fontWeight = FontWeight.Bold, color = TextWhite, fontSize = 13.sp)
                            Text("로컬 폴더의 파일 변경을 실시간 감지하여 자동 동기화합니다.", color = TextMuted, fontSize = 11.sp)
                        }
                        Switch(
                            checked = autoSyncEnabled,
                            onCheckedChange = { autoSyncEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyanGlow,
                                checkedTrackColor = CyanGlow.copy(alpha = 0.5f),
                                uncheckedThumbColor = Slate700,
                                uncheckedTrackColor = Slate900
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("3. 동기화 조건 설정", fontWeight = FontWeight.Bold, color = BlueGlow, fontSize = 14.sp)

                    // Sync condition choice
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Slate900)
                    ) {
                        SyncCondition.values().forEach { cond ->
                            val isSelected = syncCondition == cond
                            val label = when (cond) {
                                SyncCondition.TIME_DIFFERENT -> "시간 다름"
                                SyncCondition.TIME_AND_SIZE_DIFFERENT -> "시간 & 용량 다름"
                                SyncCondition.SIZE_DIFFERENT -> "용량 다름"
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { syncCondition = cond }
                                    .background(if (isSelected) Slate700 else Color.Transparent)
                                    .padding(8.dp)
                            ) {
                                Text(label, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }

                    val condDesc = when (syncCondition) {
                        SyncCondition.TIME_DIFFERENT -> "파일 수정 시간이 다르면 동기화 대상으로 판단합니다."
                        SyncCondition.TIME_AND_SIZE_DIFFERENT -> "수정 시간과 파일 크기가 둘 다 다를 때만 동기화 대상으로 판단합니다."
                        SyncCondition.SIZE_DIFFERENT -> "수정 시간은 무시하고 파일 크기(용량)만 비교하여 다르면 동기화합니다."
                    }
                    Text(condDesc, color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("4. 충돌 해결 전략", fontWeight = FontWeight.Bold, color = BlueGlow, fontSize = 14.sp)

                    // Conflict strategy choice
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Slate900)
                    ) {
                        ConflictStrategy.values().forEach { strategy ->
                            val isSelected = conflictStrategy == strategy
                            val label = when (strategy) {
                                ConflictStrategy.NEWER_WINS -> "최근것"
                                ConflictStrategy.LOCAL_WINS -> "로컬"
                                ConflictStrategy.REMOTE_WINS -> "원격"
                                ConflictStrategy.KEEP_BOTH -> "보존"
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { conflictStrategy = strategy }
                                    .background(if (isSelected) Slate700 else Color.Transparent)
                                    .padding(8.dp)
                            ) {
                                Text(label, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // Action block: Connection test and Save/Cancel
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Connection test outcome
                state.connectionResult?.let { success ->
                    val color = if (success) SuccessGreen else ErrorRed
                    val text = if (success) "접속 테스트 성공! 정상적으로 연결 가능합니다." else "접속 테스트 실패! 서버 설정이나 인증 값을 확인하세요."
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(color.copy(alpha = 0.15f))
                            .border(1.dp, color, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Icon(
                            if (success) Icons.Filled.CheckCircle else Icons.Filled.Error,
                            contentDescription = null,
                            tint = color
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val p = profile.copy(
                        name = name,
                        host = host,
                        port = port.toIntOrNull() ?: 22,
                        username = username,
                        authType = authType,
                        password = password,
                        privateKeyContent = privateKey.ifEmpty { null },
                        passphrase = passphrase.ifEmpty { null },
                        localPath = localPath,
                        remotePath = remotePath,
                        conflictStrategy = conflictStrategy,
                        syncCondition = syncCondition,
                        autoSyncEnabled = autoSyncEnabled
                    )

                    Button(
                        onClick = { viewModel.testConnection(p) },
                        modifier = Modifier.weight(0.40f),
                        colors = ButtonDefaults.buttonColors(containerColor = Slate700),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !state.isConnecting && host.isNotEmpty() && username.isNotEmpty()
                    ) {
                        Text(if (state.isConnecting) "연결 중..." else "연결 테스트")
                    }

                    Button(
                        onClick = { viewModel.saveProfile(p) },
                        modifier = Modifier.weight(0.40f),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanGlow),
                        shape = RoundedCornerShape(8.dp),
                        enabled = name.isNotEmpty() && host.isNotEmpty() && username.isNotEmpty() && localPath.isNotEmpty() && remotePath.isNotEmpty()
                    ) {
                        Text("프로필 저장", fontWeight = FontWeight.Bold)
                    }

                    // Cancel / Delete
                    Button(
                        onClick = { viewModel.navigateTo(AppScreen.DASHBOARD) },
                        modifier = Modifier.weight(0.20f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Slate700)
                    ) {
                        Text("취소", color = TextLight)
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))

                // Delete button (only show for editing existing profile)
                if (state.profiles.any { it.id == profile.id }) {
                    Button(
                        onClick = { viewModel.deleteProfile(profile) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorRed.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, ErrorRed)
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = ErrorRed)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("프로필 삭제하기", color = ErrorRed, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun LogsScreen(
    state: UiState,
    viewModel: SyncViewModel
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.navigateTo(AppScreen.DASHBOARD) }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "대시보드로 돌아가기", tint = TextLight)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "동기화 이력 로그",
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    fontSize = 22.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val logPath = getAppConfigDir() + "/logs.json"
                        openFileInSystemViewer(logPath)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                    border = BorderStroke(1.dp, BlueGlow),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Filled.Launch, contentDescription = null, tint = BlueGlow, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("로그 파일 직접 열기", color = TextLight, fontSize = 12.sp)
                }

                Button(
                    onClick = { viewModel.clearHistory() },
                    colors = ButtonDefaults.buttonColors(containerColor = Slate700),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Filled.ClearAll, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("로그 전체 초기화", fontSize = 12.sp)
                }
            }
        }

        if (state.logs.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("기록된 동기화 내역이 없습니다.", color = TextMuted, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.logs) { log ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Slate800),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Slate700)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val (tagColor, tagLabel) = when (log.actionType) {
                                "UPLOAD" -> CyanGlow to "↑ UPLOAD"
                                "DOWNLOAD" -> BlueGlow to "↓ DOWNLOAD"
                                "DELETE_LOCAL", "DELETE_REMOTE" -> ErrorRed to "✗ DELETE"
                                "CONFLICT_KEEP_BOTH", "CONFLICT" -> WarningYellow to "⚠ CONFLICT"
                                else -> SuccessGreen to "✓ SYNC"
                            }

                            Column(modifier = Modifier.width(90.dp)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(tagColor.copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        tagLabel,
                                        color = tagColor,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    log.relativePath.ifEmpty { "접속 설정" },
                                    color = TextWhite,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    log.message,
                                    color = TextLight,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                                Row(
                                    modifier = Modifier.padding(top = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        "프로필: ${log.profileName}",
                                        color = TextMuted,
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        formatTime(log.timestamp),
                                        color = TextMuted,
                                        fontSize = 10.sp
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

@Composable
fun PermissionOverlay(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable(enabled = false) {}, // Consume clicks
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.9f).padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(2.dp, CyanGlow)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Filled.FolderSpecial,
                    contentDescription = null,
                    tint = CyanGlow,
                    modifier = Modifier.size(72.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "모든 파일 접근 권한 필요",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "원격 SFTP 서버와 로컬 스토리지를 양방향으로 막힘없이 동기화하려면 Android의 모든 파일 접근 권한(MANAGE_EXTERNAL_STORAGE)이 반드시 필요합니다.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanGlow),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("권한 허가하러 가기", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

fun formatTime(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(date)
}

@Composable
fun DirectoryApprovalOverlay(
    request: com.sftpsync.app.ui.viewmodel.DirectoryApprovalRequest
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable(enabled = false) {}, // Consume clicks
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.9f).padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(2.dp, CyanGlow)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    if (request.isLocal) Icons.Filled.FolderSpecial else Icons.Filled.CloudQueue,
                    contentDescription = null,
                    tint = CyanGlow,
                    modifier = Modifier.size(72.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    if (request.isLocal) "로컬 폴더 생성 승인 요청" else "원격 폴더 생성 승인 요청",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    if (request.isLocal) {
                        "지정하신 로컬 폴더 경로가 존재하지 않습니다.\n새로운 폴더를 자동으로 생성하고 동기화를 계속할까요?\n\n경로: ${request.path}"
                    } else {
                        "지정하신 SFTP 원격 폴더 경로가 존재하지 않습니다.\n서버에 새로운 폴더를 자동으로 생성하고 동기화를 계속할까요?\n\n경로: ${request.path}"
                    },
                    color = TextMuted,
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { request.onResponse(true) },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanGlow),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("생성하기", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { request.onResponse(false) },
                        colors = ButtonDefaults.buttonColors(containerColor = Slate700),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("취소", color = TextLight)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    state: UiState,
    viewModel: SyncViewModel
) {
    var showShutdownDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.navigateTo(AppScreen.DASHBOARD) }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "대시보드로 돌아가기", tint = TextLight)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "애플리케이션 설정",
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                fontSize = 22.sp
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Slate800),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Slate700)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "백그라운드 실시간 동기화 정보",
                            fontWeight = FontWeight.Bold,
                            color = TextWhite,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        
                        val isAndroid = getPlatformName() == "Android"
                        
                        if (isAndroid) {
                            Text(
                                "• Android 환경에서는 자동 동기화 기능이 활성화된 프로필이 1개라도 있는 경우, 앱이 백그라운드로 전환되어도 시스템 알림창에 동기화 상태가 상주하는 포어그라운드 서비스가 실행됩니다.",
                                color = TextLight,
                                fontSize = 13.sp,
                                lineHeight = 20.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "• 이 서비스를 통해 로컬 파일의 변경 감지(WatchService) 및 원격 서버 폴링(30초 주기)이 끊김 없이 안정적으로 백그라운드에서 동작합니다.",
                                color = TextLight,
                                fontSize = 13.sp,
                                lineHeight = 20.sp
                            )
                        } else {
                            Text(
                                "• Desktop(Windows/Linux) 환경에서는 동기화 중단을 방지하기 위해 창 닫기 버튼 [X]를 클릭해도 앱이 즉시 종료되지 않고 작업 표시줄의 시스템 트레이 영역으로 자동 최소화됩니다.",
                                color = TextLight,
                                fontSize = 13.sp,
                                lineHeight = 20.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "• 트레이 아이콘을 더블 클릭하거나 트레이 우클릭 메뉴를 통해 메인 제어 창을 언제든지 복원할 수 있습니다.",
                                color = TextLight,
                                fontSize = 13.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Slate800),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Slate700)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "애플리케이션 완전 종료",
                            fontWeight = FontWeight.Bold,
                            color = ErrorRed,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            "백그라운드에서 백그라운드 서비스 및 트레이 상주를 해제하고, 동기화 프로세스를 즉시 정지한 후 앱을 완전히 안전하게 종료합니다.",
                            color = TextMuted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(bottom = 20.dp)
                        )

                        Button(
                            onClick = { showShutdownDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = ErrorRed.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.5.dp, ErrorRed),
                            contentPadding = PaddingValues(14.dp)
                        ) {
                            Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, tint = ErrorRed)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("애플리케이션 즉시 완전 종료", color = ErrorRed, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }

    if (showShutdownDialog) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .clickable(enabled = false) {},
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(0.9f).padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(2.dp, ErrorRed)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Filled.PowerSettingsNew,
                        contentDescription = null,
                        tint = ErrorRed,
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "애플리케이션 완전 종료",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "정말로 SFTP BiSync 애플리케이션을 완전히 종료하시겠습니까?\n\n종료 시 백그라운드 포어그라운드 서비스 및 트레이 상주가 모두 해제되고, 모든 실시간 동기화 모니터링이 중단됩니다.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { 
                                showShutdownDialog = false
                                exitApplicationProcess()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("완전 종료", fontWeight = FontWeight.Bold, color = TextWhite)
                        }
                        Button(
                            onClick = { showShutdownDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = Slate700),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("취소", color = TextLight)
                        }
                    }
                }
            }
        }
    }
}
