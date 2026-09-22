package app.jingting.localplayer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Quiet line icons drawn for JingTing. These coordinates are project-owned source,
 * not copied Material Icons paths. Familiar action silhouettes remain conventional.
 * One 24-unit grid, 1.7-unit round strokes and no embedded display colour.
 */
object QuietIcons {
    private fun line(name: String, data: String, mirrored: Boolean = false): ImageVector =
        ImageVector.Builder(
            name = "Quiet.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = mirrored,
        ).addPath(
            pathData = PathParser().parsePathString(data).toNodes(),
            fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ).build()

    val ArrowBack = line("ArrowBack", "M19 12H5M11 6L5 12L11 18", true)
    val ChevronRight = line("ChevronRight", "M9 5.5L15.5 12L9 18.5", true)
    val KeyboardArrowUp = line("Up", "M5.5 15L12 8.5L18.5 15")
    val KeyboardArrowDown = line("Down", "M5.5 9L12 15.5L18.5 9")
    val Add = line("Add", "M12 5V19M5 12H19")
    val Close = line("Close", "M6 6L18 18M18 6L6 18")
    val Check = line("Check", "M4.5 12.5L9.3 17.3L19.5 6.7")
    val SelectAll = line("SelectAll", "M8 3H4Q3 3 3 4V8M16 3H20Q21 3 21 4V8M3 16V20Q3 21 4 21H8M21 16V20Q21 21 20 21H16M7 12L10.5 15.5L17 8.5")
    val Search = line("Search", "M16.7 10.2A6.5 6.5 0 1 1 3.7 10.2A6.5 6.5 0 1 1 16.7 10.2M14.8 14.8L20.5 20.5")
    val MoreVert = line("More", "M12 5.2V5.3M12 11.95V12.05M12 18.7V18.8")
    val Refresh = line("Refresh", "M19.3 9A7.7 7.7 0 1 0 19 15.6M19.3 4.6V9H14.9")
    val Settings = line("Settings", "M10 3H14L14.7 5.5L16.5 6.5L19 5.9L21 9.3L19.2 11.1V12.9L21 14.7L19 18.1L16.5 17.5L14.7 18.5L14 21H10L9.3 18.5L7.5 17.5L5 18.1L3 14.7L4.8 12.9V11.1L3 9.3L5 5.9L7.5 6.5L9.3 5.5ZM15 12A3 3 0 1 1 9 12A3 3 0 1 1 15 12")

    val LibraryMusic = line("Library", "M4 4.5V19.5M8 5V19M12 4.5V19.5M17 4.5L20.5 18.8M16 8L19.7 7.1M18.5 17.9L22 17")
    val QueueMusic = line("Collection", "M3.5 7V18.5Q3.5 20.5 5.5 20.5H16M7 4H18.5Q20.5 4 20.5 6V15.5Q20.5 17.5 18.5 17.5H9Q7 17.5 7 15.5ZM11 8H16.5M11 12H16.5")
    val MusicNote = line("Audio", "M4.5 10V14M8.25 6.5V17.5M12 4V20M15.75 7.5V16.5M19.5 10V14")
    val Movie = line("Video", "M5.5 4.5H18.5Q20.5 4.5 20.5 6.5V17.5Q20.5 19.5 18.5 19.5H5.5Q3.5 19.5 3.5 17.5V6.5Q3.5 4.5 5.5 4.5ZM10 9L15 12L10 15ZM3.5 8H7M17 8H20.5M3.5 16H7M17 16H20.5")
    val HideImage = line("Unavailable", "M4 9V18Q4 20 6 20H15M9 4H18Q20 4 20 6V15M3 3L21 21M4.5 16L8 12.5L13 17.5M15 9H15.1")
    val AudioFile = line("ExtractAudio", "M13 3.5H6Q4 3.5 4 5.5V18.5Q4 20.5 6 20.5H11M13 3.5L19.5 10H15Q13 10 13 8ZM16.5 12V21M13 15V18M20 14V19")
    val Extract get() = AudioFile
    val Merge = line("JoinAudio", "M3.5 8H7Q10 8 12 12Q14 16 17 16H20.5M3.5 16H7Q10 16 12 12Q14 8 17 8H20.5M3.5 5.5V10.5M3.5 13.5V18.5M20.5 5.5V10.5M20.5 13.5V18.5")
    val PlaylistAdd = line("AddToCollection", "M4 5H16M4 10H12M4 15H10M17 13V21M13 17H21")
    val Edit = line("Rename", "M4 20L8.5 18.8L20 7.3Q20.8 6.5 20 5.7L18.3 4Q17.5 3.2 16.7 4L5.2 15.5ZM14.8 5.9L18.1 9.2M12 20H20")
    val Delete = line("Remove", "M4 6H20M9 6V3.5H15V6M6 6L7 19Q7 20.5 8.5 20.5H15.5Q17 20.5 17 19L18 6M10 10V16.5M14 10V16.5")

    val PlayArrow = line("Play", "M8 4.8L19.5 12L8 19.2Z")
    val Pause = line("Pause", "M8 5V19M16 5V19")
    val Stop = line("Stop", "M6 5.5H18Q18.5 5.5 18.5 6V18Q18.5 18.5 18 18.5H6Q5.5 18.5 5.5 18V6Q5.5 5.5 6 5.5Z")
    val SkipPrevious = line("Previous", "M5 5V19M18 5.5L8 12L18 18.5Z")
    val SkipNext = line("Next", "M19 5V19M6 5.5L16 12L6 18.5Z")
    // Hand-drawn '10' stays legible at 24 dp; the arrow does not resemble download.
    val Replay10 = line("BackTenSeconds", "M7 4.5H3.5V8M3.5 4.5A10 10 0 0 1 20.8 9M8 10.5L10 9V17M17.5 11Q17.5 9 15.5 9Q13.5 9 13.5 11V15Q13.5 17 15.5 17Q17.5 17 17.5 15Z")
    val Forward10 = line("ForwardTenSeconds", "M17 4.5H20.5V8M20.5 4.5A10 10 0 0 0 3.2 9M8 10.5L10 9V17M17.5 11Q17.5 9 15.5 9Q13.5 9 13.5 11V15Q13.5 17 15.5 17Q17.5 17 17.5 15Z")
    val Repeat = line("RepeatList", "M4.5 9V7.5Q4.5 5.5 6.5 5.5H19.5M16 2L19.5 5.5L16 9M19.5 15V16.5Q19.5 18.5 17.5 18.5H4.5M8 15L4.5 18.5L8 22")
    val RepeatOne = line("RepeatOne", "M4.5 9V7.5Q4.5 5.5 6.5 5.5H19.5M16 2L19.5 5.5L16 9M19.5 15V16.5Q19.5 18.5 17.5 18.5H4.5M8 15L4.5 18.5L8 22M10.5 10.5L12.5 9V15")
    val Shuffle = line("Shuffle", "M3.5 5.5H5.5Q7.5 5.5 9.5 9M14.5 15Q16.5 18.5 18.5 18.5H21M17.5 15L21 18.5L17.5 22M3.5 18.5H5.5Q8 18.5 12 12Q16 5.5 18.5 5.5H21M17.5 2L21 5.5L17.5 9")
    val PlayOnce = line("PlayOnce", "M4 5H14M4 10H12M4 15H10M14 13L20.5 17L14 21Z")

    val Schedule = line("Schedule", "M20.5 12A8.5 8.5 0 1 1 3.5 12A8.5 8.5 0 1 1 20.5 12M12 7V12L15.5 14")
    val Timer = line("Timer", "M8.5 2.5H15.5M12 2.5V5M19 5L20.5 6.5M20 13A8 8 0 1 1 4 13A8 8 0 1 1 20 13M12 8.5V13L9.5 15.5")
    val Bedtime = line("Night", "M19.5 14.5A8.5 8.5 0 1 1 9.5 4.5A6.5 6.5 0 0 0 19.5 14.5Z")
    val Calendar = line("Calendar", "M7 3V7M17 3V7M5 5H19Q21 5 21 7V19Q21 21 19 21H5Q3 21 3 19V7Q3 5 5 5ZM3 10H21M7 14H9M15 14H17M7 18H9")
    val Info = line("Info", "M20.5 12A8.5 8.5 0 1 1 3.5 12A8.5 8.5 0 1 1 20.5 12M12 7.5V7.6M12 11V16.5")
    val VolumeUp = line("Volume", "M4 9H7L12 5V19L7 15H4ZM16 8.5Q19 12 16 15.5M19 5.5Q24 12 19 18.5")
}
