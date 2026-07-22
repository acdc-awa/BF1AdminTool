package com.bf1.admin.tool.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminManagementTabImeInsetsTest {
    @Test
    fun adminManagementConsumesImeAndScaffoldInsets() {
        val tabSource = readWorkspaceFile(
            "app/src/main/java/com/bf1/admin/tool/ui/home/AdminManagementTab.kt"
        )
        val homeSource = readWorkspaceFile(
            "app/src/main/java/com/bf1/admin/tool/ui/home/HomeScreen.kt"
        )

        assertTrue(
            "AdminActionBar must apply IME padding",
            tabSource.contains("modifier = Modifier.imePadding()")
        )
        assertTrue(
            "Scaffold content padding must be consumed before IME padding is applied",
            homeSource.contains(".consumeWindowInsets(padding)")
        )
    }

    private fun readWorkspaceFile(path: String): String {
        var directory = File(System.getProperty("user.dir"))
        repeat(8) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: return@repeat
        }
        error("Unable to locate workspace file: $path")
    }
}
