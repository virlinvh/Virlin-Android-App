package com.virlin.app.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.orb.OrbInteractionParameters
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

const val VirlinOrbTestTag = "virlin_orb"
const val VirlinOrbContentDescription = "Open Virlin Agent"

/**
 * Coarse Orb presentation states (Orb V2). Kept for future mapping; production drives the Orb
 * through [OrbInteractionParameters] (the accepted interaction-state model) and uses [Idle] here.
 * A state only changes liquid speed and glow strength — never the palette.
 */
enum class VirlinOrbState(val speed: Float, val glow: Float) {
    Idle(1.0f, 0.13f), Listening(0.82f, 0.19f), Processing(0.68f, 0.17f), Ready(0.90f, 0.18f)
}

// ---------------------------------------------------------------- palette (restrained: glass · mint · natural green · a pinch of pale yellow)
private val GlassLight = Color(0xFFF9FFFB)
private val GlassMid = Color(0xF4EDF8F0)
private val GlassEdge = Color(0xE9DDF2E3)
private val MintWash = Color(0x716FDEA5)
private val MintWashEdge = Color(0x2858C58A)
private val MintBody = Color(0xB94EC887)
private val MintBodyEdge = Color(0x754EC887)
private val GreenBody = Color(0xA52FA96F)
private val GreenBodyEdge = Color(0x572FA96F)
private val PaleMintBody = Color(0x8BBCEFCF)
private val PaleMintBodyEdge = Color(0x49BCEFCF)
private val Yellow = Color(0x70E9EE8C)
private val YellowEdge = Color(0x24E9EE8C)
private val Glow = Color(0x3D4CCB84)
private val GlowEdge = Color(0x1A4CCB84)

private const val TWO_PI = (PI * 2).toFloat()

/**
 * Stitch liquid Orb (AGSL port of the design's WebGL fragment shader, `ANIMATION_13`): domain-warped
 * simplex fbm with a zig-zag flow, spherical diffuse lighting, pale matcha / pistachio palette
 * (#D7ECC7 family), soft fresnel rim, feathered edge — no white specular hot-spots. `u_time` is the
 * Orb's integrated phase time so interaction states bend its speed without a jump.
 */
private const val STITCH_LIQUID_AGSL = """
uniform float u_time;
uniform float2 u_resolution;

float3 mod289(float3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
float2 mod289(float2 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
float3 permute(float3 x) { return mod289(((x * 34.0) + 1.0) * x); }

float snoise(float2 v) {
    const float4 C = float4(0.211324865405187, 0.366025403784439, -0.577350269189626, 0.024390243902439);
    float2 i = floor(v + dot(v, C.yy));
    float2 x0 = v - i + dot(i, C.xx);
    float2 i1 = (x0.x > x0.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);
    float4 x12 = x0.xyxy + C.xxzz;
    x12.xy -= i1;
    i = mod289(i);
    float3 p = permute(permute(i.y + float3(0.0, i1.y, 1.0)) + i.x + float3(0.0, i1.x, 1.0));
    float3 m = max(0.5 - float3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), 0.0);
    m = m * m; m = m * m;
    float3 x = 2.0 * fract(p * C.www) - 1.0;
    float3 h = abs(x) - 0.5;
    float3 ox = floor(x + 0.5);
    float3 a0 = x - ox;
    m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
    float3 g;
    g.x = a0.x * x0.x + h.x * x0.y;
    g.yz = a0.yz * x12.xz + h.yz * x12.yw;
    return 130.0 * dot(m, g);
}

float fbm(float2 uv) {
    float total = 0.0;
    float amp = 0.52;
    float freq = 1.0;
    for (int i = 0; i < 4; i++) {
        total += snoise(uv * freq) * amp;
        freq *= 2.15;
        amp *= 0.48;
    }
    return total;
}

half4 main(float2 fragCoord) {
    float2 uv = (fragCoord - 0.5 * u_resolution) / min(u_resolution.x, u_resolution.y) * 2.0;
    uv.y = -uv.y;
    float dist = length(uv);
    if (dist > 1.02) { return half4(0.0); }
    float orbMask = 1.0 - smoothstep(0.88, 1.0, dist);

    float t = u_time * 0.85;
    float zigZagX = asin(sin(t * 1.2 + uv.y * 3.5)) * 0.45;
    float zigZagY = sin(t * 0.6) * 0.2;
    float2 zigZagFlow = float2(zigZagX, zigZagY);

    float2 q = float2(
        fbm(uv * 2.0 + zigZagFlow + float2(0.0, sin(t * 0.8) * 0.3)),
        fbm(uv * 2.0 - zigZagFlow + float2(5.2, cos(t * 0.7) * 0.3))
    );
    float2 r = float2(
        fbm(uv * 2.4 + 2.8 * q + float2(zigZagX * 1.5, t * 0.2)),
        fbm(uv * 2.4 + 2.8 * q + float2(-zigZagX * 1.5, -t * 0.2))
    );
    float fluidFlow = fbm(uv * 1.8 + 2.4 * r + zigZagFlow);

    float z = sqrt(max(0.0, 1.0 - dist * dist));
    float3 normal = float3(uv.x, uv.y, z);

    float3 colBaseGreen    = float3(0.80, 0.92, 0.74);
    float3 colSoftSage     = float3(0.72, 0.88, 0.67);
    float3 colLuminousMint = float3(0.88, 0.96, 0.83);
    float3 colDeepTone     = float3(0.60, 0.80, 0.56);

    float liquidGrad = smoothstep(-0.35, 0.75, fluidFlow);
    float3 liquidColor = mix(colSoftSage, colBaseGreen, liquidGrad);
    liquidColor = mix(liquidColor, colLuminousMint, smoothstep(0.15, 0.85, r.x * 0.5 + 0.5));
    liquidColor = mix(liquidColor, colDeepTone, smoothstep(0.4, 0.95, q.y * 0.5 + 0.5) * 0.25);

    float3 lightDir = normalize(float3(-0.3, 0.4, 0.85));
    float diffuse = clamp(dot(normal, lightDir), 0.0, 1.0);
    float innerGlow = pow(1.0 - dist, 1.5) * 0.35;
    liquidColor += colLuminousMint * innerGlow * 0.25;
    float fresnel = pow(1.0 - z, 2.5);
    liquidColor += colLuminousMint * fresnel * 0.2;
    float3 finalColor = liquidColor * (0.88 + 0.15 * diffuse);
    float edgeFeather = smoothstep(1.0, 0.75, dist);
    float a = orbMask * (0.85 + 0.15 * edgeFeather);
    return half4(half3(finalColor * a), half(a));
}
"""

/** AGSL is API 33+; a compile failure (hwui throws IllegalArgumentException) falls back to the Canvas liquid. */
private fun stitchLiquidShader(): RuntimeShader? {
    if (Build.VERSION.SDK_INT < 33) return null
    return try { RuntimeShader(STITCH_LIQUID_AGSL) } catch (e: IllegalArgumentException) { null }
}
private const val PERIOD_A_S = 11f
private const val PERIOD_B_S = 14f
private const val PERIOD_C_S = 17f

/**
 * Virlin Orb — the Stitch liquid Orb (2026-09-13): the design's WebGL liquid shader ported to AGSL
 * (`STITCH_LIQUID_AGSL`) drawn inside a 52dp sphere with the design's glass overlays (top-left
 * highlight, tiny bottom-right reflection, inset rim), a gentle 4.5 s float/breathe and a faint
 * matcha glow. Below API 33 (no AGSL) the previous Canvas liquid renders instead. Never rotates.
 *
 * The public contract is unchanged: [params] (the accepted interaction-state model) bends the
 * liquid speed ([OrbInteractionParameters.motionMultiplier]), glow ([attentionIntensity]) and
 * scale ([orbScale]); [onPress] / [onRelease] are the SAME callbacks the Agent shell already
 * uses, so a tap opens the existing CONTROL · CREATE · CAPTURE Agent.
 */
@Composable
fun VirlinOrb(
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    params: OrbInteractionParameters = OrbInteractionParameters(),
    onPress: () -> Unit = {},
    onRelease: (tapped: Boolean) -> Unit = {},
    interactive: Boolean = true,
    state: VirlinOrbState = VirlinOrbState.Idle
) {
    // ---- one continuous time source: three phases integrated from the eased speed, so a state
    // change bends the flow without ever resetting or jumping the liquid.
    val speed = remember { Animatable(1f) }
    LaunchedEffect(params.motionMultiplier, state) {
        speed.animateTo(params.motionMultiplier * state.speed, tween(320, easing = FastOutSlowInEasing))
    }
    var phaseA by remember { mutableFloatStateOf(0f) }
    var phaseB by remember { mutableFloatStateOf(1.1f) }
    var phaseC by remember { mutableFloatStateOf(2.3f) }
    var shaderTime by remember { mutableFloatStateOf(0f) }      // seconds, speed-integrated (Stitch u_time)
    var floatPhase by remember { mutableFloatStateOf(0f) }      // Stitch orb-float: 4.5 s breathe
    val liquid = remember { stitchLiquidShader() }
    var liquidUnsupported by remember { mutableStateOf(false) }  // software canvas (tests) can't draw a RuntimeShader
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1_000_000_000f * speed.value
            last = now
            shaderTime += dt
            floatPhase = (floatPhase + dt * TWO_PI / 4.5f) % TWO_PI
            phaseA = (phaseA + dt * TWO_PI / PERIOD_A_S) % TWO_PI
            phaseB = (phaseB + dt * TWO_PI / PERIOD_B_S) % TWO_PI
            phaseC = (phaseC + dt * TWO_PI / PERIOD_C_S) % TWO_PI
        }
    }

    // ---- glow: state baseline + attention; a one-shot bloom lifts it briefly, then it settles.
    val attention by animateFloatAsState(params.attentionIntensity, tween(400), label = "attention")
    val bloom = remember { Animatable(0f) }
    LaunchedEffect(params.oneShot) {
        if (params.oneShot != null) { bloom.snapTo(1f); bloom.animateTo(0f, tween(900, easing = FastOutSlowInEasing)) }
    }
    val glowStrength = state.glow + 0.07f * attention + 0.06f * bloom.value + 0.05f * params.warmAccentIntensity

    // ---- scale: state target (press compression / opening / acting) with the accepted spring settle.
    val stateScale by animateFloatAsState(
        targetValue = params.orbScale, animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium), label = "stateScale"
    )

    Box(
        modifier = modifier
            .size(size)
            // Test/accessibility identity: ONE node, role Button, the same press+release as a tap.
            .testTag(VirlinOrbTestTag)
            .semantics(mergeDescendants = true) {
                contentDescription = VirlinOrbContentDescription
                role = Role.Button
                onClick(label = VirlinOrbContentDescription) { onPress(); onRelease(true); true }
            }
            .drawBehind {
                // Faint green glow, never a halo: 0.69 × diameter, alpha ≤ ~0.3 even at peak.
                val glowRadius = this.size.minDimension * 0.69f
                drawCircle(
                    brush = Brush.radialGradient(colors = listOf(Glow, GlowEdge, Color.Transparent), center = center, radius = glowRadius),
                    center = center, radius = glowRadius, alpha = glowStrength.coerceIn(0f, 0.3f)
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    val breathe = (1f - cos(floatPhase)) / 2f            // 0 → 1 → 0 over 4.5 s
                    scaleX = stateScale * (1f + 0.025f * breathe); scaleY = stateScale * (1f + 0.025f * breathe)
                    translationY = -3.dp.toPx() * breathe
                }
                // Gesture node only while interactive: inside the open Agent the Orb is identity (and hidden in
                // Control) and must never win hit-testing over the sheet content beneath it.
                .pointerInput(interactive) {
                    if (!interactive) return@pointerInput
                    detectTapGestures(onPress = { onPress(); val completed = tryAwaitRelease(); onRelease(completed) })
                }
        ) {
            val d = this.size.minDimension
            val radius = d / 2f
            val c = Offset(d / 2f, d / 2f)
            val spherePath = Path().apply { addOval(Rect(1f, 1f, d - 1f, d - 1f)) }

            if (liquid != null && !liquidUnsupported) {
                // Stitch liquid: the AGSL shader fills the sphere; then the design's glass overlays.
                liquid.setFloatUniform("u_time", shaderTime)
                liquid.setFloatUniform("u_resolution", d, d)
                try { drawCircle(brush = ShaderBrush(liquid), center = c, radius = radius) }
                catch (e: IllegalArgumentException) { liquidUnsupported = true }   // "Software rendering doesn't support RuntimeShader"
                if (!liquidUnsupported) {
                // inset rim: light top, matcha-shaded bottom
                drawCircle(brush = Brush.verticalGradient(0f to Color(0xB3FFFFFF), 0.25f to Color.Transparent, 0.8f to Color.Transparent, 1f to Color(0x4DAEDBA1)),
                    center = c, radius = radius - d * 0.02f, style = Stroke(width = d * 0.06f))
                // top-left highlight (w-4 h-2.5, -35°, white/50)
                rotate(degrees = -35f, pivot = Offset(d * 0.30f, d * 0.24f)) {
                    drawOval(color = Color.White.copy(alpha = 0.5f), topLeft = Offset(d * 0.30f - d * 0.14f, d * 0.24f - d * 0.09f), size = androidx.compose.ui.geometry.Size(d * 0.28f, d * 0.18f))
                }
                // tiny bottom-right reflection
                drawCircle(color = Color.White.copy(alpha = 0.3f), center = Offset(d * 0.74f, d * 0.74f), radius = d * 0.05f)
                return@Canvas
                }
            }

            // Base glass body.
            drawCircle(
                brush = Brush.radialGradient(colors = listOf(GlassLight, GlassMid, GlassEdge), center = Offset(d * 0.31f, d * 0.26f), radius = d * 0.74f),
                center = c, radius = radius - 1f
            )

            clipPath(spherePath) {
                // Calm mint wash.
                drawCircle(
                    brush = Brush.radialGradient(colors = listOf(MintWash, MintWashEdge, Color.Transparent), center = Offset(d * 0.52f, d * 0.52f), radius = d * 0.70f),
                    center = c, radius = d * 0.66f
                )
                // Mint liquid body (~11 s).
                val aX = d * (0.49f + 0.15f * sin(phaseA)); val aY = d * (0.49f + 0.13f * cos(phaseA * 0.83f))
                drawCircle(brush = Brush.radialGradient(colors = listOf(MintBody, MintBodyEdge, Color.Transparent), center = Offset(aX, aY), radius = d * 0.33f), center = Offset(aX, aY), radius = d * 0.33f)
                // Natural green liquid body (~14 s).
                val bX = d * (0.53f + 0.17f * cos(phaseB)); val bY = d * (0.50f + 0.15f * sin(phaseB * 0.91f))
                drawCircle(brush = Brush.radialGradient(colors = listOf(GreenBody, GreenBodyEdge, Color.Transparent), center = Offset(bX, bY), radius = d * 0.29f), center = Offset(bX, bY), radius = d * 0.29f)
                // Pale mint liquid body (~17 s).
                val cX = d * (0.43f + 0.16f * sin(phaseC + 1.7f)); val cY = d * (0.55f + 0.14f * cos(phaseC * 0.78f))
                drawCircle(brush = Brush.radialGradient(colors = listOf(PaleMintBody, PaleMintBodyEdge, Color.Transparent), center = Offset(cX, cY), radius = d * 0.30f), center = Offset(cX, cY), radius = d * 0.30f)
                // Very small warm accent.
                val yX = d * (0.56f + 0.10f * cos(phaseA + phaseC)); val yY = d * (0.43f + 0.09f * sin(phaseB))
                drawCircle(brush = Brush.radialGradient(colors = listOf(Yellow, YellowEdge, Color.Transparent), center = Offset(yX, yY), radius = d * 0.16f), center = Offset(yX, yY), radius = d * 0.16f)
                // Cloudy glass overlay.
                drawCircle(
                    brush = Brush.radialGradient(colors = listOf(Color(0x50FFFFFF), Color(0x18FFFFFF), Color.Transparent), center = Offset(d * 0.34f, d * 0.29f), radius = d * 0.56f),
                    center = c, radius = d * 0.58f
                )
            }

            // Glass rim.
            drawCircle(
                brush = Brush.sweepGradient(colors = listOf(Color(0xB8FFFFFF), Color(0x7A63C993), Color(0x45FFFFFF), Color(0x6964C991), Color(0xB8FFFFFF)), center = c),
                center = c, radius = radius - 1.5f, style = Stroke(width = d * 0.022f)
            )
            // Main glass reflection.
            val hl = Offset(d * 0.30f, d * 0.24f)
            drawCircle(brush = Brush.radialGradient(colors = listOf(Color(0xE5FFFFFF), Color(0x68FFFFFF), Color.Transparent), center = hl, radius = d * 0.17f), center = hl, radius = d * 0.17f)
            // Tiny secondary reflection.
            drawCircle(color = Color.White.copy(alpha = 0.48f), center = Offset(d * 0.69f, d * 0.71f), radius = d * 0.032f)
        }
    }
}
