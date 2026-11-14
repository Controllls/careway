package com.example.careway.ui

import android.os.SystemClock
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.careway.core.ReactionPhase
import com.example.careway.core.ReactionState
import kotlinx.coroutines.channels.Channel
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** A continuous, flat driving vignette. The animal itself is the only new cue at hazard onset. */
@Composable
internal fun AnimalCrossingScene(reaction: ReactionState, revealAnimal: Boolean, onAnimalDrawn: (Long) -> Unit) {
    val running = reaction.phase in setOf(ReactionPhase.WAITING, ReactionPhase.STIMULUS)
    val animalVisible = revealAnimal || reaction.phase in setOf(ReactionPhase.COMPLETE, ReactionPhase.TIMEOUT)
    var fromRight by remember { mutableStateOf(true) }
    val crossing = remember { Animatable(0f) }
    val road = rememberInfiniteTransition(label = "forest road")
    val roadTravel by road.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1_500, easing = LinearEasing)), label = "road markings")
    var stoppedRoad by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) { if (!running) stoppedRoad = roadTravel }
    LaunchedEffect(reaction.phase, revealAnimal) {
        if (reaction.phase == ReactionPhase.WAITING && !revealAnimal) {
            fromRight = Random.nextBoolean()
            crossing.snapTo(0f)
        } else if (animalVisible && running) {
            crossing.animateTo(1f, tween(900, easing = LinearOutSlowInEasing))
        }
        // Cancellation of this effect on braking freezes the animal in the same scene.
    }

    val frames = remember { Channel<Long>(Channel.CONFLATED) }
    val reportFrame by rememberUpdatedState(onAnimalDrawn)
    LaunchedEffect(frames) { for (at in frames) reportFrame(at) }
    DisposableEffect(frames) { onDispose { frames.close() } }

    Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFFDFEAE2),
        modifier = Modifier.fillMaxWidth().testTag("reaction_stage")) {
        BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))) {
            val sceneHeight = if (maxWidth >= 700.dp) 360.dp else 300.dp
            Box(Modifier.fillMaxWidth().height(sceneHeight).testTag("animal_crossing_scene")) {
                Canvas(Modifier.fillMaxSize().semantics { contentDescription = "숲길 도로와 양옆의 나무" }) {
                    val w = size.width
                    val h = size.height
                    val horizon = h * .32f
                    drawRect(Color(0xFFDDEAE5))
                    drawCircle(Color(0xFFF6F0D8), h * .065f, Offset(w * .74f, h * .19f))
                    drawPath(Path().apply {
                        moveTo(0f, horizon); lineTo(0f, h * .25f)
                        quadraticTo(w * .18f, h * .08f, w * .37f, horizon)
                        quadraticTo(w * .64f, h * .12f, w, h * .25f)
                        lineTo(w, h); lineTo(0f, h); close()
                    }, Color(0xFFB9CFC0))
                    drawRect(Color(0xFF93B39B), Offset(0f, horizon), Size(w, h - horizon))
                    drawPath(Path().apply {
                        moveTo(w * .42f, horizon); lineTo(w * .58f, horizon)
                        lineTo(w, h); lineTo(0f, h); close()
                    }, Color(0xFFC7D0B5))
                    drawPath(Path().apply {
                        moveTo(w * .445f, horizon); lineTo(w * .555f, horizon)
                        lineTo(w * .91f, h); lineTo(w * .09f, h); close()
                    }, Color(0xFF596A6A))
                    drawLine(Color(0xFFE9EADB), Offset(w * .45f, horizon), Offset(w * .115f, h), 3.dp.toPx())
                    drawLine(Color(0xFFE9EADB), Offset(w * .55f, horizon), Offset(w * .885f, h), 3.dp.toPx())
                    val travel = if (running) roadTravel else stoppedRoad
                    repeat(7) { index ->
                        val depth = ((index + travel) / 7f).pow(1.7f)
                        val y = horizon + (h - horizon) * depth
                        drawLine(Color(0xFFE4D5A4), Offset(w * .5f, y),
                            Offset(w * .5f, y + h * (.012f + .075f * depth)),
                            2.dp.toPx() + 5.dp.toPx() * depth, StrokeCap.Round)
                    }
                    listOf(.09f to .64f, .24f to .47f, .36f to .36f, .91f to .61f, .77f to .46f, .64f to .36f)
                        .forEach { (x, y) -> forestTree(Offset(w * x, h * y), h * (if (y > .5f) .31f else .19f)) }
                    // The bushes stay in place; the deer first becomes visible beside their road edge.
                    bush(Offset(w * .14f, h * .79f), h * .115f)
                    bush(Offset(w * .86f, h * .79f), h * .115f)
                    if (animalVisible) {
                        val entry = if (fromRight) .79f else .21f
                        val x = w * (entry + (.53f - entry) * crossing.value)
                        val y = h * (.66f + crossing.value * .035f)
                        val stride = if (running) sin(crossing.value * 19f) else 0f
                        deer(Offset(x, y), h / 310f, fromRight, stride)
                        // Timestamp the actual Canvas draw, not the timer or the start of the entrance animation.
                        if (revealAnimal && reaction.phase == ReactionPhase.WAITING) frames.trySend(SystemClock.elapsedRealtime())
                    }
                    // A small bonnet anchors the first-person view without dashboard detail.
                    drawPath(Path().apply {
                        moveTo(w * .20f, h); quadraticTo(w * .23f, h * .93f, w * .5f, h * .93f)
                        quadraticTo(w * .77f, h * .93f, w * .8f, h); close()
                    }, Color(0xFF273B3F))
                }
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    SceneLabel("숲길 · 동물 횡단")
                    SceneLabel("정차 중 체험")
                }
                if (animalVisible) {
                    Box(Modifier.fillMaxSize().testTag("stimulus").semantics { contentDescription = "도로로 뛰어나온 사슴" })
                }
                val caption = when (reaction.phase) {
                    ReactionPhase.IDLE -> "아래 ‘반응 체크’를 누르면 시작합니다"
                    ReactionPhase.WAITING -> "측정 중 · 잠시 후 동물이 나타납니다"
                    ReactionPhase.STIMULUS -> "지금 브레이크를 밟으세요"
                    ReactionPhase.COMPLETE -> "${reaction.reactionMs} ms  ·  브레이크 반응 기록 완료"
                    ReactionPhase.EARLY -> "동물이 나오기 전에 밟았어요 · 발을 떼고 다시 시작하세요"
                    ReactionPhase.TIMEOUT -> "브레이크 입력이 없었어요 · 쉬었다가 다시 시작하세요"
                    ReactionPhase.CANCELLED -> reaction.message
                }
                Text(caption, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp)
                        .clip(RoundedCornerShape(14.dp)).background(Color(0xEC203238)).padding(horizontal = 22.dp, vertical = 12.dp)
                        .then(when (reaction.phase) {
                            ReactionPhase.COMPLETE -> Modifier.testTag("reaction_result")
                            ReactionPhase.WAITING -> Modifier.testTag("reaction_waiting")
                            ReactionPhase.IDLE -> Modifier.testTag("reaction_idle")
                            else -> Modifier
                        }))
            }
        }
    }
}

@Composable
private fun SceneLabel(text: String) {
    Text(text, color = Color(0xFF314B40), fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xEAF6F8F0)).padding(horizontal = 14.dp, vertical = 8.dp))
}

private fun DrawScope.forestTree(base: Offset, height: Float) {
    drawLine(Color(0xFF6A7965), Offset(base.x, base.y - height * .32f), base, height * .07f, StrokeCap.Round)
    drawPath(Path().apply {
        moveTo(base.x, base.y - height); lineTo(base.x - height * .38f, base.y - height * .22f)
        lineTo(base.x + height * .38f, base.y - height * .22f); close()
    }, Color(0xFF527E67))
    drawPath(Path().apply {
        moveTo(base.x, base.y - height); lineTo(base.x, base.y - height * .22f)
        lineTo(base.x + height * .38f, base.y - height * .22f); close()
    }, Color(0xFF426B59))
}

private fun DrawScope.bush(center: Offset, radius: Float) {
    drawOval(Color(0xFF759A7D), Offset(center.x - radius * 1.4f, center.y - radius * .4f), Size(radius * 2.8f, radius))
    drawCircle(Color(0xFF628C71), radius * .8f, Offset(center.x - radius * .6f, center.y))
    drawCircle(Color(0xFF6C9676), radius, Offset(center.x + radius * .3f, center.y - radius * .15f))
}

private fun DrawScope.deer(center: Offset, unit: Float, facingLeft: Boolean, stride: Float) {
    translate(center.x, center.y) {
        scale(if (facingLeft) -unit else unit, unit, Offset.Zero) {
            val coat = Color(0xFFCD9863)
            val dark = Color(0xFF8D6344)
            drawOval(Color(0x30203934), Offset(-65f, 45f), Size(140f, 18f))
            listOf(-31f, -13f, 19f, 34f).forEachIndexed { i, x ->
                val step = stride * (if (i % 2 == 0) 13f else -13f)
                drawLine(if (i % 2 == 0) dark else coat, Offset(x, 8f), Offset(x + step, 46f), 9f, StrokeCap.Round)
                drawLine(Color(0xFF5B4C42), Offset(x + step - 3f, 48f), Offset(x + step + 5f, 48f), 7f, StrokeCap.Round)
            }
            drawOval(coat, Offset(-51f, -22f), Size(99f, 49f))
            drawOval(Color(0xFFE8C8A1), Offset(-24f, 6f), Size(60f, 16f))
            drawLine(coat, Offset(31f, 2f), Offset(45f, -38f), 24f, StrokeCap.Round)
            drawOval(coat, Offset(32f, -59f), Size(44f, 31f))
            drawOval(Color(0xFFE8C8A1), Offset(57f, -43f), Size(25f, 15f))
            drawPath(Path().apply { moveTo(40f, -52f); lineTo(24f, -72f); quadraticTo(43f, -74f, 49f, -54f); close() }, coat)
            drawPath(Path().apply { moveTo(49f, -57f); lineTo(59f, -80f); quadraticTo(70f, -65f, 59f, -51f); close() }, coat)
            drawLine(dark, Offset(47f, -61f), Offset(42f, -87f), 4f, StrokeCap.Round)
            drawLine(dark, Offset(44f, -76f), Offset(33f, -84f), 4f, StrokeCap.Round)
            drawLine(dark, Offset(43f, -82f), Offset(51f, -91f), 4f, StrokeCap.Round)
            drawCircle(Color(0xFF263B35), 3.5f, Offset(62f, -47f))
            drawCircle(Color(0xFF423F35), 4f, Offset(79f, -37f))
            drawLine(Color(0xFFF1DDC0), Offset(-46f, -3f), Offset(-62f, -18f), 10f, StrokeCap.Round)
            listOf(Offset(-28f, -9f), Offset(-10f, -13f), Offset(7f, -8f)).forEach { drawCircle(Color(0xFFF0DCBA), 3f, it) }
        }
    }
}
