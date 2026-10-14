package com.virlin.app.map

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.virlin.app.ui.hierarchy.ProjectPalette
import com.virlin.app.ui.map.projectMapChromeColors
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectMapChromeColorTest {
    @Test fun projectDialogsUseWhiteSurfacesAndProjectInk() {
        val palette = ProjectPalette(
            main = 0xFF3459A8.toInt(), primary = 0xFF3459A8.toInt(),
            secondary = 0xFF234278.toInt(), tertiary = 0xFFE4EAFA.toInt(),
            text = 0xFF172643.toInt(), muted = 0xFF586781.toInt(),
        )
        val scheme = projectMapChromeColors(lightColorScheme(), palette)

        assertEquals(Color.White, scheme.surface)
        assertEquals(Color.White, scheme.surfaceContainerHigh)
        assertEquals(Color(palette.main), scheme.primary)
        assertEquals(Color(palette.text), scheme.onSurface)
        assertEquals(Color(palette.muted), scheme.onSurfaceVariant)
    }
}
