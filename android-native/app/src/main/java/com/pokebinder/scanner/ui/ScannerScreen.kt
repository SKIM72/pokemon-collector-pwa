package com.pokebinder.scanner.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.pokebinder.scanner.model.CardLanguage
import com.pokebinder.scanner.model.ScanPhase
import com.pokebinder.scanner.model.ScannerUiState
import com.pokebinder.scanner.model.SessionCard
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ScannerScreen(
    state: ScannerUiState,
    onLanguageSelected: (CardLanguage) -> Unit,
    onFrameProbe: (com.pokebinder.scanner.model.FrameProbe) -> Unit,
    onStableFrame: (ByteArray) -> Unit,
    onQuantityChanged: (String, Int) -> Unit,
    onClearSession: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (hasCameraPermission) {
            CameraPreview(
                onProbe = onFrameProbe,
                onStableFrame = onStableFrame,
            )
        } else {
            PermissionPrompt(
                onRequestPermission = {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
            )
        }

        ScannerShade()

        ScannerTopBar(
            state = state,
            onLanguageSelected = onLanguageSelected,
            onClose = onClose,
        )

        CardGuide(
            phase = state.phase,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.72f)
                .aspectRatio(63f / 88f),
        )

        state.currentMatch?.marketPrice?.let { price ->
            PriceBadge(
                price = price,
                currency = state.currentMatch.currency,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp, bottom = 120.dp),
            )
        }

        ScannerBottomPanel(
            state = state,
            onQuantityChanged = onQuantityChanged,
            onClearSession = onClearSession,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ScannerTopBar(
    state: ScannerUiState,
    onLanguageSelected: (CardLanguage) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xB20A0D10))
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "LIVE SCAN",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "카드 자동 인식",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Surface(
                color = Color(0xDD1B222B),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = "${state.totalCards}장",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = formatMoney(state.runningTotal, state.displayCurrency),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                color = Color(0xEEEDF1F4),
                shape = CircleShape,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "스캐너 닫기",
                        tint = Color(0xFF11161C),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            CardLanguage.values().forEach { language ->
                val selected = state.language == language
                Surface(
                    color = if (selected) MaterialTheme.colorScheme.primary else Color(0xCC1B222B),
                    contentColor = if (selected) Color(0xFF06150F) else Color.White,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onLanguageSelected(language) },
                ) {
                    Text(
                        text = language.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 9.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun CardGuide(
    phase: ScanPhase,
    modifier: Modifier = Modifier,
) {
    val guideColor = when (phase) {
        ScanPhase.ANALYZING -> MaterialTheme.colorScheme.secondary
        ScanPhase.MATCHED -> MaterialTheme.colorScheme.primary
        ScanPhase.ERROR -> MaterialTheme.colorScheme.error
        else -> Color(0xFF78EBC2)
    }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val segment = size.width * 0.2f
            val stroke = 6.dp.toPx()
            val radius = 28.dp.toPx()

            drawArc(
                color = guideColor,
                startAngle = 180f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset.Zero,
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawLine(guideColor, Offset(radius, 0f), Offset(segment, 0f), stroke, StrokeCap.Round)
            drawLine(guideColor, Offset(0f, radius), Offset(0f, segment), stroke, StrokeCap.Round)

            drawArc(
                color = guideColor,
                startAngle = 270f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(size.width - radius * 2, 0f),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawLine(
                guideColor,
                Offset(size.width - segment, 0f),
                Offset(size.width - radius, 0f),
                stroke,
                StrokeCap.Round,
            )
            drawLine(
                guideColor,
                Offset(size.width, radius),
                Offset(size.width, segment),
                stroke,
                StrokeCap.Round,
            )

            drawArc(
                color = guideColor,
                startAngle = 90f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(0f, size.height - radius * 2),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawLine(
                guideColor,
                Offset(0f, size.height - segment),
                Offset(0f, size.height - radius),
                stroke,
                StrokeCap.Round,
            )
            drawLine(
                guideColor,
                Offset(radius, size.height),
                Offset(segment, size.height),
                stroke,
                StrokeCap.Round,
            )

            drawArc(
                color = guideColor,
                startAngle = 0f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(size.width - radius * 2, size.height - radius * 2),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawLine(
                guideColor,
                Offset(size.width - segment, size.height),
                Offset(size.width - radius, size.height),
                stroke,
                StrokeCap.Round,
            )
            drawLine(
                guideColor,
                Offset(size.width, size.height - segment),
                Offset(size.width, size.height - radius),
                stroke,
                StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun ScannerBottomPanel(
    state: ScannerUiState,
    onQuantityChanged: (String, Int) -> Unit,
    onClearSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xF20C1015),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.phase == ScanPhase.ANALYZING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = when (state.phase) {
                            ScanPhase.ERROR -> MaterialTheme.colorScheme.error
                            ScanPhase.MATCHED -> MaterialTheme.colorScheme.primary
                            else -> Color(0xFFB8C0CB)
                        },
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = state.statusMessage,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
            }

            state.currentMatch?.let { card ->
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = Color(0xFF171D24),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(10.dp),
                    ) {
                        AsyncImage(
                            model = card.imageUrl,
                            contentDescription = card.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 54.dp, height = 75.dp)
                                .background(Color(0xFF252D37), RoundedCornerShape(8.dp)),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = card.name,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${card.setName} · #${card.number}",
                                color = Color(0xFFADB6C2),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = "일치 ${(card.confidence * 100).toInt()}%",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = card.marketPrice?.let {
                                    formatMoney(it, card.currency)
                                } ?: "가격 확인 중",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            )
                            Text(
                                text = card.currency,
                                color = Color(0xFF85909D),
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }

            if (state.sessionCards.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "이번 스캔",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onClearSession,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = "스캔 목록 비우기",
                            tint = Color(0xFF9DA7B3),
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    state.sessionCards.forEach { item ->
                        SessionCardItem(item, onQuantityChanged)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCardItem(
    item: SessionCard,
    onQuantityChanged: (String, Int) -> Unit,
) {
    Surface(
        color = Color(0xFF171D24),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.width(178.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp),
        ) {
            AsyncImage(
                model = item.card.imageUrl,
                contentDescription = item.card.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 40.dp, height = 56.dp)
                    .background(Color(0xFF252D37), RoundedCornerShape(6.dp)),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.card.name,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallQuantityButton(
                        icon = Icons.Rounded.Remove,
                        label = "수량 줄이기",
                        onClick = { onQuantityChanged(item.card.id, -1) },
                    )
                    Text(
                        text = item.quantity.toString(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 5.dp),
                    )
                    SmallQuantityButton(
                        icon = Icons.Rounded.Add,
                        label = "수량 늘리기",
                        onClick = { onQuantityChanged(item.card.id, 1) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallQuantityButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(28.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun PriceBadge(
    price: Double,
    currency: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xE611161C),
        shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
            Text(
                text = "MARKET",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = formatMoney(price, currency),
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PermissionPrompt(onRequestPermission: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
    ) {
        Text(
            text = "카드 스캔을 위해 카메라 권한이 필요합니다.",
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = onRequestPermission,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text("카메라 권한 허용")
        }
    }
}

@Composable
private fun ScannerShade() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.12f)
            .background(Color(0x66000000)),
    )
}

private fun formatMoney(value: Double, currency: String): String {
    if (value <= 0.0) return "-"
    val numberFormat = NumberFormat.getNumberInstance(Locale.KOREA).apply {
        maximumFractionDigits = if (currency == "USD") 2 else 0
    }
    val symbol = when (currency) {
        "JPY" -> "¥"
        "KRW" -> "₩"
        "USD" -> "$"
        else -> "$currency "
    }
    return "$symbol${numberFormat.format(value)}"
}
