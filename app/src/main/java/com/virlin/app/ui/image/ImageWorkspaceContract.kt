package com.virlin.app.ui.image

const val ImageWorkspaceTaskRoute = "image_workspace/new/task/{taskId}"
const val ImageWorkspaceCaptureRoute = "image_workspace/capture/{captureId}"

fun imageWorkspaceForTask(taskId: String): String {
    require(taskId.isNotBlank()) { "A task-owned image workspace requires a task id" }
    return "image_workspace/new/task/$taskId"
}

fun imageWorkspaceForCapture(captureId: String): String {
    require(captureId.isNotBlank()) { "An image workspace capture id cannot be blank" }
    return "image_workspace/capture/$captureId"
}

const val ImageWorkspaceTag = "image_workspace"
const val ImageChooseButtonTag = "image_choose_button"
const val ImageSaveCopyTag = "image_save_copy"

enum class ImageWorkspaceMode { LIBRARY, EDIT, CROP, MARKUP }

enum class ImageEditTool { ADJUST, CROP, MARKUP, TEXT, FILTERS }

enum class ImageMarkupTool { PEN, HIGHLIGHTER, SHAPE, ARROW, TEXT, ERASER }

data class ImageAdjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
) {
    fun normalized() = copy(
        brightness = brightness.coerceIn(-1f, 1f),
        contrast = contrast.coerceIn(-1f, 1f),
        saturation = saturation.coerceIn(-1f, 1f),
        warmth = warmth.coerceIn(-1f, 1f),
    )
}

data class ImageCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(right > left && bottom > top)
    }

    fun pixelBounds(width: Int, height: Int): IntArray = intArrayOf(
        (left * width).toInt().coerceIn(0, width - 1),
        (top * height).toInt().coerceIn(0, height - 1),
        (right * width).toInt().coerceIn(1, width),
        (bottom * height).toInt().coerceIn(1, height),
    )
}

data class ImageTransform(
    val rotationQuarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val straightenDegrees: Float = 0f,
) {
    fun normalized() = copy(
        rotationQuarterTurns = ((rotationQuarterTurns % 4) + 4) % 4,
        straightenDegrees = straightenDegrees.coerceIn(-15f, 15f),
    )
}

data class ImageMarkupStroke(
    val tool: ImageMarkupTool,
    val colorArgb: Int,
    val widthFraction: Float,
    val opacity: Float,
    val points: List<Pair<Float, Float>>,
    val text: String? = null,
)
