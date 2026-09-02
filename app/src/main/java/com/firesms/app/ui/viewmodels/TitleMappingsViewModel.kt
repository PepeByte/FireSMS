package com.firesms.app.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firesms.app.FireSmsApp
import com.firesms.app.data.local.entity.TitleMappingRule
import com.firesms.app.data.remote.FireflyApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class TitleMappingsViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as FireSmsApp).database
    private val dao = db.titleMappingRuleDao()
    private val prefs = (application as FireSmsApp).preferencesManager

    val mappings: StateFlow<List<TitleMappingRule>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _saveResult = MutableStateFlow<String?>(null)
    val saveResult: StateFlow<String?> = _saveResult.asStateFlow()

    private val _categorySuggestions = MutableStateFlow<List<String>>(emptyList())
    val categorySuggestions: StateFlow<List<String>> = _categorySuggestions.asStateFlow()

    private val _budgetSuggestions = MutableStateFlow<List<String>>(emptyList())
    val budgetSuggestions: StateFlow<List<String>> = _budgetSuggestions.asStateFlow()

    private val _loadingSuggestions = MutableStateFlow(false)
    val loadingSuggestions: StateFlow<Boolean> = _loadingSuggestions.asStateFlow()

    init {
        loadSuggestions()
    }

    private fun loadSuggestions() {
        viewModelScope.launch {
            val baseUrl = prefs.fireflyUrl.first()
            val token = prefs.fireflyToken.first()
            if (baseUrl.isEmpty() || token.isEmpty()) return@launch

            _loadingSuggestions.value = true
            val api = FireflyApi(token)
            try {
                val categories = api.getCategories(baseUrl)
                _categorySuggestions.value = categories.map { it.attributes.name }

                val budgets = api.getBudgets(baseUrl)
                _budgetSuggestions.value = budgets.map { it.attributes.name }
            } catch (_: Exception) {
                // Firefly not reachable — suggestions stay empty
            } finally {
                api.close()
                _loadingSuggestions.value = false
            }
        }
    }

    fun refreshSuggestions() {
        loadSuggestions()
    }

    fun upsertMapping(rule: TitleMappingRule) {
        viewModelScope.launch {
            try {
                dao.upsert(rule)
                _saveResult.value = "Mapping saved"
            } catch (e: Exception) {
                _saveResult.value = "Error: ${e.message}"
            }
        }
    }

    fun toggleEnabled(rule: TitleMappingRule) {
        viewModelScope.launch {
            dao.update(rule.copy(enabled = !rule.enabled))
        }
    }

    fun deleteMapping(rule: TitleMappingRule) {
        viewModelScope.launch {
            dao.delete(rule)
        }
    }

    fun clearSaveResult() {
        _saveResult.value = null
    }
}
