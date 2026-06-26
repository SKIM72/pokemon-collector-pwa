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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pokebinder.scanner.model.CardDetection
import com.pokebinder.scanner.model.CardLanguage
import com.pokebinder.scanner.model.FrameProbe
import com.pokebinder.scanner.model.RecognizedCard
import com.pokebinder.scanner.model.ScanDebugSnapshot
import com.pokebinder.scanner.model.ScanPhase
import com.pokebinder.scanner.model.ScannerUiState
import com.pokebinder.scanner.model.SessionCard
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ScannerScreen(
    state: ScannerUiState,
    onLanguageSelected: (CardLanguage) -> Unit,
    onFrameProbe: (FrameProbe) -> Unit,
    onStableFrame: (ByteArray) -> Unit,
    onCandidateSelected: (RecognizedCard) -> Unit,
    onConfirmScan: () -> Unit,
    onNextScan: () -> Unit,
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
    var torchEnabled by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1_200)
            focusPoint = null
        }
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
                torchEnabled = torchEnabled,
                onTorchAvailabilityChanged = {
                    torchAvailable = it
                    if (!it) torchEnabled = false
                },
                onFocusPointChanged = { focusPoint = it },
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
            torchAvailable = torchAvailable,
            torchEnabled = torchEnabled,
            onTorchToggle = { torchEnabled = !torchEnabled },
            onClose = onClose,
        )

        DetectedCardOverlay(
            detection = state.probe.detection,
            phase = state.phase,
            modifier = Modifier.fillMaxSize(),
        )
        focusPoint?.let { point ->
            FocusIndicator(
                point = point,
                modifier = Modifier.fillMaxSize(),
            )
        }

        state.currentMatch?.marketPrice?.let {
            PriceBadge(
                price = state.convertedPrice(state.currentMatch),
                currency = state.displayCurrency,
                priceSource = state.currentMatch.priceSource,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp, bottom = 120.dp),
            )
        }

        ScannerBottomPanel(
            state = state,
            onCandidateSelected = onCandidateSelected,
            onConfirmScan = onConfirmScan,
            onNextScan = onNextScan,
            onClearSession = onClearSession,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ScannerTopBar(
    state: ScannerUiState,
    onLanguageSelected: (CardLanguage) -> Unit,
    torchAvailable: Boolean,
    torchEnabled: Boolean,
    onTorchToggle: () -> Unit,
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
                    text = "카드 영역 자동 탐지",
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
                color = Color(0xDD1B222B),
                shape = CircleShape,
            ) {
                IconButton(
                    onClick = onTorchToggle,
                    enabled = torchAvailable,
                ) {
                    Icon(
                        imageVector = if (torchEnabled) {
                            Icons.Rounded.FlashOn
                        } else {
                            Icons.Rounded.FlashOff
                        },
                        contentDescription = if (torchEnabled) "플래시 끄기" else "플래시 켜기",
                        tint = if (torchAvailable) {
                            if (torchEnabled) MaterialTheme.colorScheme.primary else Color.White
                        } else {
                            Color(0xFF59616B)
                        },
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
private fun FocusIndicator(
    point: Offset,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        drawCircle(
            color = Color.White,
            radius = 26.dp.toPx(),
            center = point,
            style = Stroke(width = 2.dp.toPx()),
        )
        drawCircle(
            color = primary,
            radius = 4.dp.toPx(),
            center = point,
        )
    }
}

@Composable
private fun DetectedCardOverlay(
    detection: CardDetection?,
    phase: ScanPhase,
    modifier: Modifier = Modifier,
) {
    val guideColor = when (phase) {
        ScanPhase.ANALYZING -> MaterialTheme.colorScheme.secondary
        ScanPhase.MATCHED -> MaterialTheme.colorScheme.primary
        ScanPhase.ERROR -> MaterialTheme.colorScheme.error
        else -> Color(0xFF78EBC2)
    }

    Canvas(modifier = modifier) {
        if (detection == null || detection.corners.size != 4) return@Canvas
        val sourceWidth = detection.frameWidth.toFloat().coerceAtLeast(1f)
        val sourceHeight = detection.frameHeight.toFloat().coerceAtLeast(1f)
        val scale = maxOf(size.width / sourceWidth, size.height / sourceHeight)
        val offsetX = (size.width - sourceWidth * scale) / 2f
        val offsetY = (size.height - sourceHeight * scale) / 2f
        val points = detection.corners.map { point ->
            Offset(
                x = offsetX + point.x * sourceWidth * scale,
                y = offsetY + point.y * sourceHeight * scale,
            )
        }
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path, guideColor.copy(alpha = 0.12f))
        drawPath(path, guideColor, style = Stroke(width = 4.dp.toPx()))
        points.forEach { point ->
            drawCircle(
                color = Color.White,
                radius = 6.dp.toPx(),
                center = point,
            )
            drawCircle(
                color = guideColor,
                radius = 3.5.dp.toPx(),
                center = point,
            )
        }
    }
}

@Composable
private fun ScannerBottomPanel(
    state: ScannerUiState,
    onCandidateSelected: (RecognizedCard) -> Unit,
    onConfirmScan: () -> Unit,
    onNextScan: () -> Unit,
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
                        CardArtwork(
                            card = card,
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
                                    formatMoney(
                                        state.convertedPrice(card),
                                        state.displayCurrency,
                                    )
                                } ?: "가격 확인 중",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            )
                            Text(
                                text = "${state.displayCurrency} · ${
                                    priceSourceLabel(card.priceSource)
                                }",
                                color = Color(0xFF85909D),
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }

            if (state.scanAwaitingConfirmation) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = onNextScan,
                        enabled = !state.scanSaving,
                        shape = RoundedCornerShape(13.dp),
                        modifier = Modifier
                            .weight(0.8f)
                            .height(50.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("재촬영", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onConfirmScan,
                        enabled = !state.scanSaving,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        shape = RoundedCornerShape(13.dp),
                        modifier = Modifier
                            .weight(1.2f)
                            .height(50.dp),
                    ) {
                        if (state.scanSaving) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            Text("컬렉션에 1장 추가", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (state.scanAdded) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onNextScan,
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    Text("다음 카드 스캔", fontWeight = FontWeight.Bold)
                }
            }

            if (state.candidates.size > 1) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "일치 후보",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(7.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    state.candidates.forEach { candidate ->
                        CandidateCardItem(
                            card = candidate,
                            selected = candidate.id == state.currentMatch?.id,
                            onClick = { onCandidateSelected(candidate) },
                        )
                    }
                }
            }

            if (state.recentScanCards.isNotEmpty()) {
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
                    state.recentScanCards.forEach { item ->
                        SessionCardItem(item)
                    }
                }
            }

            if (state.scanDebugEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                ScanDebugPanel(
                    debug = state.lastScanDebug,
                    probe = state.probe,
                )
            }
        }
    }
}

@Composable
private fun ScanDebugPanel(
    debug: ScanDebugSnapshot?,
    probe: FrameProbe,
) {
    Surface(
        color = Color(0xFF121820),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "스캔 디버그",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (debug == null) {
                Text(
                    text = "카드를 비추면 검출값이 표시됩니다.",
                    color = Color(0xFFADB6C2),
                    fontSize = 12.sp,
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (debug.cropImageUrl != null) {
                    SubcomposeAsyncImage(
                        model = debug.cropImageUrl,
                        contentDescription = "마지막 스캔 크롭",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 50.dp, height = 70.dp)
                            .background(Color(0xFF252D37), RoundedCornerShape(7.dp)),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    DebugLine("경로", debug.recognitionPath.ifBlank { "대기" })
                    DebugLine(
                        "검출",
                        "${(debug.detectionConfidence * 100).toInt()}% · 안정 ${debug.stableFrames}",
                    )
                    DebugLine(
                        "프레임",
                        "밝기 ${debug.brightness.toInt()} · 흔들림 ${
                            String.format(Locale.US, "%.3f", debug.motion)
                        }",
                    )
                    if (debug.errorMessage.isNotBlank()) {
                        DebugLine("오류", debug.errorMessage)
                    } else if (debug.recognizedCardName.isNotBlank()) {
                        DebugLine(
                            "선택",
                            "${debug.recognizedCardName} #${debug.recognizedNumber} ${
                                (debug.recognizedConfidence * 100).toInt()
                            }%",
                        )
                    }
                }
            }
            if (debug.cornerSummary.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                DebugLine("모서리", debug.cornerSummary)
            }
            val candidates = debug.candidates.take(3)
            if (candidates.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                candidates.forEach { candidate ->
                    Text(
                        text = "${candidate.name} #${candidate.number} · ${
                            (candidate.confidence * 100).toInt()
                        }% · ${candidate.priceSource}",
                        color = Color(0xFFCAD2DC),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else if (probe.detection != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "후보 대기 중",
                    color = Color(0xFF85909D),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun DebugLine(
    label: String,
    value: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label ",
            color = Color(0xFF85909D),
            fontSize = 11.sp,
        )
        Text(
            text = value,
            color = Color(0xFFCAD2DC),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CandidateCardItem(
    card: RecognizedCard,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        Color(0xFF303945)
    }
    Surface(
        color = Color(0xFF171D24),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .width(132.dp)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(7.dp),
        ) {
            CardArtwork(
                card = card,
                contentDescription = card.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 34.dp, height = 47.dp)
                    .background(Color(0xFF252D37), RoundedCornerShape(5.dp)),
            )
            Spacer(modifier = Modifier.width(7.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.name,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${(card.confidence * 100).toInt()}%",
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color(0xFFADB6C2)
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun SessionCardItem(
    item: SessionCard,
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
            CardArtwork(
                card = item.card,
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
                Text(
                    text = "${item.quantity}장",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun PriceBadge(
    price: Double,
    currency: String,
    priceSource: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xE611161C),
        shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
            Text(
                text = priceSourceLabel(priceSource).uppercase(Locale.ROOT),
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

private fun priceSourceLabel(source: String): String = when (source) {
    "yuyu-tei" -> "遊々亭"
    "estimated-rarity" -> "추정가"
    "pokemon-tcg-api" -> "TCGPlayer"
    "pokemon-tcg-api-cardmarket", "cardmarket" -> "Cardmarket"
    "tcgplayer" -> "TCGPlayer"
    else -> "시장가"
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
        maximumFractionDigits = if (currency in setOf("USD", "EUR")) 2 else 0
    }
    val symbol = when (currency) {
        "JPY" -> "¥"
        "KRW" -> "₩"
        "USD" -> "$"
        else -> "$currency "
    }
    return "$symbol${numberFormat.format(value)}"
}
