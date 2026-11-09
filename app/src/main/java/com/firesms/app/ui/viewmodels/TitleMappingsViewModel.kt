package com.firesms.app.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firesms.app.FireSmsApp
import com.firesms.app.data.local.entity.TitleMappingRule
import com.firesms.app.data.remote.FireflyApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Category and budget names from Firefly, offered while editing a mapping. */
data class MappingSuggestions(
    val categories: List<String> = emptyList(),
    val budgets: List<String> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    /** Firefly is not set up, so there is nothing to suggest. */
    val notConnected: Boolean = false,
    val failed: Boolean = false
)

class TitleMappingsViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as FireSmsApp).database
    private val dao = db.titleMappingRuleDao()
    private val prefs = (application as FireSmsApp).preferencesManager

    val mappings: StateFlow<List<TitleMappingRule>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _saveResult = MutableStateFlow<String?>(null)
    val saveResult: StateFlow<String?> = _saveResult.asStateFlow()

    private val _suggestions = MutableStateFlow(MappingSuggestions())
    val suggestions: StateFlow<MappingSuggestions> = _suggestions.asStateFlow()

    /** Loads suggestions the first time the editor opens; later opens reuse them. */
    fun ensureSuggestionsLoaded() {
        val current = _suggestions.value
        if (current.loading || current.loaded) return
        loadSuggestions()
    }

    fun retrySuggestions() {
        if (_suggestions.value.loading) return
        loadSuggestions()
    }

    private fun loadSuggestions() {
        _suggestions.update { it.copy(loading = true, failed = false, notConnected = false) }
        viewModelScope.launch {
            val baseUrl = prefs.fireflyUrl.first()
            val token = prefs.fireflyToken.first()
            if (baseUrl.isEmpty() || token.isEmpty()) {
                _suggestions.update { it.copy(loading = false, notConnected = true) }
                return@launch
            }

            val api = FireflyApi(token)
            try {
                val categories = com.firesms.app.data.remote.FireflyLookupCache.categories(api, baseUrl).map { it.attributes.name }
                val budgets = com.firesms.app.data.remote.FireflyLookupCache.budgets(api, baseUrl).map { it.attributes.name }
                _suggestions.value = MappingSuggestions(categories = categories, budgets = budgets, loaded = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _suggestions.update { it.copy(loading = false, failed = true) }
            } finally {
                api.close()
            }
        }
    }

    fun upsertMapping(rule: TitleMappingRule) {
        viewModelScope.launch {
            try {
                dao.upsert(rule)
                _saveResult.value = "Categorization saved"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _saveResult.value = "Couldn't save the categorization: ${e.message}"
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
