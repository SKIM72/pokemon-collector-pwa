@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pokebinder.scanner.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
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
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pokebinder.scanner.BuildConfig
import com.pokebinder.scanner.R
import com.pokebinder.scanner.model.AuthStatus
import com.pokebinder.scanner.model.CardLanguage
import com.pokebinder.scanner.model.CollectionSortField
import com.pokebinder.scanner.model.FrameProbe
import com.pokebinder.scanner.model.RecognizedCard
import com.pokebinder.scanner.model.SearchSortField
import com.pokebinder.scanner.model.ScannerUiState
import com.pokebinder.scanner.model.SessionCard
import com.pokebinder.scanner.model.SortDirection
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
    onConfirmScan: () -> Unit,
    onNextScan: () -> Unit,
    onQuantitySaved: (String, Int) -> Unit,
    onCardDeleted: (String) -> Unit,
    onFavoriteToggle: (String) -> Unit,
    onClearSession: () -> Unit,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, String, String) -> Unit,
    onPasswordReset: (String) -> Unit,
    onSearchCards: (String) -> Unit,
    onAddSearchCard: (RecognizedCard) -> Unit,
    onSearchSortFieldChanged: (SearchSortField) -> Unit,
    onSearchSortDirectionToggle: () -> Unit,
    onCollectionSortFieldChanged: (CollectionSortField) -> Unit,
    onCollectionSortDirectionToggle: () -> Unit,
    onDisplayCurrencyChanged: (String) -> Unit,
    onScanDebugToggle: (Boolean) -> Unit,
    onScanDebugClear: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onRefreshCollection: () -> Unit,
    onProfileUpdate: (String) -> Unit,
    onPasswordUpdate: (String, String) -> Unit,
    onSignOut: () -> Unit,
) {
    val context = LocalContext.current
    var destinationName by rememberSaveable {
        mutableStateOf(AppDestination.COLLECTION.name)
    }
    var returnDestinationName by rememberSaveable {
        mutableStateOf(AppDestination.COLLECTION.name)
    }
    val destination = AppDestination.valueOf(destinationName)

    LaunchedEffect(state.noticeId) {
        if (state.noticeId > 0L && state.noticeMessage.isNotBlank()) {
            Toast.makeText(context, state.noticeMessage, Toast.LENGTH_SHORT).show()
        }
    }

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
            onConfirmScan = onConfirmScan,
            onNextScan = onNextScan,
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
                onSearch = onSearchCards,
                onAddCard = onAddSearchCard,
                onSortFieldChanged = onSearchSortFieldChanged,
                onSortDirectionToggle = onSearchSortDirectionToggle,
                modifier = Modifier.padding(padding),
            )
            AppDestination.COLLECTION -> CollectionScreen(
                state = state,
                title = "내 컬렉션",
                cards = state.sortedSessionCards,
                emptyTitle = "아직 등록된 카드가 없어요",
                onScan = { openDestination(AppDestination.SCAN) },
                onQuantitySaved = onQuantitySaved,
                onCardDeleted = onCardDeleted,
                onFavoriteToggle = onFavoriteToggle,
                onRefresh = onRefreshCollection,
                onSortFieldChanged = onCollectionSortFieldChanged,
                onSortDirectionToggle = onCollectionSortDirectionToggle,
                modifier = Modifier.padding(padding),
            )
            AppDestination.FAVORITES -> CollectionScreen(
                state = state,
                title = "즐겨찾기",
                cards = state.sortedFavoriteCards,
                emptyTitle = "즐겨찾기한 카드가 없어요",
                onScan = { openDestination(AppDestination.SCAN) },
                onQuantitySaved = onQuantitySaved,
                onCardDeleted = onCardDeleted,
                onFavoriteToggle = onFavoriteToggle,
                onRefresh = onRefreshCollection,
                onSortFieldChanged = onCollectionSortFieldChanged,
                onSortDirectionToggle = onCollectionSortDirectionToggle,
                modifier = Modifier.padding(padding),
            )
            AppDestination.SETTINGS -> SettingsScreen(
                state = state,
                themeMode = themeMode,
                onThemeModeChanged = onThemeModeChanged,
                onLanguageSelected = onLanguageSelected,
                onDisplayCurrencyChanged = onDisplayCurrencyChanged,
                onScanDebugToggle = onScanDebugToggle,
                onScanDebugClear = onScanDebugClear,
                onCheckForUpdate = onCheckForUpdate,
                onInstallUpdate = onInstallUpdate,
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
                color = Color.White,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.size(68.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.app_icon),
                    contentDescription = "PokeBinder",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(15.dp),
                    modifier = Modifier.size(64.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.app_icon),
                        contentDescription = "PokeBinder",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(modifier = Modifier.width(15.dp))
                Column {
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
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
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
                color = Color.White,
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.size(38.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.app_icon),
                    contentDescription = "PokeBinder",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(9.dp)),
                )
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
    onQuantitySaved: (String, Int) -> Unit,
    onCardDeleted: (String) -> Unit,
    onFavoriteToggle: (String) -> Unit,
    onRefresh: () -> Unit,
    onSortFieldChanged: (CollectionSortField) -> Unit,
    onSortDirectionToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedCard by remember { mutableStateOf<SessionCard?>(null) }

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
        item {
            SortControls(
                labels = CollectionSortField.values().map { it.label },
                selectedIndex = state.collectionSortField.ordinal,
                direction = state.collectionSortDirection,
                onSelected = { index ->
                    onSortFieldChanged(CollectionSortField.values()[index])
                },
                onDirectionToggle = onSortDirectionToggle,
            )
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
                    state = state,
                    item = item,
                    isFavorite = item.collectionKey in state.favoriteCardIds,
                    onOpenDetails = { selectedCard = item },
                    onQuantitySaved = onQuantitySaved,
                    onCardDeleted = onCardDeleted,
                    onFavoriteToggle = onFavoriteToggle,
                )
            }
        }
        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    selectedCard?.let { item ->
        CardDetailDialog(
            state = state,
            item = item,
            onDismiss = { selectedCard = null },
        )
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
    state: ScannerUiState,
    item: SessionCard,
    isFavorite: Boolean,
    onOpenDetails: () -> Unit,
    onQuantitySaved: (String, Int) -> Unit,
    onCardDeleted: (String) -> Unit,
    onFavoriteToggle: (String) -> Unit,
) {
    var draftQuantity by rememberSaveable(item.collectionKey, item.quantity) {
        mutableStateOf(item.quantity)
    }
    var showDeleteConfirmation by rememberSaveable(item.collectionKey) {
        mutableStateOf(false)
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetails),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            CardArtwork(
                card = item.card,
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
                    IconButton(
                        onClick = { showDeleteConfirmation = true },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "카드 삭제",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(7.dp))
                Text(
                    text = item.card.marketPrice?.let {
                        formatMoney(
                            state.convertedPrice(item.card) * draftQuantity,
                            state.displayCurrency,
                        )
                    } ?: "가격 확인 중",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Text(
                    text = priceSourceLabel(item.card.priceSource),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            IconButton(
                                onClick = {
                                    if (draftQuantity == 1) {
                                        showDeleteConfirmation = true
                                    } else {
                                        draftQuantity -= 1
                                    }
                                },
                                modifier = Modifier.size(38.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Remove,
                                    contentDescription = "수량 줄이기",
                                )
                            }
                            Text(
                                text = "${draftQuantity}장",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                            )
                            IconButton(
                                onClick = { draftQuantity += 1 },
                                modifier = Modifier.size(38.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = "수량 늘리기",
                                )
                            }
                        }
                    }
                    Button(
                        onClick = {
                            onQuantitySaved(item.collectionKey, draftQuantity)
                        },
                        enabled = draftQuantity != item.quantity && !state.isSyncing,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(38.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Save,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("저장")
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        DeleteCardDialog(
            item = item,
            onDismiss = { showDeleteConfirmation = false },
            onConfirm = {
                showDeleteConfirmation = false
                onCardDeleted(item.collectionKey)
            },
        )
    }
}

@Composable
private fun DeleteCardDialog(
    item: SessionCard,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("컬렉션에서 삭제할까요?") },
        text = {
            Text(
                "${item.card.name} ${item.quantity}장을 모두 삭제합니다. " +
                    "삭제한 뒤에는 되돌릴 수 없습니다.",
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text("삭제")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        },
    )
}

@Composable
private fun CardDetailDialog(
    state: ScannerUiState,
    item: SessionCard,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = item.card.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                CardArtwork(
                    card = item.card,
                    contentDescription = item.card.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(190.dp)
                        .height(266.dp)
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(modifier = Modifier.height(18.dp))
                CardDetailRow("언어", item.card.language.label)
                CardDetailRow("세트", item.card.setName)
                CardDetailRow("카드 번호", "#${item.card.number}")
                CardDetailRow("희귀도", item.card.rarity.ifBlank { "정보 없음" })
                CardDetailRow("출시일", item.card.releaseDate.ifBlank { "정보 없음" })
                CardDetailRow("상태", item.condition)
                CardDetailRow("타입", item.finish)
                CardDetailRow("보유 수량", "${item.quantity}장")
                CardDetailRow(
                    "카드 가격",
                    item.card.marketPrice?.let {
                        formatMoney(
                            state.convertedPrice(item.card),
                            state.displayCurrency,
                        )
                    } ?: "가격 확인 중",
                )
                CardDetailRow("가격 출처", priceSourceLabel(item.card.priceSource))
                CardDetailRow("데이터 출처", item.card.source)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기")
            }
        },
    )
}

@Composable
private fun CardDetailRow(
    label: String,
    value: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
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
    onSearch: (String) -> Unit,
    onAddCard: (RecognizedCard) -> Unit,
    onSortFieldChanged: (SearchSortField) -> Unit,
    onSortDirectionToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = {
                        Text("리자몽, リザードン, Charizard")
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search,
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { onSearch(query) },
                    enabled = query.isNotBlank() && !state.searchBusy,
                    shape = RoundedCornerShape(13.dp),
                    contentPadding = PaddingValues(horizontal = 15.dp),
                    modifier = Modifier.height(56.dp),
                ) {
                    if (state.searchBusy) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(19.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "검색",
                        )
                    }
                }
            }
        }
        if (state.searchResults.isNotEmpty()) {
            item {
                SortControls(
                    labels = SearchSortField.values().map { it.label },
                    selectedIndex = state.searchSortField.ordinal,
                    direction = state.searchSortDirection,
                    onSelected = { index ->
                        onSortFieldChanged(SearchSortField.values()[index])
                    },
                    onDirectionToggle = onSortDirectionToggle,
                )
            }
        }
        item {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = "카드 데이터 검색",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
                Text(
                    text = state.searchMessage,
                    color = if (state.searchMessage.contains("실패")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontSize = 13.sp,
                )
            }
        }
        if (state.searchResults.isEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "한 번의 검색으로 일본판·영문판·한국판을 함께 보여드려요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(22.dp),
                    )
                }
            }
        } else {
            items(
                state.sortedSearchResults,
                key = { "${it.source}:${it.language.code}:${it.id}" },
            ) { card ->
                SearchResultCard(
                    state = state,
                    card = card,
                    busy = state.searchBusy,
                    onAdd = { onAddCard(card) },
                )
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    state: ScannerUiState,
    card: RecognizedCard,
    busy: Boolean,
    onAdd: () -> Unit,
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
            CardArtwork(
                card = card,
                contentDescription = card.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 76.dp, height = 106.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(10.dp),
                    ),
            )
            Spacer(modifier = Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${card.language.label} · #${card.number} · ${card.setName}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                card.marketPrice?.let {
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = "${
                            formatMoney(
                                state.convertedPrice(card),
                                state.displayCurrency,
                            )
                        } · ${priceSourceLabel(card.priceSource)}",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onAdd,
                    enabled = !busy,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text("컬렉션에 추가")
                }
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
    onDisplayCurrencyChanged: (String) -> Unit,
    onScanDebugToggle: (Boolean) -> Unit,
    onScanDebugClear: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
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
            SettingsGroup(title = "스캔 디버그") {
                Text(
                    text = "인식이 빗나가는 카드의 마지막 크롭, 모서리 신뢰도, 후보 경로를 확인합니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SelectChip(
                        label = if (state.scanDebugEnabled) "디버그 켜짐" else "디버그 꺼짐",
                        selected = state.scanDebugEnabled,
                        onClick = { onScanDebugToggle(!state.scanDebugEnabled) },
                    )
                    OutlinedButton(
                        onClick = onScanDebugClear,
                        enabled = state.lastScanDebug != null,
                    ) {
                        Text("기록 지우기")
                    }
                }
                state.lastScanDebug?.let { debug ->
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsRow(
                        label = "최근 경로",
                        value = debug.recognitionPath.ifBlank { "대기" },
                    )
                    Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                    SettingsRow(
                        label = "검출 신뢰도",
                        value = "${(debug.detectionConfidence * 100).toInt()}%",
                        valueColor = if (debug.detectionConfidence >= 0.7) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }
        item {
            SettingsGroup(title = "표시 통화") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    listOf(
                        "JPY" to "일본 엔",
                        "KRW" to "한국 원",
                        "USD" to "미국 달러",
                    ).forEach { (currency, label) ->
                        SelectChip(
                            label = "$currency · $label",
                            selected = state.displayCurrency == currency,
                            onClick = { onDisplayCurrencyChanged(currency) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "모든 카드 가격과 총 가치를 선택한 통화로 환산합니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
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
                    label = "최신 버전",
                    value = state.updateInfo?.latestVersion?.ifBlank { "-" } ?: "-",
                    valueColor = if (state.updateInfo?.isUpdateAvailable == true) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
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
                if (state.updateMessage.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = state.updateMessage,
                        color = if (state.updateMessage.contains("실패") ||
                            state.updateMessage.contains("없습니다")
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 12.sp,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = onCheckForUpdate,
                        enabled = !state.updateBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state.updateBusy) {
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
                        Text("업데이트 확인")
                    }
                    if (state.updateInfo?.isUpdateAvailable == true) {
                        OutlinedButton(
                            onClick = onInstallUpdate,
                            enabled = !state.updateBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("다운로드")
                        }
                    }
                }
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
private fun SortControls(
    labels: List<String>,
    selectedIndex: Int,
    direction: SortDirection,
    onSelected: (Int) -> Unit,
    onDirectionToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
        ) {
            labels.forEachIndexed { index, label ->
                SelectChip(
                    label = label,
                    selected = selectedIndex == index,
                    onClick = { onSelected(index) },
                )
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            IconButton(onClick = onDirectionToggle) {
                Icon(
                    imageVector = if (direction == SortDirection.ASCENDING) {
                        Icons.Rounded.ArrowUpward
                    } else {
                        Icons.Rounded.ArrowDownward
                    },
                    contentDescription = direction.label,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
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
        maximumFractionDigits = if (currency in setOf("USD", "EUR")) 2 else 0
    }
    val symbol = when (currency) {
        "JPY" -> "¥"
        "KRW" -> "₩"
        "USD" -> "$"
        else -> "$currency "
    }
    return "$symbol${formatter.format(value)}"
}

private fun priceSourceLabel(source: String): String = when (source) {
    "yuyu-tei" -> "遊々亭"
    "estimated-rarity" -> "추정가"
    "pokemon-tcg-api" -> "TCGPlayer"
    "pokemon-tcg-api-cardmarket", "cardmarket" -> "Cardmarket"
    "tcgplayer" -> "TCGPlayer"
    else -> "시장가"
}

private fun collectionKey(item: SessionCard): String = item.collectionKey
