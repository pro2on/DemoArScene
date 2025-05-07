package com.pro2on.geospacial

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.filament.Engine
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Earth
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.node.ARCameraNode
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.ar.scene.destroy
import io.github.sceneview.collision.Vector3
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.toFloat3
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberView
import kotlin.math.pow
import kotlin.math.sqrt

private const val RED_ARROW_MODEL_X_POSITION = 0.4f
private const val RED_ARROW_MODEL_Z_POSITION = -1.0f
private const val RED_ARROW_MODEL_INITIAL_SCALE = 0.25f
private const val AVERAGE_MAN_PHONE_POSITION = 1.5f
private const val AR_CORE_FAR_PLANE = 30f
private const val METERS_TO_FEET = 3.28084f

// The thresholds that are required for horizontal and orientation accuracies before entering into
// the LOCALIZED state. Once the accuracies are equal or less than these values, the app will
// allow the user to place anchors.
private const val LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS = 10.0
private const val LOCALIZING_ORIENTATION_YAW_ACCURACY_THRESHOLD_DEGREES = 15.0

// Once in the LOCALIZED state, if either accuracies degrade beyond these amounts, the app will
// revert back to the LOCALIZING state.
private const val LOCALIZED_HORIZONTAL_ACCURACY_HYSTERESIS_METERS = 10.0
private const val LOCALIZED_ORIENTATION_YAW_ACCURACY_HYSTERESIS_DEGREES = 10.0

private const val DEFAULT_ACCURACY = 100.0

@Composable
fun ArNavigationScreen() {

    val context = LocalContext.current

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val cameraNode = rememberARCameraNode(engine)
    val childNodes = rememberNodes()
    val view = rememberView(engine)
    val collisionSystem = rememberCollisionSystem(view)
    var shouldCreateAnchor by remember { mutableStateOf(false) }
    var trackingFailureReason by remember {
        mutableStateOf<TrackingFailureReason?>(null)
    }
    var frame: Frame? by remember { mutableStateOf<Frame?>(null) }
    val useImperialUnits: Boolean = remember {
        val locale = context.resources.configuration.locales[0]
        locale.country in setOf("US", "LR", "MM")
    }
    var arAccuracyState: ARState by remember { mutableStateOf(ARState.LOCALIZING) }
    var distanceToAsset by remember { mutableFloatStateOf(-1f) }
    var debugInfo by remember { mutableStateOf("") }

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
        planeRenderer = false,
        onTrackingFailureChanged = {
            trackingFailureReason = it
        },
        onSessionUpdated = { session, updatedFrame ->
            frame = updatedFrame

            // update accuracy
            val horizontalAccuracy = session.earth?.cameraGeospatialPose?.horizontalAccuracy ?: DEFAULT_ACCURACY
            val yawAccuracy = session.earth?.cameraGeospatialPose?.orientationYawAccuracy ?: DEFAULT_ACCURACY

            if (arAccuracyState == ARState.LOCALIZING) {
                if (horizontalAccuracy <= LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS &&
                    yawAccuracy <= LOCALIZING_ORIENTATION_YAW_ACCURACY_THRESHOLD_DEGREES
                ) {
                    arAccuracyState = ARState.LOCALIZED
                }
            } else if (arAccuracyState == ARState.LOCALIZED) {
                if (horizontalAccuracy > LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS +
                    LOCALIZED_HORIZONTAL_ACCURACY_HYSTERESIS_METERS ||
                    yawAccuracy > LOCALIZING_ORIENTATION_YAW_ACCURACY_THRESHOLD_DEGREES +
                    LOCALIZED_ORIENTATION_YAW_ACCURACY_HYSTERESIS_DEGREES
                ) {
                    arAccuracyState = ARState.LOCALIZING
                }
            }

            if (arAccuracyState == ARState.LOCALIZING) {
                // remove empty nodes
                distanceToAsset = -1f
                removeAnchors(childNodes)
            } else if (arAccuracyState == ARState.LOCALIZED) {
                val anchorNode = childNodes.filterIsInstance<AnchorNode>().firstOrNull()
                val redArrowNode = childNodes.filterIsInstance<ModelNode>().firstOrNull()
                if (anchorNode != null && redArrowNode != null) {
                    updateCylinderPosition(anchorNode, cameraNode)
                    distanceToAsset = calculateDistanceToAsset(anchorNode, cameraNode)
                    updateRedArrowPosition(redArrowNode, anchorNode, frame)
                } else {
                    distanceToAsset = -1f
                    if (shouldCreateAnchor) {
                        shouldCreateAnchor = false
                        handleAnchorCreation(session, engine, materialLoader, modelLoader, childNodes)
                    }
                }
            }

            // Update debug info
            val sb = StringBuilder()
            sb.appendLine("Earth is enabled: ${session.earth?.earthState}")
            sb.appendLine("Tracking state: ${session.earth?.trackingState}")
            if (trackingFailureReason != null) {
                sb.appendLine("Tracking failure reason: $trackingFailureReason")
            }
            sb.appendLine("Horizontal accuracy: ${session.earth?.cameraGeospatialPose?.horizontalAccuracy}")
            sb.append("Orientation accuracy: ${session.earth?.cameraGeospatialPose?.orientationYawAccuracy}")
            debugInfo = sb.toString()
        },
        onSessionPaused = {
            distanceToAsset = -1f
            arAccuracyState = ARState.LOCALIZING
            removeAnchors(childNodes)
        },
    )

    // Show a distance to the asset
    if (distanceToAsset > 0 && distanceToAsset > 1) {
        val displayDistance = if (useImperialUnits) {
            (distanceToAsset * METERS_TO_FEET).toInt()
        } else {
            distanceToAsset.toInt()
        }
        val unit = if (useImperialUnits) "ft" else "m"

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(Color.Gray.copy(alpha = 0.5f), shape = RoundedCornerShape(4.dp))
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    text = "$displayDistance$unit",
                    style = MaterialTheme.typography.displaySmall,
                )
            }
        }
    }


    if (debugInfo.isNotEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(color = Color.White.copy(alpha = 0.5f))
        ) {
            Text(text = debugInfo)
        }
    }

    if (arAccuracyState == ARState.LOCALIZING) {
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .align(alignment = Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(color = Color.White.copy(alpha = 0.5f))
            ) {
                Text(
                    modifier = Modifier.align(Alignment.Center),
                    text = "Insufficient accuracy"
                )
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        if (childNodes.isEmpty() && arAccuracyState == ARState.LOCALIZED) {
            Spacer(modifier = Modifier.weight(1f))
            Button(
                modifier = Modifier
                    .align(alignment = Alignment.CenterHorizontally)
                    .padding(16.dp),
                onClick = { shouldCreateAnchor = true }) {
                Text(text = "Create Anchor")
            }
        }
    }

}

@Suppress("TooGenericExceptionCaught")
private fun removeAnchors(childNodes: SnapshotStateList<Node>) {
    childNodes.filterIsInstance<AnchorNode>().forEach { node ->
        node.anchor.let { anchor ->
            try {
                anchor.destroy()
            } catch (e: Exception) {
                Log.e("AR", "Error detaching ARCore anchor")
            }
        }
    }
    childNodes.clear()
}

private const val CYLINDER_HEIGHT = 30f
private const val CYLINDER_RADIUS = 0.25f

fun createAnchorNode(
    engine: Engine,
    materialLoader: MaterialLoader,
    anchor: Anchor,
): AnchorNode {
    // Create the node using the provided ARCore anchor.
    val anchorNode = AnchorNode(engine = engine, anchor = anchor)

    // Offset the center so that the cylinder's base is at the anchor.
    val center = Position(0.0f, CYLINDER_HEIGHT / 2f, 0.0f)

    // Create a semi-transparent green material.
    val materialInstance = materialLoader.createColorInstance(Color.Green)

    // Build the cylinder node.
    val cylinderNode = CylinderNode(
        engine = engine,
        radius = CYLINDER_RADIUS,
        height = CYLINDER_HEIGHT,
        center = center,
        materialInstance = materialInstance
    )

    // Attach the cylinder node as a child of the anchor node.
    anchorNode.addChildNode(cylinderNode)
    return anchorNode
}

private fun updateCylinderPosition(
    anchorNode: AnchorNode,
    cameraNode: ARCameraNode,
) {
    val cylinderNode = anchorNode.childNodes.firstOrNull() ?: return
    val cameraPosition = cameraNode.worldPosition
    val assetPosition = anchorNode.worldPosition
    val horizontalDistance = sqrt(
        (assetPosition.x - cameraPosition.x).pow(2) +
            (assetPosition.z - cameraPosition.z).pow(2)
    )
    val heightAboveFloor = cameraPosition.y - AVERAGE_MAN_PHONE_POSITION
    cylinderNode.worldPosition = if (horizontalDistance > AR_CORE_FAR_PLANE) {
        val ratio = AR_CORE_FAR_PLANE / horizontalDistance
        Position(
            cameraPosition.x + (assetPosition.x - cameraPosition.x) * ratio,
            heightAboveFloor,
            cameraPosition.z + (assetPosition.z - cameraPosition.z) * ratio
        )
    } else {
        Position(assetPosition.x, heightAboveFloor, assetPosition.z)
    }
}

private fun calculateDistanceToAsset(
    anchorNode: AnchorNode,
    cameraNode: ARCameraNode,
): Float {
    val cameraPosition = cameraNode.worldPosition
    val anchorPosition = anchorNode.worldPosition
    return sqrt(
        (cameraPosition.x - anchorPosition.x).pow(2) +
            (cameraPosition.z - anchorPosition.z).pow(2)
    )
}

private fun updateRedArrowPosition(
    redArrowNode: ModelNode,
    anchorNode: AnchorNode,
    frame: Frame?,
) {
    val cameraPose = frame?.camera?.pose ?: return
    val translationPose = Pose.makeTranslation(
        RED_ARROW_MODEL_X_POSITION,
        0.0f,
        RED_ARROW_MODEL_Z_POSITION
    )
    val modelPose = cameraPose.compose(translationPose)
    redArrowNode.worldPosition = Position(
        modelPose.tx(),
        modelPose.ty(),
        modelPose.tz()
    )
    val target = Position(
        anchorNode.worldPosition.x,
        redArrowNode.worldPosition.y,
        anchorNode.worldPosition.z
    )
    redArrowNode.lookAt(target, Vector3.up().toFloat3(), false, 1f)
}

@Suppress("LongParameterList")
private fun handleAnchorCreation(
    session: Session,
    engine: Engine,
    materialLoader: MaterialLoader,
    modelLoader: ModelLoader,
    childNodes: MutableList<Node>,
) {
    val earth = session.earth ?: return
    if (earth.earthState == Earth.EarthState.ENABLED && earth.trackingState == TrackingState.TRACKING) {
        val earthPose = earth.cameraGeospatialPose
        val anchor = earth.createAnchor(
            earthPose.latitude,
            earthPose.longitude,
            earthPose.altitude,
            0f,
            0f,
            0f,
            1f
        )
        val anchorNode = createAnchorNode(engine, materialLoader, anchor)
        childNodes.add(anchorNode)
        val modelNode = ModelNode(
            modelInstance = modelLoader.createModelInstance("models/car_arrow.glb"),
            scaleToUnits = RED_ARROW_MODEL_INITIAL_SCALE
        )
        childNodes.add(modelNode)
    }
}

enum class ARState {
    LOCALIZING,
    LOCALIZED,
}
