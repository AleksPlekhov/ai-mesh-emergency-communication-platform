package com.bitchat.android.ui.connectivity

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.ui.theme.BitchatTheme

class TelemetryTestActivity : ComponentActivity() {

    private val viewModel: TelemetryTestViewModel by viewModels()

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        pendingTestAfterPermission?.let { (catId, testId) ->
            viewModel.runSingleTest(catId, testId)
            pendingTestAfterPermission = null
        }
    }

    private var pendingTestAfterPermission: Pair<String, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BitchatTheme {
                TelemetryTestScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                    onRequestPermission = { categoryId, testId, permission ->
                        pendingTestAfterPermission = categoryId to testId
                        locationPermissionLauncher.launch(arrayOf(permission))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelemetryTestScreen(
    viewModel: TelemetryTestViewModel,
    onBack: () -> Unit,
    onRequestPermission: (String, String, String) -> Unit
) {
    val categories by viewModel.categories.collectAsState()
    val isRunningAll by viewModel.isRunningAll.collectAsState()
    val meshTransmitEnabled by viewModel.meshTransmitEnabled.collectAsState()
    val lastPackedSize by viewModel.lastPackedSize.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "telemetry test",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (lastPackedSize > 0) {
                            Text(
                                "packed: ${lastPackedSize}B | mesh: ${if (meshTransmitEnabled) "on" else "off"}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.toggleMeshTransmit() }
                    ) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = "Toggle mesh transmit",
                            tint = if (meshTransmitEnabled)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    TextButton(
                        onClick = { viewModel.runAllTests() },
                        enabled = !isRunningAll
                    ) {
                        if (isRunningAll) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "running...",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        } else {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "run all",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                val totalTests = categories.sumOf { it.items.size }
                val totalPassed = categories.sumOf { it.passCount }
                val totalFailed = categories.sumOf { it.failCount }
                val totalNotImpl = categories.sumOf { it.notImplCount }
                val totalTested = categories.sumOf { it.testedCount }

                if (totalTested > 0) {
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SummaryChip("$totalTested/$totalTests", "tested")
                            SummaryChip("$totalPassed", "pass", TestStatus.PASS.color)
                            SummaryChip("$totalNotImpl", "avail", TestStatus.AVAILABLE_NOT_IMPLEMENTED.color)
                            SummaryChip("$totalFailed", "fail", TestStatus.FAIL.color)
                        }
                    }
                }
            }

            items(categories, key = { it.id }) { category ->
                TestCategoryCard(
                    category = category,
                    onToggle = { viewModel.toggleCategory(category.id) },
                    onRunCategory = { viewModel.runCategoryTests(category.id) },
                    onRunTest = { testId ->
                        val item = category.items.find { it.id == testId }
                        if (item?.requiresPermission != null) {
                            val ctx = viewModel.getApplication<android.app.Application>()
                            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                                ctx, item.requiresPermission
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                viewModel.runSingleTest(category.id, testId)
                            } else {
                                onRequestPermission(category.id, testId, item.requiresPermission)
                            }
                        } else {
                            viewModel.runSingleTest(category.id, testId)
                        }
                    }
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SummaryChip(
    value: String,
    label: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = color.copy(alpha = 0.7f)
        )
    }
}
