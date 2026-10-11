package com.example.droidnode.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.droidnode.runtime.PRootRunner
import com.example.droidnode.runtime.ProcessOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NodeUiState(
    val isInstalling: Boolean = false,
    val installLogs: List<String> = emptyList()
)

class NodeRuntimeViewModel(application: Application) : AndroidViewModel(application) {
    private val runner = PRootRunner(application)
    private val _uiState = MutableStateFlow(NodeUiState())
    val uiState = _uiState.asStateFlow()

    fun installPackage(packageName: String) {
        if (_uiState.value.isInstalling) return
        _uiState.update { it.copy(isInstalling = true) }

        viewModelScope.launch {
            runner.npmInstall(packageName).collect { output ->
                when (output) {
                    is ProcessOutput.Stdout -> {
                        _uiState.update { it.copy(installLogs = (it.installLogs + output.line).takeLast(300)) }
                    }
                    is ProcessOutput.Stderr -> {
                        _uiState.update { it.copy(installLogs = (it.installLogs + "[ERR] ${output.line}").takeLast(300)) }
                    }
                    is ProcessOutput.Completed -> {
                        _uiState.update { it.copy(isInstalling = false) }
                    }
                    is ProcessOutput.Error -> {
                        _uiState.update { it.copy(isInstalling = false, installLogs = it.installLogs + "[FATAL] ${output.throwable.message}") }
                    }
                }
            }
        }
    }

    fun clearInstallLogs() {
        _uiState.update { it.copy(installLogs = emptyList()) }
    }
}
 
