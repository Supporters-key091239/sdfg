package com.sync.app.glass

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * SYNC 의 UI 본체는 WebView(assets/www 의 html/css/js)이며, JS 는 AndroidBridge 로
 * 백엔드(검색/가사/다운로드/재생 상태)와 실시간 통신한다. 이 파일은 그 구조를 건드리지 않고,
 * 같은 Activity 안에서 WebView 를 Kyant0 backdrop 의 "배경(layerBackdrop)"으로 쓰고
 * 그 위에 네이티브 Liquid Glass 표면을 올린다.
 *
 *  - WebView 는 Compose 의 AndroidView 로 호스팅되고 layerBackdrop 으로 기록된다.
 *  - JS(liquid-glass.js)는 글래스가 필요한 DOM 요소의 위치/모양/터치 상태만 보고한다.
 *  - 글래스 표면은 포인터 입력을 소비하지 않으므로 터치는 그대로 WebView 로 전달된다.
 *    (예외: 하단 내비의 슬라이딩 필 — 레퍼런스처럼 드래그로 탭 전환)
 */
fun interface GlassCallbacks {
    fun onTabSelected(index: Int)
}

internal data class GlassItem(
    val id: String,
    val x: Float, val y: Float, val w: Float, val h: Float,
    val r: Float,
    val px: Float?, val py: Float?,
    val tabX: Float, val tabW: Float, val tabs: Int, val idx: Int
)

internal data class GlassFrame(val items: List<GlassItem>) {
    companion object { val Empty = GlassFrame(emptyList()) }
}

object GlassHost {

    internal val frame = mutableStateOf(GlassFrame.Empty)
    private val main = Handler(Looper.getMainLooper())

    @JvmStatic
    fun attach(activity: ComponentActivity, webView: WebView, callbacks: GlassCallbacks) {
        val compose = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { GlassRoot(webView, callbacks) }
        }
        activity.setContentView(compose)
    }

    /** JS → (AndroidBridge.glassFrame) → 여기. 임의 스레드에서 호출되므로 파싱 후 메인 스레드로 반영. */
    @JvmStatic
    fun update(json: String) {
        val parsed = try {
            val arr = JSONObject(json).optJSONArray("items")
            val list = ArrayList<GlassItem>()
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    GlassItem(
                        id = o.getString("id"),
                        x = o.getDouble("x").toFloat(), y = o.getDouble("y").toFloat(),
                        w = o.getDouble("w").toFloat(), h = o.getDouble("h").toFloat(),
                        r = o.optDouble("r", 0.0).toFloat(),
                        px = if (o.has("px")) o.getDouble("px").toFloat() else null,
                        py = if (o.has("py")) o.getDouble("py").toFloat() else null,
                        tabX = o.optDouble("tx", 0.0).toFloat(),
                        tabW = o.optDouble("tw", 0.0).toFloat(),
                        tabs = o.optInt("tabs", 0),
                        idx = o.optInt("idx", 0)
                    )
                )
            }
            GlassFrame(list)
        } catch (e: Exception) {
            return
        }
        main.post { frame.value = parsed }
    }
}

@Composable
private fun GlassRoot(webView: WebView, callbacks: GlassCallbacks) {
    // WebView 는 프레임당 "한 번만" 그려서 webLayer 에 기록하고, 화면 표시와 backdrop(글래스가
    // 굴절시키는 배경) 모두 이 레이어를 재생(replay)한다.
    // (WebView 의 draw() 를 한 프레임에 두 번 호출하면 렌더러가 빈 화면을 낼 수 있어 피함)
    val webLayer = rememberGraphicsLayer()
    val backdrop = rememberLayerBackdrop { drawLayer(webLayer) }
    Box(Modifier.fillMaxSize().background(Color(0xFF08080D))) {
        AndroidView(
            factory = { webView },
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .drawWithContent {
                    webLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(webLayer)
                }
        )
        GlassOverlay(backdrop, callbacks)
    }
}

@Composable
private fun GlassOverlay(backdrop: Backdrop, callbacks: GlassCallbacks) {
    val frame = GlassHost.frame.value
    for (item in frame.items) {
        key(item.id) {
            GlassSurface(item, backdrop)
            if (item.id == "nav" && item.tabs > 1) NavPill(item, backdrop, callbacks)
        }
    }
}

/* ───────────────────────── 일반 글래스 표면 ─────────────────────────
   검색 캡슐 / 미니 플레이어 바 / 하단 내비 : LiquidButton · LiquidBottomTabs 의 파라미터
   컨텍스트 메뉴 / 다이얼로그 / 모달     : DialogContent 의 파라미터
   (WebView 안의 글자·아이콘이 흐려지지 않도록 내부 blur 는 쓰지 않는다 — 가장자리 렌즈 굴절 중심) */
@Composable
private fun GlassSurface(item: GlassItem, backdrop: Backdrop) {
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(220)) }

    val highlight = remember(scope) { InteractiveHighlight(scope) }
    val pressed = item.px != null
    LaunchedEffect(pressed) {
        if (pressed) highlight.press(Offset(item.px!! * density, item.py!! * density))
        else highlight.release()
    }
    LaunchedEffect(item.px, item.py) {
        if (item.px != null) highlight.move(Offset(item.px * density, item.py!! * density))
    }

    val isCapsule = item.id == "search" || item.id == "nav" || item.id == "bar"
    val growDp = when (item.id) { "nav" -> 8f; "bar" -> 6f; else -> 4f }

    Box(
        Modifier
            .offset { IntOffset((item.x * density).roundToInt(), (item.y * density).roundToInt()) }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { if (isCapsule) Capsule() else RoundedRectangle(item.r.dp) },
                effects = {
                    when (item.id) {
                        "nav" -> { vibrancy(); lens(24f.dp.toPx(), 24f.dp.toPx()) }
                        "bar" -> { vibrancy(); lens(16f.dp.toPx(), 28f.dp.toPx()) }
                        "search" -> { vibrancy(); lens(12f.dp.toPx(), 24f.dp.toPx()) }
                        else -> {
                            colorControls(brightness = 0f, saturation = 1.5f)
                            lens(24f.dp.toPx(), 48f.dp.toPx(), depthEffect = true)
                        }
                    }
                },
                highlight = { if (isCapsule) Highlight.Default else Highlight.Plain },
                layerBlock = {
                    alpha = appear.value
                    if (isCapsule) {
                        val p = highlight.pressProgress
                        val s = lerp(1f, 1f + growDp.dp.toPx() / size.height, p)
                        scaleX = s
                        scaleY = s
                    }
                },
                onDrawSurface = {
                    drawRect(
                        if (isCapsule) Color.White.copy(0.04f)
                        else Color(0xFF121212).copy(0.20f)
                    )
                }
            )
            .then(highlight.modifier)
            .size(item.w.dp, item.h.dp)
    )
}

/* ───────────────────────── 하단 내비 슬라이딩 필 ─────────────────────────
   LiquidBottomTabs 의 슬라이더(탭 인디케이터) 파라미터/애니메이션을 그대로 사용.
   탭 아이콘·라벨은 WebView(DOM)가 그대로 그리며, 필은 그 위에서 렌즈로 굴절시킨다. */
@Composable
private fun NavPill(nav: GlassItem, backdrop: Backdrop, callbacks: GlassCallbacks) {
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val tabs = nav.tabs
    val tabWidthPx = rememberUpdatedState(nav.tabW * density)
    val callback = rememberUpdatedState(callbacks)

    val damped = remember(scope, tabs) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = nav.idx.toFloat(),
            valueRange = 0f..(tabs - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            onDragStarted = {},
            onDragStopped = {
                val target = targetValue.fastRoundToInt().fastCoerceIn(0, tabs - 1)
                animateToValue(target.toFloat())
                callback.value.onTabSelected(target)
            },
            onDrag = { _, dragAmount ->
                updateValue(
                    (targetValue + dragAmount.x / tabWidthPx.value)
                        .fastCoerceIn(0f, (tabs - 1).toFloat())
                )
            }
        )
    }
    // DOM 에서 탭이 바뀌면(탭 클릭) 필이 스프링으로 따라간다
    LaunchedEffect(nav.idx) {
        if (abs(damped.targetValue - nav.idx) > 0.01f) damped.animateToValue(nav.idx.toFloat())
    }

    val insetDp = 4f
    Box(
        Modifier
            .offset {
                IntOffset(
                    ((nav.tabX + insetDp) * density).roundToInt(),
                    ((nav.y + insetDp) * density).roundToInt()
                )
            }
            .graphicsLayer { translationX = damped.value * tabWidthPx.value }
            .then(damped.modifier)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    val progress = damped.pressProgress
                    lens(10f.dp.toPx() * progress, 14f.dp.toPx() * progress, chromaticAberration = true)
                },
                highlight = { Highlight.Default.copy(alpha = damped.pressProgress) },
                shadow = { Shadow(alpha = damped.pressProgress) },
                innerShadow = {
                    val progress = damped.pressProgress
                    InnerShadow(radius = 8f.dp * progress, alpha = progress)
                },
                layerBlock = {
                    scaleX = damped.scaleX
                    scaleY = damped.scaleY
                    val velocity = damped.velocity / 10f
                    scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                    scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                },
                onDrawSurface = {
                    val progress = damped.pressProgress
                    drawRect(Color.White.copy(0.10f), alpha = 1f - progress)
                    drawRect(Color.Black.copy(alpha = 0.03f * progress))
                }
            )
            .size((nav.tabW - insetDp * 2).dp, (nav.h - insetDp * 2).dp)
    )
}
