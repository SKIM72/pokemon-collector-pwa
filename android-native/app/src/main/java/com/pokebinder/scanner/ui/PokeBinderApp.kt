@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pokebinder.scanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pokebinder.scanner.BuildConfig
import com.pokebinder.scanner.model.AuthStatus
import com.pokebinder.scanner.model.CardLanguage
import com.pokebinder.scanner.model.FrameProbe
import com.pokebinder.scanner.model.RecognizedCard
import com.pokebinder.scanner.model.ScannerUiState
import com.pokebinder.scanner.model.SessionCard
import java.text.NumberFormat
import java.util.Locale

enum class ThemeMode(val label: String) {
    LIGHT("라이트"),
    DARK("다크"),
    SYSTEM("기기 설정"),
}

private enum class AppDestination(
    val label: String,
    val icon: ImageVector,
) {
    SEARCH("검색", Icons.Rounded.Search),
    COLLECTION("컬렉션", Icons.Rounded.CollectionsBookmark),
    FAVORITES("즐겨찾기", Icons.Rounded.FavoriteBorder),
    SCAN("스캔", Icons.Rounded.CameraAlt),
    SETTINGS("설정", Icons.Rounded.Settings),
}

@Composable
fun PokeBinderApp(
    state: ScannerUiState,
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onLanguageSelected: (CardLanguage) -> Unit,
    onFrameProbe: (FrameProbe) -> Unit,
    onStableFrame: (ByteArray) -> Unit,
    onCandidateSelected: (RecognizedCard) -> Unit,
    onQuantityChanged: (String, Int) -> Unit,
    onFavoriteToggle: (String) -> Unit,
    onClearSession: () -> Unit,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, String, String) -> Unit,
    onPasswordReset: (String) -> Unit,
    onRefreshCollection: () -> Unit,
    onProfileUpdate: (String) -> Unit,
    onPasswordUpdate: (String, String) -> Unit,
    onSignOut: () -> Unit,
) {
    var destinationName by rememberSaveable {
        mutableStateOf(AppDestination.COLLECTION.name)
    }
    var returnDestinationName by rememberSaveable {
        mutableStateOf(AppDestination.COLLECTION.name)
    }
    val destination = AppDestination.valueOf(destinationName)

    when (state.authStatus) {
        AuthStatus.RESTORING -> {
            AppLoadingScreen()
            return
        }
        AuthStatus.SIGNED_OUT -> {
            AuthScreen(
                busy = state.authBusy,
                message = state.authMessage,
                configured = BuildConfig.SUPABASE_URL.isNotBlank() &&
                    BuildConfig.SUPABASE_ANON_KEY.isNotBlank(),
                onSignIn = onSignIn,
                onSignUp = onSignUp,
                onPasswordReset = onPasswordReset,
            )
            return
        }
        AuthStatus.SIGNED_IN -> Unit
    }

    fun openDestination(next: AppDestination) {
        if (next == AppDestination.SCAN) {
            returnDestinationName = destination.name
        }
        destinationName = next.name
    }

    fun closeScanner() {
        destinationName = returnDestinationName
    }

    if (destination == AppDestination.SCAN) {
        BackHandler(onBack = ::closeScanner)
        ScannerScreen(
            state = state,
            onLanguageSelected = onLanguageSelected,
            onFrameProbe = onFrameProbe,
            onStableFrame = onStableFrame,
            onCandidateSelected = onCandidateSelected,
            onQuantityChanged = onQuantityChanged,
            onClearSession = onClearSession,
            onClose = ::closeScanner,
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppHeader(
                destination = destination,
                userEmail = state.authUser?.email.orEmpty(),
                isSyncing = state.isSyncing,
            )
        },
        bottomBar = {
            AppBottomBar(
                selected = destination,
                onSelected = ::openDestination,
            )
        },
    ) { padding ->
        when (destination) {
            AppDestination.SEARCH -> SearchScreen(
                state = state,
                onScan = { openDestination(AppDestination.SCAN) },
                onQuantityChanged = onQuantityChanged,
                onFavoriteToggle = onFavoriteToggle,
                modifier = Modifier.padding(padding),
            )
            AppDestination.COLLECTION -> CollectionScreen(
                state = state,
                title = "내 컬렉션",
                cards = state.sessionCards,
                emptyTitle = "아직 등록된 카드가 없어요",
                onScan = { openDestination(AppDestination.SCAN) },
                onQuantityChanged = onQuantityChanged,
                onFavoriteToggle = onFavoriteToggle,
                onRefresh = onRefreshCollection,
                modifier = Modifier.padding(padding),
            )
            AppDestination.FAVORITES -> CollectionScreen(
                state = state,
                title = "즐겨찾기",
                cards = state.favoriteCards,
                emptyTitle = "즐겨찾기한 카드가 없어요",
                onScan = { openDestination(AppDestination.SCAN) },
                onQuantityChanged = onQuantityChanged,
                onFavoriteToggle = onFavoriteToggle,
                onRefresh = onRefreshCollection,
                modifier = Modifier.padding(padding),
            )
            AppDestination.SETTINGS -> SettingsScreen(
                state = state,
                themeMode = themeMode,
                onThemeModeChanged = onThemeModeChanged,
                onLanguageSelected = onLanguageSelected,
                onRefreshCollection = onRefreshCollection,
                onProfileUpdate = onProfileUpdate,
                onPasswordUpdate = onPasswordUpdate,
                onSignOut = onSignOut,
                modifier = Modifier.padding(padding),
            )
            AppDestination.SCAN -> Unit
        }
    }
}

@Composable
private fun AppLoadingScreen() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.size(68.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "P",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun AuthScreen(
    busy: Boolean,
    message: String,
    configured: Boolean,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, String, String) -> Unit,
    onPasswordReset: (String) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf("login") }
    var email by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        verticalArrangement = Arrangement.Center,
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 28.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.size(62.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "P",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "PokeBinder",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = when (mode) {
                        "signup" -> "새 컬렉터 계정을 만드세요"
                        "reset" -> "가입한 이메일로 재설정 링크를 보내드려요"
                        else -> "내 카드 컬렉션에 로그인"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
                Spacer(modifier = Modifier.height(26.dp))
            }
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = when (mode) {
                            "signup" -> "계정 만들기"
                            "reset" -> "비밀번호 재설정"
                            else -> "로그인"
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(18.dp))

                    if (mode == "signup") {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("아이디") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("이메일") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = if (mode == "reset") ImeAction.Done else ImeAction.Next,
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (mode != "reset") {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("비밀번호") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = if (mode == "signup") ImeAction.Next else ImeAction.Done,
                            ),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (mode == "signup") {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            label = { Text("비밀번호 재확인") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (message.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = message,
                            color = if (message.contains("완료") || message.contains("보냈")) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            fontSize = 13.sp,
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                    Button(
                        enabled = !busy && configured,
                        onClick = {
                            when (mode) {
                                "signup" -> onSignUp(
                                    email,
                                    username,
                                    password,
                                    confirmPassword,
                                )
                                "reset" -> onPasswordReset(email)
                                else -> onSignIn(email, password)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            Text(
                                text = when (mode) {
                                    "signup" -> "계정 만들기"
                                    "reset" -> "재설정 메일 보내기"
                                    else -> "로그인"
                                },
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    if (!configured) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "이 APK에 Supabase 연결 정보가 없습니다.",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                        )
                    }

                    if (mode == "login") {
                        TextButton(
                            onClick = { mode = "reset" },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Text("비밀번호를 잊으셨나요?")
                        }
                        Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { mode = "signup" },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                        ) {
                            Text("새 계정 만들기")
                        }
                    } else {
                        TextButton(
                            onClick = { mode = "login" },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Text("로그인으로 돌아가기")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(
    destination: AppDestination,
    userEmail: String,
    isSyncing: Boolean,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "P",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Black,
                        fontSize = 19.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = destination.label,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
                Text(
                    text = if (isSyncing) "Supabase 동기화 중" else "PokeBinder",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = userEmail.firstOrNull()?.uppercase() ?: "P",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppBottomBar(
    selected: AppDestination,
    onSelected: (AppDestination) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier.navigationBarsPadding(),
    ) {
        AppDestination.values().forEach { item ->
            NavigationBarItem(
                selected = selected == item,
                onClick = { onSelected(item) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                    )
                },
                label = {
                    Text(
                        text = item.label,
                        maxLines = 1,
                        fontSize = 10.sp,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun CollectionScreen(
    state: ScannerUiState,
    title: String,
    cards: List<SessionCard>,
    emptyTitle: String,
    onScan: () -> Unit,
    onQuantityChanged: (String, Int) -> Unit,
    onFavoriteToggle: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        item {
            SummaryPanel(state = state)
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                    Text(
                        text = if (state.syncMessage.isNotBlank()) {
                            state.syncMessage
                        } else {
                            "${cards.sumOf { it.quantity }}장 · ${cards.size}종류"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = !state.isSyncing,
                ) {
                    if (state.isSyncing) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(19.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "컬렉션 새로고침",
                        )
                    }
                }
                Button(
                    onClick = onScan,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text("스캔")
                }
            }
        }
        if (cards.isEmpty()) {
            item {
                EmptyCollection(
                    title = emptyTitle,
                    onScan = onScan,
                )
            }
        } else {
            items(cards, key = ::collectionKey) { item ->
                CollectionCard(
                    item = item,
                    isFavorite = item.collectionKey in state.favoriteCardIds,
                    onQuantityChanged = onQuantityChanged,
                    onFavoriteToggle = onFavoriteToggle,
                )
            }
        }
        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SummaryPanel(state: ScannerUiState) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "COLLECTION VALUE",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = formatMoney(state.runningTotal, state.displayCurrency),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryMetric(
                    label = "보유 카드",
                    value = "${state.totalCards}",
                    modifier = Modifier.weight(1f),
                )
                SummaryMetric(
                    label = "종류",
                    value = "${state.sessionCards.size}",
                    modifier = Modifier.weight(1f),
                )
                SummaryMetric(
                    label = "즐겨찾기",
                    value = "${state.favoriteCards.size}",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
            )
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        }
    }
}

@Composable
private fun CollectionCard(
    item: SessionCard,
    isFavorite: Boolean,
    onQuantityChanged: (String, Int) -> Unit,
    onFavoriteToggle: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            AsyncImage(
                model = item.card.imageUrl,
                contentDescription = item.card.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 72.dp, height = 101.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(10.dp),
                    ),
            )
            Spacer(modifier = Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.card.name,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${item.card.setName} · #${item.card.number}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = { onFavoriteToggle(item.collectionKey) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = if (isFavorite) {
                                Icons.Rounded.Favorite
                            } else {
                                Icons.Rounded.FavoriteBorder
                            },
                            contentDescription = if (isFavorite) "즐겨찾기 해제" else "즐겨찾기 추가",
                            tint = if (isFavorite) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(7.dp))
                Text(
                    text = item.card.marketPrice?.let {
                        formatMoney(it * item.quantity, item.card.currency)
                    } ?: "가격 확인 중",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        IconButton(
                            onClick = { onQuantityChanged(item.collectionKey, -1) },
                            modifier = Modifier.size(38.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Remove,
                                contentDescription = "수량 줄이기",
                            )
                        }
                        Text(
                            text = "${item.quantity}장",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(
                            onClick = { onQuantityChanged(item.collectionKey, 1) },
                            modifier = Modifier.size(38.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "수량 늘리기",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCollection(
    title: String,
    onScan: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 38.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape,
                modifier = Modifier.size(58.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.CollectionsBookmark,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onScan) {
                Icon(
                    imageVector = Icons.Rounded.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text("카드 스캔")
            }
        }
    }
}

@Composable
private fun SearchScreen(
    state: ScannerUiState,
    onScan: () -> Unit,
    onQuantityChanged: (String, Int) -> Unit,
    onFavoriteToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val normalized = query.trim().lowercase(Locale.getDefault())
    val results = if (normalized.isBlank()) {
        state.sessionCards
    } else {
        state.sessionCards.filter { item ->
            listOf(
                item.card.name,
                item.card.setName,
                item.card.number,
                item.card.id,
            ).any { it.lowercase(Locale.getDefault()).contains(normalized) }
        }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("카드 이름, 세트 또는 번호") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                text = if (normalized.isBlank()) "최근 등록 카드" else "검색 결과 ${results.size}건",
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (results.isEmpty()) {
            item {
                EmptyCollection(
                    title = if (state.sessionCards.isEmpty()) {
                        "스캔한 카드가 아직 없어요"
                    } else {
                        "일치하는 카드가 없어요"
                    },
                    onScan = onScan,
                )
            }
        } else {
            items(results, key = ::collectionKey) { item ->
                CollectionCard(
                    item = item,
                    isFavorite = item.collectionKey in state.favoriteCardIds,
                    onQuantityChanged = onQuantityChanged,
                    onFavoriteToggle = onFavoriteToggle,
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: ScannerUiState,
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onLanguageSelected: (CardLanguage) -> Unit,
    onRefreshCollection: () -> Unit,
    onProfileUpdate: (String) -> Unit,
    onPasswordUpdate: (String, String) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var username by rememberSaveable {
        mutableStateOf(state.authUser?.username.orEmpty())
    }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        item {
            SettingsGroup(title = "마이페이지") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                        modifier = Modifier.size(46.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = state.authUser?.email?.firstOrNull()?.uppercase() ?: "P",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.authUser?.email.orEmpty(),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = state.authUser?.id.orEmpty(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("아이디") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { onProfileUpdate(username) },
                    enabled = !state.authBusy,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text("프로필 저장")
                }
            }
        }
        item {
            SettingsGroup(title = "테마") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    ThemeMode.values().forEach { mode ->
                        SelectChip(
                            label = mode.label,
                            icon = when (mode) {
                                ThemeMode.LIGHT -> Icons.Rounded.LightMode
                                ThemeMode.DARK -> Icons.Rounded.DarkMode
                                ThemeMode.SYSTEM -> Icons.Rounded.Smartphone
                            },
                            selected = themeMode == mode,
                            onClick = { onThemeModeChanged(mode) },
                        )
                    }
                }
            }
        }
        item {
            SettingsGroup(title = "기본 스캔 언어") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    CardLanguage.values().forEach { language ->
                        SelectChip(
                            label = language.label,
                            selected = state.language == language,
                            onClick = { onLanguageSelected(language) },
                        )
                    }
                }
            }
        }
        item {
            SettingsGroup(title = "비밀번호 변경") {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("새 비밀번호") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("새 비밀번호 재확인") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        onPasswordUpdate(password, confirmPassword)
                        password = ""
                        confirmPassword = ""
                    },
                    enabled = password.isNotBlank() && !state.authBusy,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text("비밀번호 변경")
                }
            }
        }
        item {
            SettingsGroup(title = "백업과 동기화") {
                SettingsRow(
                    label = "Supabase",
                    value = if (state.isSyncing) "동기화 중" else state.syncMessage.ifBlank {
                        "${state.sessionCards.size}종류 저장됨"
                    },
                    valueColor = if (state.syncMessage.contains("실패") ||
                        state.syncMessage.contains("오류")
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Button(
                    onClick = onRefreshCollection,
                    enabled = !state.isSyncing,
                ) {
                    if (state.isSyncing) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(7.dp))
                    Text("지금 동기화")
                }
            }
        }
        item {
            SettingsGroup(title = "앱 정보") {
                SettingsRow(label = "버전", value = BuildConfig.VERSION_NAME)
                Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsRow(
                    label = "이미지 매칭",
                    value = if (state.isEndpointConfigured) "연결됨" else "설정 필요",
                    valueColor = if (state.isEndpointConfigured) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
        if (state.authMessage.isNotBlank()) {
            item {
                Text(
                    text = state.authMessage,
                    color = if (state.authMessage.contains("저장")) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        item {
            SettingsGroup(title = "세션") {
                Button(
                    onClick = onSignOut,
                    enabled = !state.authBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text("로그아웃")
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
            )
            Spacer(modifier = Modifier.height(13.dp))
            content()
        }
    }
}

@Composable
private fun SelectChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = background,
        contentColor = contentColor,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(modifier = Modifier.width(7.dp))
            }
            Text(
                text = label,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = valueColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun formatMoney(value: Double, currency: String): String {
    if (value <= 0.0) return "-"
    val formatter = NumberFormat.getNumberInstance(Locale.KOREA).apply {
        maximumFractionDigits = if (currency == "USD") 2 else 0
    }
    val symbol = when (currency) {
        "JPY" -> "¥"
        "KRW" -> "₩"
        "USD" -> "$"
        else -> "$currency "
    }
    return "$symbol${formatter.format(value)}"
}

private fun collectionKey(item: SessionCard): String = item.collectionKey
