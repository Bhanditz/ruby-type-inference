package org.jetbrains.plugins.ruby.ruby.codeInsight.stateTracker

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.util.Processor
import org.jetbrains.plugins.ruby.ruby.codeInsight.symbols.RubySymbolProviderBase
import org.jetbrains.plugins.ruby.ruby.codeInsight.symbols.fqn.FQN
import org.jetbrains.plugins.ruby.ruby.codeInsight.symbols.structure.Symbol
import org.jetbrains.plugins.ruby.ruby.lang.psi.RPsiElement

class ClassHierarchySymbolProvider : RubySymbolProviderBase() {
    override fun processDynamicSymbols(symbol: Symbol?, element: RPsiElement?, fqn: FQN?, processor: Processor<Symbol>?): Boolean {
        if (symbol == null || fqn == null || element == null || processor == null) {
            return true
        }

        val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return true
        val hierarchy = RubyClassHierarchyWithCaching.getInstance(module)?: return true
        hierarchy.getMembersWithCaching(fqn.fullPath, symbol.rootSymbol).forEach {
            if (!processor.process(it)) {
                return false
            }
        }
        return true
    }
}