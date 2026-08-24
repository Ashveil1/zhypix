package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zhypix.R
import com.example.ui.theme.*

@Composable
fun IsometricCube(
    modifier: Modifier = Modifier,
    color: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        
        val r = kotlin.math.min(w, h) * 0.45f
        
        val p0_x = cx
        val p0_y = cy
        
        val p1_x = cx
        val p1_y = cy - r
        
        val p2_x = cx
        val p2_y = cy + r
        
        val p3_x = cx - r * 0.866f
        val p3_y = cy - r * 0.5f
        
        val p4_x = cx + r * 0.866f
        val p4_y = cy - r * 0.5f
        
        val p5_x = cx - r * 0.866f
        val p5_y = cy + r * 0.5f
        
        val p6_x = cx + r * 0.866f
        val p6_y = cy + r * 0.5f

        // Draw translucent inner face fills for premium depth (frosted look)
        val topPath = Path().apply {
            moveTo(p0_x, p0_y)
            lineTo(p3_x, p3_y)
            lineTo(p1_x, p1_y)
            lineTo(p4_x, p4_y)
            close()
        }
        drawPath(path = topPath, color = color.copy(alpha = 0.08f))

        val leftPath = Path().apply {
            moveTo(p0_x, p0_y)
            lineTo(p3_x, p3_y)
            lineTo(p5_x, p5_y)
            lineTo(p2_x, p2_y)
            close()
        }
        drawPath(path = leftPath, color = color.copy(alpha = 0.04f))

        val rightPath = Path().apply {
            moveTo(p0_x, p0_y)
            lineTo(p4_x, p4_y)
            lineTo(p6_x, p6_y)
            lineTo(p2_x, p2_y)
            close()
        }
        drawPath(path = rightPath, color = color.copy(alpha = 0.06f))

        // Outer Hexagon Wireframe
        val outerPath = Path().apply {
            moveTo(p1_x, p1_y)
            lineTo(p4_x, p4_y)
            lineTo(p6_x, p6_y)
            lineTo(p2_x, p2_y)
            lineTo(p5_x, p5_y)
            lineTo(p3_x, p3_y)
            close()
        }

        val strokeWidth = 2.dp.toPx()

        drawPath(
            path = outerPath,
            color = color,
            style = Stroke(width = strokeWidth)
        )

        // Three internal lines representing the 3D edges meeting at the center
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(p0_x, p0_y),
            end = androidx.compose.ui.geometry.Offset(p3_x, p3_y),
            strokeWidth = strokeWidth
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(p0_x, p0_y),
            end = androidx.compose.ui.geometry.Offset(p4_x, p4_y),
            strokeWidth = strokeWidth
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(p0_x, p0_y),
            end = androidx.compose.ui.geometry.Offset(p2_x, p2_y),
            strokeWidth = strokeWidth
        )
    }
}

@Composable
fun AiCoreIcon(
    modifier: Modifier = Modifier,
    color: Color = CyanAccent,
    animate: Boolean = false
) {
    if (animate) {
        val infiniteTransition = rememberInfiniteTransition(label = "core_anim")
        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(8000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotation"
        )

        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(color.copy(alpha = 0.15f), Color.Transparent)
                        ),
                        shape = CircleShape
                    )
            )
            Canvas(modifier = Modifier.fillMaxSize(0.8f).rotate(rotation)) {
                drawCircle(
                    color = color.copy(alpha = 0.4f),
                    style = Stroke(width = 1.5.dp.toPx())
                )
                drawCircle(
                    color = color,
                    radius = 3.dp.toPx(),
                    center = center.copy(y = 0f)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize(0.4f)
                    .background(color, CircleShape)
                    .border(1.dp, color.copy(alpha = 0.8f), CircleShape)
            )
        }
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .fillMaxSize(0.8f)
                    .background(color.copy(alpha = 0.15f), CircleShape)
                    .border(1.dp, color.copy(alpha = 0.4f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize(0.4f)
                    .background(color, CircleShape)
                    .border(1.dp, color.copy(alpha = 0.8f), CircleShape)
            )
        }
    }
}

@Composable
fun BeautifiedMenuIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Box(modifier = Modifier.width(18.dp).height(2.dp).background(color, CircleShape))
        Box(modifier = Modifier.width(12.dp).height(2.dp).background(color, CircleShape))
        Box(modifier = Modifier.width(16.dp).height(2.dp).background(color, CircleShape))
    }
}

@Composable
fun RipplePulseIndicator(
    isProcessing: Boolean,
    modifier: Modifier = Modifier,
    color: Color = CyanAccent
) {
    if (!isProcessing) {
        Box(
            modifier = modifier
                .size(8.dp)
                .background(color.copy(alpha = 0.3f), CircleShape)
                .border(1.dp, color.copy(alpha = 0.5f), CircleShape)
        )
        return
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    
    val progress1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring1"
    )

    val progress2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing, delayMillis = 500),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring2"
    )

    val progress3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing, delayMillis = 1000),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring3"
    )

    Box(
        modifier = modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(progress3 * 1.5f)
                .background(color.copy(alpha = (1f - progress3) * 0.2f), CircleShape)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(progress2 * 1.5f)
                .background(color.copy(alpha = (1f - progress2) * 0.35f), CircleShape)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(progress1 * 1.5f)
                .background(color.copy(alpha = (1f - progress1) * 0.55f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color, CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape)
        )
    }
}
