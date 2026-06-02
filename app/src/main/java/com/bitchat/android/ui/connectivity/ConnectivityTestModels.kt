package com.bitchat.android.ui.connectivity

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

enum class TestStatus {
    IDLE,
    TESTING,
    AVAILABLE,
    AVAILABLE_NOT_IMPLEMENTED,
    PASS,
    FAIL,
    UNAVAILABLE;

    val color: Color
        get() = when (this) {
            IDLE -> Color(0xFF39FF14).copy(alpha = 0.4f)
            TESTING -> Color(0xFFFFD600)
            AVAILABLE, PASS -> Color(0xFF00C851)
            AVAILABLE_NOT_IMPLEMENTED -> Color(0xFF007AFF)
            FAIL, UNAVAILABLE -> Color(0xFFFF5555)
        }

    val label: String
        get() = when (this) {
            IDLE -> "idle"
            TESTING -> "testing..."
            AVAILABLE -> "available"
            AVAILABLE_NOT_IMPLEMENTED -> "available (not implemented)"
            PASS -> "pass"
            FAIL -> "fail"
            UNAVAILABLE -> "unavailable"
        }
}

data class TestItem(
    val id: String,
    val name: String,
    val description: String,
    val status: TestStatus = TestStatus.IDLE,
    val detail: String? = null,
    val requiresPermission: String? = null,
    val isImplemented: Boolean = true
)

data class TestCategory(
    val id: String,
    val name: String,
    val icon: ImageVector,
    val items: List<TestItem>,
    val isExpanded: Boolean = false
) {
    val passCount: Int get() = items.count { it.status == TestStatus.PASS || it.status == TestStatus.AVAILABLE }
    val failCount: Int get() = items.count { it.status == TestStatus.FAIL || it.status == TestStatus.UNAVAILABLE }
    val notImplCount: Int get() = items.count { it.status == TestStatus.AVAILABLE_NOT_IMPLEMENTED }
    val testedCount: Int get() = items.count { it.status != TestStatus.IDLE && it.status != TestStatus.TESTING }
}
