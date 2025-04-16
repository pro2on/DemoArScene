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
import com.google.ar.core.Earth
import com.google.ar.core.Frame
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberView


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
    var anchorCounter by remember { mutableStateOf(0) }
    var shouldCreateAnchor by remember { mutableStateOf(false) }

    val planeRenderer: Boolean by remember { mutableStateOf(true) }

    var earthState: String by remember { mutableStateOf("Sync...") }
    var earthTrackingState: String by remember { mutableStateOf("Sync...") }

    val modelInstances = remember { mutableListOf<ModelInstance>() }
    var trackingFailureReason by remember {
        mutableStateOf<TrackingFailureReason?>(null)
    }


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

            // Display current session tracking state in the debug overlay.
//                        val sessionTracking = session.



            val earth = session.earth
            if (earth == null) {
// Check for location permissions (which are required for geospatial tracking).
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

            debugText = sb.toString()

            if (shouldCreateAnchor) {
                shouldCreateAnchor = false

                val anchor = earth.createAnchor(
                    earthPose.latitude,
                    earthPose.longitude,
                    earthPose.altitude,
                    earthPose.eastUpSouthQuaternion[0],
                    earthPose.eastUpSouthQuaternion[1],
                    earthPose.eastUpSouthQuaternion[2],
                    earthPose.eastUpSouthQuaternion[3]
                )

                val node = createAnchorNode(engine, materialLoader, anchor)
                childNodes.add(node)
            }

//            val latitude = 37.741703
//            val longitude = -25.664420
//            val altitude = 10.0
//
//            val qx = 0.0f
//            val qy = 0.0f
//            val qz = 0.0f
//            val qw = 1.0f
//
//
//
//            if (childNodes.isEmpty()) {
//                val anchor = earth.createAnchor(
//                    latitude,
//                    longitude,
//                    altitude,
//                    qx,
//                    qy,
//                    qz,
//                    qw
//                )
//                // Add a new anchor node with a 1‑km‑tall cylinder.
//                childNodes.add(createAnchorNode(engine, materialLoader, anchor))
//
//                anchorCounter++
//
//                debugText = "Anchor created: $anchorCounter"
//            }
        },
    )


    Column {
        Text(text = earthState)
        Text(text = earthTrackingState)
        Text(text = debugText)
        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = { shouldCreateAnchor = true }) {
            Text(text = "Create Anchor")
        }
    }

}

/**
 * Creates an AnchorNode that attaches a 1‑km‑tall cylinder.
 *
 * The cylinder is built using CylinderNode with a specified radius and height.
 * To align the cylinder’s base at the anchor point, the center is offset upward by half of its height.
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
        size = Size(1.0f),
        center = center,
        materialInstance = materialInstance
    )

    // Attach the cylinder node as a child of the anchor node.
    anchorNode.addChildNode(boxNode)

    return anchorNode
}
