package com.tera.tera

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** Settings → Languages & Frameworks → Tera. */
class TeraSettingsConfigurable(private val project: Project) : Configurable {
    private val choices = listOf("auto" to "Detect from Cargo.toml (default: Tera 2.x)", "1" to "Tera 1.x", "2" to "Tera 2.x")
    private val combo = JComboBox(choices.map { it.second }.toTypedArray())
    private val hints = JCheckBox("In Tera 1.x projects, mark constructs that Tera 2 removed or renamed")

    override fun getDisplayName() = "Tera"

    override fun createComponent(): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Tera version:"))
            add(combo)
        }
        val note = JLabel("<html>Selects the built-in filters, tests, functions and tags the editor knows about, and which " +
            "constructs are reported. Detection reads the nearest <code>Cargo.toml</code> (a <code>tera</code> dependency, " +
            "or <code>rocket_dyn_templates</code>, which uses Tera 1.x), then <code>Cargo.lock</code>.</html>")
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(JPanel(BorderLayout()).apply { add(row, BorderLayout.NORTH); add(hints, BorderLayout.CENTER); add(note, BorderLayout.SOUTH) }, BorderLayout.NORTH)
            reset()
        }
    }

    private val state get() = TeraSettings.getInstance(project).state

    override fun isModified() =
        choices[combo.selectedIndex].first != state.version || hints.isSelected != state.migrationHints

    override fun apply() {
        state.version = choices[combo.selectedIndex].first
        state.migrationHints = hints.isSelected
    }

    override fun reset() {
        combo.selectedIndex = choices.indexOfFirst { it.first == state.version }.coerceAtLeast(0)
        hints.isSelected = state.migrationHints
    }
}
