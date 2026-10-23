package ru.begunok.ui.map

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import ru.begunok.workout.LatLon

private val LatLon.geo get() = GeoPoint(lat, lon)

private class DotOverlay(private val fill: Int, private val radiusPx: Float) : Overlay() {
    var position: GeoPoint? = null
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = radiusPx / 2.5f
    }
    private val point = Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val p = position ?: return
        mapView.projection.toPixels(p, point)
        canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), radiusPx, fillPaint)
        canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), radiusPx, strokePaint)
    }
}

private fun polyline(map: MapView, color: Int, width: Float) = Polyline(map).apply {
    outlinePaint.color = color
    outlinePaint.strokeWidth = width
    outlinePaint.strokeCap = Paint.Cap.ROUND
    outlinePaint.strokeJoin = Paint.Join.ROUND
    isGeodesic = false
    infoWindow = null
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun rememberMapView(): MapView {
    val context = LocalContext.current
    val map = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            setOnTouchListener { _, _ -> true }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            map.onDetach()
        }
    }
    return map
}

@Composable
fun LiveRouteMap(route: List<LatLon>, modifier: Modifier = Modifier) {
    if (route.isEmpty()) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("Маршрут появится, когда будет GPS", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val density = LocalDensity.current.density
    val map = rememberMapView()
    val line = remember { polyline(map, Color.rgb(0xFC, 0x4C, 0x02), 5 * density) }
    val dot = remember { DotOverlay(Color.rgb(0x1E, 0x88, 0xE5), 7 * density) }
    AndroidView(
        factory = {
            map.clipToOutline = true
            map.overlays.add(line)
            map.overlays.add(dot)
            map.controller.setZoom(16.5)
            map
        },
        modifier = modifier.clipToBounds(),
        update = {
            val points = route.map { it.geo }
            line.setPoints(points)
            dot.position = points.last()
            map.controller.setCenter(points.last())
            map.invalidate()
        },
    )
}
