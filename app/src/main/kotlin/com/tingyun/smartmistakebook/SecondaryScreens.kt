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
import com.tingyun.smartmistakebook.core.domain.currentCapabilityVerification
import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayRepository
import com.tingyun.smartmistakebook.core.domain.LearningMasteryPrivacyRepository
import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
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
import kotlinx.coroutines.launch

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
    var apiKey by remember { mutableStateOf("") }
    var operation by remember { mutableStateOf(CapabilityOperation.IDLE) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    val formEnabled = operation == CapabilityOperation.IDLE

    LaunchedEffect(configuration.configurationVersion, configuration.updatedAtEpochMillis) {
        provider = configuration.provider
        baseUrl = configuration.baseUrl
        modelId = configuration.modelId
    }

    RootPageColumn(modifier = Modifier.testTag("capability_screen")) {
        SecondaryHeader(title = "大模型设置", onBack = onBack)
        SectionHeader("当前可用能力")
        CapabilityRow(
            icon = Icons.Outlined.Memory,
            title = if (capabilities.networkMode == NetworkMode.STRICT_OFFLINE) {
                "智能服务未启用"
            } else {
                "模型优先"
            },
            detail = if (capabilities.networkRequestsAllowed) {
                "学习记录留在本机；只有你发起任务时，才发送当次已说明的内容"
            } else {
                "当前版本未启用联网智能服务，题库、复习和已保存内容仍可查看"
            },
        )
        CapabilityRow(
            icon = Icons.Outlined.Security,
            title = "拍题后直接处理",
            detail = "题面清楚时直接进入讲解或收录；只有无法判断时才请你补充",
        )

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
                text = if (configuration.isConfigured) {
                    capabilityVerificationSummary(configuration)
                } else {
                    "支持 OpenAI 兼容的多模态模型。保存配置不会联网；第一次发送题目前会显示发送范围。"
                },
                modifier = Modifier.padding(bottom = 12.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
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
                label = { Text("Base URL（例如 https://服务地址/v1）") },
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
                    baseUrl == configuration.baseUrl && modelId == configuration.modelId,
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
    learningMasteryPrivacy: LearningMasteryPrivacyRepository?,
    onBack: () -> Unit,
) {
    val screenScope = rememberCoroutineScope()
    var showEraseConfirmation by rememberSaveable { mutableStateOf(false) }
    var erasing by remember { mutableStateOf(false) }
    var eraseStatus by remember { mutableStateOf<String?>(null) }

    if (showEraseConfirmation) {
        AlertDialog(
            onDismissRequest = { showEraseConfirmation = false },
            title = { Text("清除学习记录？") },
            text = {
                Text("会清除本机掌握情况与学习记录，错题本里的题目不会删除。")
            },
            confirmButton = {
                TextButton(
                    enabled = !erasing,
                    onClick = {
                        val repository = learningMasteryPrivacy
                        if (repository == null) {
                            eraseStatus = "暂时无法清除学习记录"
                            showEraseConfirmation = false
                            return@TextButton
                        }
                        erasing = true
                        eraseStatus = null
                        screenScope.launch {
                            try {
                                repository.eraseAllLearningData()
                                eraseStatus = "学习记录已清除"
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                eraseStatus = "清除失败，请稍后重试"
                            } finally {
                                erasing = false
                                showEraseConfirmation = false
                            }
                        }
                    },
                ) {
                    Text(if (erasing) "正在清除" else "清除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEraseConfirmation = false },
                ) {
                    Text("取消")
                }
            },
        )
    }

    RootPageColumn {
        SecondaryHeader(title = "数据与隐私", onBack = onBack)
        SectionHeader("本机数据清单")
        Text(
            "当前题面、原图、学习记录和复习进度都保存在本机应用空间。未完成的临时照片会按过期规则清理，不会自动进入错题本。",
            modifier = Modifier.padding(top = 10.dp),
            color = InkSecondary,
        )
        PrimaryActionButton(
            text = if (erasing) "正在清除" else "清除学习记录",
            onClick = {
                eraseStatus = null
                showEraseConfirmation = true
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(52.dp)
                    .testTag("privacy_erase_learning_memory"),
        )
        eraseStatus?.let { message ->
            Text(
                text = message,
                modifier =
                    Modifier
                        .padding(top = 10.dp)
                        .testTag("privacy_erase_status"),
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
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
    learningMasteryDisplay: LearningMasteryDisplayRepository?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val screenScope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<LearningMemoryExportDocument?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val document = pendingExport
        if (uri == null || document == null) {
            pendingExport = null
            exportStatus = "已取消导出"
            return@rememberLauncherForActivityResult
        }
        val wrote = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(document.text.toByteArray(Charsets.UTF_8))
            } != null
        }.getOrDefault(false)
        pendingExport = null
        exportStatus = if (wrote) "学习记录已导出" else "导出失败，请稍后重试"
    }

    RootPageColumn {
        SecondaryHeader(title = "存储与导出", onBack = onBack)
        SectionHeader("本机存储")
        CapabilityRow(
            Icons.Outlined.Storage,
            "当前数据",
            "当前题面、原图、学习记录与复习进度保存在本机应用空间",
        )
        PaperDivider(Modifier.padding(vertical = 18.dp))
        SectionHeader("导出")
        Text(
            "单道错题可在详情页保存或打印 PDF；错题本还能把当前筛选结果整理成一份 A4 练习。学习记录可导出为文本文件。",
            modifier = Modifier.padding(top = 10.dp),
            color = InkSecondary,
        )
        PrimaryActionButton(
            text = if (exporting) "正在准备" else "导出学习记录",
            onClick = {
                val repository = learningMasteryDisplay
                if (repository == null) {
                    exportStatus = "暂时无法导出学习记录"
                    return@PrimaryActionButton
                }
                if (exporting) return@PrimaryActionButton
                exporting = true
                exportStatus = null
                screenScope.launch {
                    try {
                        when (val result = repository.loadLearningMemoryExport()) {
                            is LearningMemoryExportLoadResult.Ready -> {
                                pendingExport = result.document
                                exportLauncher.launch("学习记录.txt")
                            }

                            LearningMemoryExportLoadResult.Unavailable -> {
                                exportStatus = "暂时无法导出学习记录"
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        exportStatus = "导出失败，请稍后重试"
                    } finally {
                        exporting = false
                    }
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(52.dp)
                    .testTag("storage_export_learning_memory"),
        )
        exportStatus?.let { message ->
            Text(
                text = message,
                modifier =
                    Modifier
                        .padding(top = 10.dp)
                        .testTag("storage_export_status"),
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
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
