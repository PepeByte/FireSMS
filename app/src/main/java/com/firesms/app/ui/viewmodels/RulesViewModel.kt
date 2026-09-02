package com.firesms.app.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firesms.app.FireSmsApp
import com.firesms.app.data.local.entity.ParserRule
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class RulesViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = (application as FireSmsApp).database.parserRuleDao()

    val rules: StateFlow<List<ParserRule>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleEnabled(rule: ParserRule) {
        viewModelScope.launch {
            dao.upsert(rule.copy(enabled = !rule.enabled))
        }
    }

    fun deleteRule(rule: ParserRule) {
        viewModelScope.launch {
            dao.delete(rule)
        }
    }
}
