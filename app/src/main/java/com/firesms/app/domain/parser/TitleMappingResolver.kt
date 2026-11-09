package com.firesms.app.domain.parser

import com.firesms.app.data.local.TitleMappingRuleDao
import com.firesms.app.data.local.entity.TitleMappingRule
import kotlinx.coroutines.flow.first

class TitleMappingResolver(private val dao: TitleMappingRuleDao) {

    /**
     * Returns the first enabled mapping whose titlePattern matches [title]
     * (case-insensitive contains match). Mappings are checked from the lowest priority number
     * up (ties by title pattern), so the lowest-numbered match wins.
     */
    suspend fun resolve(title: String): TitleMappingRule? {
        val rules = dao.getAllEnabled().first()
        for (rule in rules) {
            if (title.contains(rule.titlePattern, ignoreCase = true)) {
                return rule
            }
        }
        return null
    }
}
