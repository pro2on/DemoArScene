package com.pro2on.geospacial

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.filament.Engine
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.arcore.position
import io.github.sceneview.ar.arcore.zDirection
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.collision.Quaternion
import io.github.sceneview.collision.Vector3
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

@Composable
fun ArNavigationScreen() {

    val context = LocalContext.current

    // The destroy calls are automatically made when their disposable effect leaves
    // the composition or its key changes.
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val cameraNode = rememberARCameraNode(engine)
    val childNodes = rememberNodes()
    val view = rememberView(engine)
    val collisionSystem = rememberCollisionSystem(view)

    var debugText by remember { mutableStateOf("Waiting for AR session update...") }
    var shouldCreateAnchor by remember { mutableStateOf(false) }

    val planeRenderer: Boolean by remember { mutableStateOf(true) }

    var earthState: String by remember { mutableStateOf("Sync...") }
    var earthTrackingState: String by remember { mutableStateOf("Sync...") }

    var trackingFailureReason by remember {
        mutableStateOf<TrackingFailureReason?>(null)
    }

    var anchorAdded by remember { mutableStateOf(false) }


    var frame: Frame? by remember { mutableStateOf<Frame?>(null) }
    ARScene(
        modifier = Modifier.fillMaxSize(),
        childNodes = childNodes,
        engine = engine,
        view = view,
        modelLoader = modelLoader,
        collisionSystem = collisionSystem,
        sessionConfiguration = { session, config ->
            config.depthMode =
                when (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    true -> Config.DepthMode.AUTOMATIC
                    else -> Config.DepthMode.DISABLED
                }
            config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
            config.lightEstimationMode =
                Config.LightEstimationMode.ENVIRONMENTAL_HDR

            // Enable the Geospatial API.
            config.geospatialMode = Config.GeospatialMode.ENABLED
        },
        cameraNode = cameraNode,
        planeRenderer = planeRenderer,
        onTrackingFailureChanged = {
            trackingFailureReason = it
        },
        onSessionUpdated = { session, updatedFrame ->
            frame = updatedFrame

            if (childNodes.isNotEmpty()) {
                val assetNode = childNodes.firstOrNull { it is AnchorNode } as AnchorNode?
                val modelNode = childNodes.firstOrNull {it is ModelNode} as ModelNode?
                if (modelNode != null) {
                    frame?.camera?.pose?.let { cameraPose ->

                        // Create a pose that represents a translation of 1 meter forward (negative z-direction)
                        val translationPose = Pose.makeTranslation(0.4f, 0.0f, -1f)

                        // Compose the camera's pose with the translation to get the new model node pose
                        val modelPose = cameraPose.compose(translationPose)

                        // Update the node's world position using the composed pose's translation components
                        val newPosition = Position(modelPose.tx(), modelPose.ty(), modelPose.tz())
                        modelNode.worldPosition = newPosition
                        
                        // Rotate this red arrow to face the anchor node
                        if (assetNode != null) {
                            modelNode.lookAt(assetNode, Direction( 0.0f, 1.0f, 0.0f), false, 1.0f)
                        }
                    }
                }
            }

            val earth = session.earth
            if (earth == null) {
                val hasLocationPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                debugText =
                    "Earth is null. Session tracking: ${frame?.camera?.trackingState}.\n" +
                        "Geospatial mode: ${session.config.geospatialMode}.\n" +
                        "Location permission granted: $hasLocationPermission.\n" +
                        "Waiting for sufficient sensor data..."
                return@ARScene
            }

            earthTrackingState = "Earth tracking state: ${earth.trackingState}"
            earthState = "Earth state: ${earth.earthState}"

            val earthPose = earth.cameraGeospatialPose
            val sb = StringBuilder()
            sb.append("Latitude: ").appendLine(earthPose.latitude)
            sb.append("Longitude: ").appendLine(earthPose.longitude)
            sb.append("Altitude: ").appendLine(earthPose.altitude)
            sb.append("Distance: ").appendLine(flatDistance(37.745600, -25.585428, earthPose.latitude, earthPose.longitude))

            debugText = sb.toString()

            // 37.745600, -25.585428

            if (shouldCreateAnchor) {
                shouldCreateAnchor = false
                val anchor = earth.createAnchor(
                    37.745600,//earthPose.latitude,
                    -25.585428,//earthPose.longitude,
                    earthPose.altitude,
                    earthPose.eastUpSouthQuaternion[0],
                    earthPose.eastUpSouthQuaternion[1],
                    earthPose.eastUpSouthQuaternion[2],
                    earthPose.eastUpSouthQuaternion[3]
                )
                val node = createAnchorNode(engine, materialLoader, anchor)
                childNodes.add(node)

                val modelNode = ModelNode(
                    modelInstance = modelLoader.createModelInstance("models/car_arrow.glb"),
                    scaleToUnits = 0.25f,
                )

                childNodes.add(modelNode)
                anchorAdded = true
            }
        },
    )


    Column {
        Text(text = earthState)
        Text(text = earthTrackingState)
        Text(text = debugText)
        if (!anchorAdded) {
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = { shouldCreateAnchor = true }) {
                Text(text = "Create Anchor")
            }
        }
    }

}

/**
 * Creates an AnchorNode that attaches a 1‑km‑tall cylinder.
 *
 * The cylinder is built using CylinderNode with a specified radius and height.
 * To align the cylinder's base at the anchor point, the center is offset upward by half of its height.
 */
fun createAnchorNode(
    engine: Engine,
    materialLoader: MaterialLoader,
    anchor: Anchor,
): AnchorNode {
    // Create the node using the provided ARCore anchor.
    val anchorNode = AnchorNode(engine = engine, anchor = anchor)

    // Define the dimensions of the cylinder.
    val height = 1000.0f    // 1 km tall
    val radius = 100.0f       // Adjust the radius based on visibility needs
    // Offset the center so that the cylinder's base is at the anchor.
    val center = Position(0.0f, 0.0f, 0.0f)

    // Create a semi-transparent green material.
    val materialInstance = materialLoader.createColorInstance(Color.Green)
    // Build the cylinder node.
//    val cylinderNode = CylinderNode(
//        engine = engine,
//        radius = radius,
//        height = height,
//        center = center,
//        materialInstance = materialInstance
//    )

    val boxNode = CubeNode(
        engine = engine,
        size = Size(10.0f),
        center = center,
        materialInstance = materialInstance
    )

    // Attach the cylinder node as a child of the anchor node.
    anchorNode.addChildNode(boxNode)

    return anchorNode
}


fun flatDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    // Один градус широты примерно равен 111320 метрам
    val metersPerDegree = 111320.0

    // Разница широт в метрах
    val dLat = (lat2 - lat1) * metersPerDegree

    // Вычисляем среднюю широту и переводим в радианы
    val avgLat = Math.toRadians((lat1 + lat2) / 2)

    // Разница долгот в метрах с учетом косинуса средней широты
    val dLon = (lon2 - lon1) * metersPerDegree * cos(avgLat)

    // Расстояние по теореме Пифагора
    return sqrt(dLat * dLat + dLon * dLon)
}


/**
 * Converts a quaternion (qx, qy, qz, qw) into Euler angles (pitch, yaw, roll).
 * Note: The exact order and sign of the Euler angles depend on the convention your engine uses.
 */
fun quaternionToEulerAngles(qx: Float, qy: Float, qz: Float, qw: Float): Vector3 {
    // Roll (rotation around x-axis)
    val sinr_cosp = 2 * (qw * qx + qy * qz)
    val cosr_cosp = 1 - 2 * (qx * qx + qy * qy)
    val roll = atan2(sinr_cosp, cosr_cosp)

    // Pitch (rotation around y-axis)
    val sinp = 2 * (qw * qy - qz * qx)
    val pitch = if (abs(sinp) >= 1) {
        (PI / 2).toFloat() * kotlin.math.sign(sinp) // use 90° if out of range
    } else {
        asin(sinp)
    }

    // Yaw (rotation around z-axis)
    val siny_cosp = 2 * (qw * qz + qx * qy)
    val cosy_cosp = 1 - 2 * (qy * qy + qz * qz)
    val yaw = atan2(siny_cosp, cosy_cosp)

    return Vector3(pitch, yaw, roll)
}
