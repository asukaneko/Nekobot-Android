package com.nekobot.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.repository.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 所有 ViewModel 的基类：统一管理 loading / error / toast 状态。 */
abstract class BaseViewModel : ViewModel() {

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    protected val repo get() = ServiceContainer.repository

    fun setLoading(v: Boolean) { _loading.value = v }
    fun showError(msg: String?) { _error.value = msg }
    fun clearError() { _error.value = null }
    fun showToast(msg: String) { _toast.value = msg }
    fun clearToast() { _toast.value = null }

    /** 在协程中执行挂起操作，自动管理 loading 与错误。 */
    fun <T> launchWith(
        onError: (String) -> Unit = { showError(it) },
        block: suspend () -> Resource<T>
    ) {
        viewModelScope.launch {
            setLoading(true)
            try {
                when (val res = block()) {
                    is Resource.Success -> {}
                    is Resource.Error -> onError(res.message)
                    is Resource.Loading -> {}
                }
            } catch (e: Exception) {
                onError(e.message ?: "未知错误")
            } finally {
                setLoading(false)
            }
        }
    }

    /** 执行带结果的挂起操作。 */
    fun <T> launchResult(
        block: suspend () -> Resource<T>,
        onSuccess: (T) -> Unit,
        onError: (String) -> Unit = { showError(it) }
    ) {
        viewModelScope.launch {
            setLoading(true)
            try {
                when (val res = block()) {
                    is Resource.Success -> onSuccess(res.data)
                    is Resource.Error -> onError(res.message)
                    is Resource.Loading -> {}
                }
            } catch (e: Exception) {
                onError(e.message ?: "未知错误")
            } finally {
                setLoading(false)
            }
        }
    }
}
