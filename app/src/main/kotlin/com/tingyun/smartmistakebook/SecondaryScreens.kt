package com.tingyun.smartmistakebook

import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.work.WorkManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingyun.smartmistakebook.core.domain.ModelApiKey
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityTestResult
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityTester
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationIssue
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationMutationResult
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationSnapshot
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationStore
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationUpdate
import com.tingyun.smartmistakebook.core.domain.BackupRepository
import com.tingyun.smartmistakebook.core.domain.BackupValidation
import com.tingyun.smartmistakebook.core.domain.StorageInventory
import com.tingyun.smartmistakebook.core.domain.currentCapabilityVerification
import com.tingyun.smartmistakebook.core.model.ModelProviderProtocol
import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationReport
import com.tingyun.smartmistakebook.core.model.NetworkMode
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import java.util.Arrays
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class CapabilityOperation {
    IDLE,
    SAVING,
    TESTING,
    CLEARING,
}

@Composable
internal fun SecondaryHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
        }
        Spacer(Modifier.width(4.dp))
        Text(text = title, style = MaterialTheme.typography.headlineSmall, color = Ink)
    }
}

@Composable
internal fun CapabilityScreen(
    capabilities: AppCapabilitySnapshot,
    configurationStore: ModelConfigurationStore?,
    capabilityTester: ModelCapabilityTester?,
    onBack: () -> Unit,
    calibrationReportProvider: (suspend () -> CalibrationReport)? = null,
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val screenScope = rememberCoroutineScope()
    val configuration = configurationStore?.configuration
        ?.collectAsStateWithLifecycle(initialValue = ModelConfigurationSnapshot())
        ?.value ?: ModelConfigurationSnapshot()
    var provider by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(ModelProviderProtocol.DEFAULT) }
    var apiKey by remember { mutableStateOf("") }
    var operation by remember { mutableStateOf(CapabilityOperation.IDLE) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    val formEnabled = operation == CapabilityOperation.IDLE
    var calibrationReport by remember { mutableStateOf<CalibrationReport?>(null) }
    var calibrationUnavailable by remember { mutableStateOf(false) }

    LaunchedEffect(calibrationReportProvider) {
        val provider = calibrationReportProvider ?: return@LaunchedEffect
        calibrationReport = try {
            provider()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            calibrationUnavailable = true
            null
        }
    }

    LaunchedEffect(configuration.configurationVersion, configuration.updatedAtEpochMillis) {
        provider = configuration.provider
        baseUrl = configuration.baseUrl
        modelId = configuration.modelId
        protocol = configuration.protocol
    }

    RootPageColumn(modifier = Modifier.testTag("capability_screen")) {
        SecondaryHeader(title = "大模型设置", onBack = onBack)

        if (capabilities.networkRequestsAllowed && configurationStore != null) {
            PaperDivider(Modifier.padding(vertical = 16.dp))
            SectionHeader("大模型 API")
            Text(
                text = "本机直接连接你选择的服务商，不经过本应用云端；调用费用由你的服务商账户承担。",
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("capability_direct_connection_notice"),
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "API Key 只保存在本机安全存储中，只用于连接服务商，不会作为内容发给模型。",
                modifier = Modifier
                    .padding(top = 4.dp, bottom = 10.dp)
                    .testTag("capability_secret_notice"),
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "接口协议（按服务商选择；选错协议会连不通）",
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .testTag("capability_protocol_label"),
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("capability_protocol_group"),
            ) {
                ModelProviderProtocol.entries.forEach { candidate ->
                    FilterChip(
                        selected = protocol == candidate,
                        onClick = {
                            protocol = candidate
                            operationMessage = null
                        },
                        label = { Text(candidate.displayLabel()) },
                        enabled = formEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .testTag("capability_protocol_${candidate.name}"),
                    )
                }
            }
            OutlinedTextField(
                value = provider,
                onValueChange = {
                    provider = it
                    operationMessage = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("capability_provider"),
                label = { Text("服务商") },
                enabled = formEnabled,
                singleLine = true,
            )
            OutlinedTextField(
                value = modelId,
                onValueChange = {
                    modelId = it
                    operationMessage = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .testTag("capability_model_id"),
                label = { Text("模型 ID") },
                enabled = formEnabled,
                singleLine = true,
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    operationMessage = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .testTag("capability_base_url"),
                label = { Text("Base URL（${protocol.baseUrlHint()}）") },
                enabled = formEnabled,
                singleLine = true,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    operationMessage = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .testTag("capability_api_key"),
                label = { Text("API Key") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                enabled = formEnabled,
                singleLine = true,
            )
            PrimaryActionButton(
                text = if (operation == CapabilityOperation.SAVING) {
                    "正在保存"
                } else {
                    "安全保存配置"
                },
                onClick = {
                    val submittedProvider = provider
                    val submittedBaseUrl = baseUrl
                    val submittedModelId = modelId
                    val submittedProtocol = protocol
                    val submittedApiKey = apiKey
                    operation = CapabilityOperation.SAVING
                    operationMessage = null
                    screenScope.launch {
                        val sourceChars = submittedApiKey.toCharArray()
                        val secret = ModelApiKey.from(sourceChars)
                        try {
                            val result = configurationStore.save(
                                update = ModelConfigurationUpdate(
                                    provider = submittedProvider,
                                    baseUrl = submittedBaseUrl,
                                    modelId = submittedModelId,
                                    protocol = submittedProtocol,
                                ),
                                apiKey = secret,
                            )
                            operationMessage = result.toUserMessage(
                                successMessage = "配置已安全保存在本机；请继续测试图片与讲解能力。",
                            )
                            if (result is ModelConfigurationMutationResult.Success) apiKey = ""
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            operationMessage = "本机安全存储发生异常，配置未生效。"
                        } finally {
                            secret.close()
                            Arrays.fill(sourceChars, '\u0000')
                            operation = CapabilityOperation.IDLE
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .testTag("capability_save"),
                enabled = formEnabled && provider.isNotBlank() &&
                    baseUrl.isNotBlank() && modelId.isNotBlank() && apiKey.isNotBlank(),
            )
            Text(
                text = "能力测试只使用内置合成样例，不发送真实题目或学习记录。",
                modifier = Modifier.padding(top = 12.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = {
                    operation = CapabilityOperation.TESTING
                    operationMessage = null
                    screenScope.launch {
                        try {
                            operationMessage = capabilityTester
                                ?.testSavedConfiguration()
                                ?.toCapabilityTestMessage()
                                ?: "能力测试暂不可用，请稍后再试。"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            operationMessage = "能力测试暂未完成，请稍后再试。"
                        } finally {
                            operation = CapabilityOperation.IDLE
                        }
                    }
                },
                enabled = formEnabled && capabilityTester != null &&
                    configuration.isConfigured && apiKey.isBlank() &&
                    provider == configuration.provider &&
                    baseUrl == configuration.baseUrl && modelId == configuration.modelId &&
                    protocol == configuration.protocol,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag("capability_test"),
            ) {
                Text(
                    if (operation == CapabilityOperation.TESTING) {
                        "正在测试"
                    } else {
                        "测试图片与讲解能力"
                    },
                )
            }
            OutlinedButton(
                onClick = {
                    operation = CapabilityOperation.CLEARING
                    operationMessage = null
                    screenScope.launch {
                        try {
                            operationMessage = configurationStore.clear().toUserMessage(
                                successMessage = "本机配置与密钥已清除。",
                            )
                            apiKey = ""
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            operationMessage = "本机安全存储发生异常，配置未清除。"
                        } finally {
                            operation = CapabilityOperation.IDLE
                        }
                    }
                },
                enabled = formEnabled &&
                    (configuration.isConfigured || configuration.provider.isNotBlank()),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .testTag("capability_clear"),
            ) {
                Text(
                    if (operation == CapabilityOperation.CLEARING) {
                        "正在清除"
                    } else {
                        "清除本机配置与密钥"
                    },
                )
            }
            operationMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .testTag("capability_operation_message"),
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            PaperDivider(Modifier.padding(vertical = 16.dp))
            Text(
                text = "当前版本未启用大模型服务，因此不显示服务商、地址、API Key 和能力测试。",
                modifier = Modifier.testTag("capability_strict_offline_notice"),
                color = InkSecondary,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (calibrationReportProvider != null) {
            PaperDivider(Modifier.padding(vertical = 16.dp))
            SectionHeader("学习模型校准")
            val report = calibrationReport
            when {
                calibrationUnavailable -> Text(
                    text = "校准数据暂不可用，请稍后再试。",
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag("capability_calibration_error"),
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )

                report == null -> Text(
                    text = "正在读取校准数据…",
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag("capability_calibration_loading"),
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )

                report.resolvedPredictions == 0 -> Text(
                    text = "还没有已验证的预测样本。完成复习后，影子预测会与真实结果对比，并在这里生成校准指标。",
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag("capability_calibration_empty"),
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> Column(Modifier.padding(top = 8.dp)) {
                    Text(
                        text = "模型 ${report.modelVersion.modelId} · ${report.modelVersion.version}",
                        modifier = Modifier.testTag("capability_calibration_model"),
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "已验证预测 ${report.resolvedPredictions}/${report.totalPredictions}",
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .testTag("capability_calibration_resolved"),
                        color = Ink,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Brier 分数 " + "%.4f".format(report.overallBrierScore),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .testTag("capability_calibration_brier"),
                        color = Ink,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    report.overallLogLoss?.let { logLoss ->
                        Text(
                            text = "对数损失 " + "%.4f".format(logLoss),
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .testTag("capability_calibration_log_loss"),
                            color = Ink,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        text = "期望校准误差 ECE " + "%.4f".format(report.expectedCalibrationError),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .testTag("capability_calibration_ece"),
                        color = Ink,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "最大分桶偏差 " + "%.4f".format(report.maximumCalibrationDeviation),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .testTag("capability_calibration_max_deviation"),
                        color = Ink,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

internal fun capabilityVerificationSummary(configuration: ModelConfigurationSnapshot): String {
    val verification = configuration.currentCapabilityVerification()
        ?: return "配置已安全保存在本机。使用前请测试图片与讲解能力；发送当前题前会显示具体范围。"
    return when {
        verification.supportsImageInput && verification.supportsStructuredOutput ->
            "能力已测试：可以读取题图并稳定整理讲解；发送当前题前会显示具体范围。"
        verification.supportsStructuredOutput ->
            "能力已测试：文字讲解可用，当前模型暂不能可靠读取题图。"
        verification.supportsImageInput ->
            "能力已测试：能读取合成题图，但返回格式不稳定，暂不启用智能任务。"
        else -> "能力测试未通过，当前模型暂不兼容。"
    }
}

internal fun ModelCapabilityTestResult.toCapabilityTestMessage(): String = when (this) {
    is ModelCapabilityTestResult.Completed -> when {
        verification.supportsImageInput && verification.supportsStructuredOutput ->
            "测试通过：图片读取和讲解都可用。"
        verification.supportsStructuredOutput ->
            "测试完成：文字讲解可用，当前模型暂不能可靠读取题图。"
        verification.supportsImageInput ->
            "测试完成：能读取图片，但返回格式不稳定，暂不启用智能任务。"
        else -> "测试未通过：当前模型暂不兼容。"
    }
    ModelCapabilityTestResult.NotConfigured -> "请先安全保存配置，再开始测试。"
    ModelCapabilityTestResult.ConfigurationChanged -> "配置刚刚发生变化，请重新测试。"
    ModelCapabilityTestResult.AuthenticationFailed -> "API Key 无效或已失效，请更新后重试。"
    ModelCapabilityTestResult.ConnectionFailed -> "暂时无法连接服务商，请检查网络后重试。"
    ModelCapabilityTestResult.ProviderUnavailable -> "服务商暂时没有完成测试，请稍后重试。"
    ModelCapabilityTestResult.InvalidServiceAddress -> "服务地址不可用，请检查后重新保存。"
    ModelCapabilityTestResult.StorageUnavailable -> "本机安全存储暂不可用，请稍后重试。"
}

@Composable
private fun CapabilityRow(icon: ImageVector, title: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(JadeSoft, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = JadeActive)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = InkSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun DataPrivacyScreen(
    capabilities: AppCapabilitySnapshot,
    onBack: () -> Unit,
) {
    RootPageColumn {
        SecondaryHeader(title = "数据与隐私", onBack = onBack)
        SectionHeader("本机数据清单")
        Text(
            "当前题面、原图、学习记录和复习进度都保存在本机应用空间。未完成的临时照片会按过期规则清理，不会自动进入错题本。",
            modifier = Modifier.padding(top = 10.dp),
            color = InkSecondary,
        )
        PaperDivider(Modifier.padding(vertical = 18.dp))
        SectionHeader("智能服务")
        Text(
            text = when (capabilities.networkMode) {
                NetworkMode.LOCAL_FIRST ->
                    "只有你主动发起识题、讲题或整理时，才会发送当次已说明的题目内容。学习记录和整个错题本不会交给模型自行查看或修改。"

                NetworkMode.STRICT_OFFLINE ->
                    "当前版本不使用联网智能服务，本机内容不会发送给模型服务。"
            },
            modifier = Modifier.padding(top = 10.dp),
            color = InkSecondary,
        )
    }
}

@Composable
internal fun StorageScreen(
    onBack: () -> Unit,
    backupRepository: BackupRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inventory by remember { mutableStateOf<StorageInventory?>(null) }
    var operationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var operationBusy by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(backupRepository) {
        inventory = runCatching { backupRepository.inspect() }.getOrNull()
        OrphanAssetGc.enqueue(context)
    }
    val orphanGcInfos by remember(context) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(OrphanAssetGc.UNIQUE_NAME)
    }.collectAsState(initial = emptyList())
    val orphanGcStatus = orphanAssetGcStatusLine(
        orphanAssetGcPhase(orphanGcInfos.map { info -> info.state.name }),
    )

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        operationBusy = true
        operationMessage = "正在创建完整备份…"
        scope.launch {
            try {
                val receipt = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        backupRepository.create(output)
                    }
                }
                operationMessage = if (receipt != null) {
                    "备份完成：${receipt.problemCount} 道题、${receipt.assetCount} 张题图。"
                } else {
                    "无法写入所选位置，备份未完成。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                operationMessage = "备份失败，原数据不受影响。${failure.message.orEmpty()}"
            } finally {
                operationBusy = false
            }
        }
    }

    val validateBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        operationBusy = true
        operationMessage = "正在校验备份…"
        scope.launch {
            try {
                val validation = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        backupRepository.validate(input)
                    }
                }
                operationMessage = when (validation) {
                    is BackupValidation.Valid ->
                        "备份有效：${validation.checkedFileCount} 个文件全部通过校验。"
                    is BackupValidation.Invalid -> "备份无效：${validation.reason}"
                    null -> "无法读取所选备份。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                operationMessage = "校验失败，文件可能已损坏。${failure.message.orEmpty()}"
            } finally {
                operationBusy = false
            }
        }
    }

    val restoreBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        operationBusy = true
        operationMessage = "正在校验并恢复备份…"
        scope.launch {
            try {
                val receipt = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        backupRepository.restore(input)
                    }
                }
                operationMessage = if (receipt != null) {
                    "恢复完成：${receipt.problemCount} 道题、${receipt.assetCount} 张题图。请完全退出并重新打开应用。"
                } else {
                    "无法读取所选备份。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                operationMessage = "恢复失败，已尝试保留原数据。${failure.message.orEmpty()}"
            } finally {
                operationBusy = false
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除全部数据？") },
            text = {
                Text("会删除本机数据库、题图、设置和临时文件，且不会自动备份。请先确认已创建完整备份。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        operationBusy = true
                        operationMessage = "正在删除全部数据…"
                        scope.launch {
                            try {
                                val receipt = withContext(Dispatchers.IO) {
                                    backupRepository.deleteAllData()
                                }
                                operationMessage =
                                    "已删除数据库 ${formatBytes(receipt.deletedDatabaseBytes)}、" +
                                        "题图 ${formatBytes(receipt.deletedAssetBytes)}、" +
                                        "设置和临时文件。请重新打开应用。"
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                operationMessage =
                                    "删除未全部完成，请重启后重试。${failure.message.orEmpty()}"
                            } finally {
                                operationBusy = false
                            }
                        }
                    },
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("取消")
                }
            },
        )
    }

    RootPageColumn(modifier = Modifier.testTag("storage_screen")) {
        SecondaryHeader(title = "存储、备份与导出", onBack = onBack)
        SectionHeader("本机存储")
        val current = inventory
        Text(
            text = if (current == null) {
                "正在读取本机占用…"
            } else {
                "学习记录 ${formatBytes(current.databaseBytes)} · 题图 ${formatBytes(current.assetBytes)} · 可清理临时文件 ${formatBytes(current.cleanableBytes)}"
            },
            modifier = Modifier.padding(top = 10.dp),
            color = InkSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        PaperDivider(Modifier.padding(vertical = 18.dp))
        SectionHeader("完整备份")
        Text(
            "备份包含学习数据库和规范题图，不包含 API Key、授权租约或临时文件。恢复会先校验，再分阶段替换；失败时回滚原数据。",
            modifier = Modifier.padding(top = 10.dp),
            color = InkSecondary,
        )
        PrimaryActionButton(
            text = if (operationBusy) "正在处理…" else "创建完整备份",
            onClick = { createBackup.launch("smart-mistake-book-${System.currentTimeMillis()}.smbk") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .testTag("storage_create_backup"),
            enabled = !operationBusy,
        )
        OutlinedButton(
            onClick = { validateBackup.launch(arrayOf("application/octet-stream", "application/zip")) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("storage_validate_backup"),
            enabled = !operationBusy,
        ) {
            Text("校验一个备份文件")
        }
        OutlinedButton(
            onClick = {
                restoreBackup.launch(
                    arrayOf("application/octet-stream", "application/zip"),
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("storage_restore_backup"),
            enabled = !operationBusy,
        ) {
            Text("从备份恢复（替换当前数据）")
        }
        OutlinedButton(
            onClick = { confirmDelete = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("storage_delete_all"),
            enabled = !operationBusy,
        ) {
            Text("删除全部数据")
        }
        OutlinedButton(
            onClick = {
                if (operationBusy) return@OutlinedButton
                operationBusy = true
                operationMessage = null
                scope.launch {
                    try {
                        val removed = withContext(Dispatchers.IO) {
                            backupRepository.cleanupOrphanAssets()
                        }
                        operationMessage = if (removed == 0) {
                            "没有需要清理的孤立题图。"
                        } else {
                            "已清理 $removed 张不再被任何题面引用的题图。"
                        }
                        inventory = withContext(Dispatchers.IO) {
                            backupRepository.inspect()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        operationMessage =
                            "清理未完成，原题图不受影响。${failure.message.orEmpty()}"
                    } finally {
                        operationBusy = false
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("storage_cleanup_orphans"),
            enabled = !operationBusy,
        ) {
            Text("清理孤立题图")
        }
        orphanGcStatus?.let { message ->
            Text(
                text = message,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .testTag("storage_orphan_gc_status"),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        operationMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .testTag("storage_operation_message"),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        PaperDivider(Modifier.padding(vertical = 18.dp))
        SectionHeader("单题导出")
        Text(
            "单道错题可在详情页保存或打印 PDF；错题本还能把当前筛选结果整理成一份 A4 练习。两种方式都只使用已经确认的正式题面。",
            modifier = Modifier.padding(top = 10.dp),
            color = InkSecondary,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

private fun ModelConfigurationMutationResult.toUserMessage(successMessage: String): String = when (this) {
    is ModelConfigurationMutationResult.Success -> successMessage

    is ModelConfigurationMutationResult.Rejected -> {
        val fields = issues.mapTo(linkedSetOf()) { issue ->
            when (issue) {
                ModelConfigurationIssue.PROVIDER_REQUIRED,
                ModelConfigurationIssue.PROVIDER_TOO_LONG,
                ModelConfigurationIssue.PROVIDER_CONTAINS_CONTROL_CHARACTER,
                -> "服务商"

                ModelConfigurationIssue.BASE_URL_REQUIRED,
                ModelConfigurationIssue.BASE_URL_TOO_LONG,
                ModelConfigurationIssue.BASE_URL_INVALID,
                ModelConfigurationIssue.BASE_URL_CONTAINS_CONTROL_CHARACTER,
                ModelConfigurationIssue.BASE_URL_CREDENTIALS_NOT_ALLOWED,
                ModelConfigurationIssue.BASE_URL_QUERY_OR_FRAGMENT_NOT_ALLOWED,
                ModelConfigurationIssue.BASE_URL_PATH_NOT_ALLOWED,
                ModelConfigurationIssue.INSECURE_HTTP_NOT_ALLOWED,
                -> "Base URL（请填写不含账号信息、查询参数或不安全路径的 HTTPS 地址）"

                ModelConfigurationIssue.MODEL_ID_REQUIRED,
                ModelConfigurationIssue.MODEL_ID_TOO_LONG,
                ModelConfigurationIssue.MODEL_ID_CONTAINS_CONTROL_CHARACTER,
                -> "模型 ID"

                ModelConfigurationIssue.API_KEY_REQUIRED,
                ModelConfigurationIssue.API_KEY_TOO_LONG,
                ModelConfigurationIssue.API_KEY_CONTAINS_WHITESPACE_OR_CONTROL_CHARACTER,
                -> "API Key"
            }
        }
        "配置未保存，请检查：${fields.joinToString("、")}。"
    }

    ModelConfigurationMutationResult.MissingConfiguration -> "本机没有可更新的配置。"
    ModelConfigurationMutationResult.StorageUnavailable ->
        "本机安全存储暂不可用，配置未生效。"
}

/** 设置界面里协议的中文标签（spec 2026-09-08-multi-protocol §3.2 显式协议选择）。 */
private fun ModelProviderProtocol.displayLabel(): String = when (this) {
    ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS -> "OpenAI 兼容（默认）"
    ModelProviderProtocol.OPENAI_RESPONSES -> "OpenAI Responses"
    ModelProviderProtocol.ANTHROPIC_MESSAGES -> "Anthropic Messages"
    ModelProviderProtocol.GEMINI_GENERATE_CONTENT -> "Google Gemini"
}

/**
 * Base URL 该填什么由协议决定：各协议的端点路径不同（如 Anthropic 是 /v1/messages、
 * Gemini 是 /v1beta/models/{model}:generateContent），填错会直接连不通。
 */
private fun ModelProviderProtocol.baseUrlHint(): String = when (this) {
    ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS -> "例如 https://服务地址/v1"
    ModelProviderProtocol.OPENAI_RESPONSES -> "例如 https://api.openai.com/v1"
    ModelProviderProtocol.ANTHROPIC_MESSAGES -> "例如 https://api.anthropic.com"
    ModelProviderProtocol.GEMINI_GENERATE_CONTENT -> "例如 https://generativelanguage.googleapis.com"
}
