package com.topjohnwu.magisk.ui.deny

import android.view.View
import android.view.ViewGroup
import androidx.databinding.Bindable
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.startAnimations
import com.topjohnwu.magisk.databinding.DiffItem
import com.topjohnwu.magisk.databinding.ObservableRvItem
import com.topjohnwu.magisk.databinding.addOnPropertyChangedCallback
import com.topjohnwu.magisk.databinding.set
import kotlin.math.roundToInt

class DenyListRvItem(
    val info: AppProcessInfo,
    private val toggleAll: (DenyListRvItem, Boolean) -> Unit,
    toggleProcess: (ProcessRvItem, Boolean) -> Unit,
) : ObservableRvItem(), DiffItem<DenyListRvItem>, Comparable<DenyListRvItem> {

    override val layoutRes get() = R.layout.item_hide_md2

    val processes = info.processes.map { ProcessRvItem(it, toggleProcess) }

    @get:Bindable
    var isExpanded = false
        set(value) = set(value, field, { field = it }, BR.expanded)

    var itemsChecked = 0
        set(value) = set(value, field, { field = it }, BR.checkedPercent)

    val isChecked get() = itemsChecked != 0

    @get:Bindable
    val checkedPercent get() = (itemsChecked.toFloat() / processes.size * 100).roundToInt()

    private var _state: Boolean? = false
        set(value) = set(value, field, { field = it }, BR.state)

    @get:Bindable
    var state: Boolean?
        get() = _state
        set(value) {
            if (value != null && value != _state) toggleAll(this, value)
        }

    init {
        processes.forEach { it.addOnPropertyChangedCallback(BR.enabled) { recalculateChecked() } }
        addOnPropertyChangedCallback(BR.expanded) { recalculateChecked() }
        recalculateChecked()
    }

    fun toggleExpand(v: View) {
        (v.parent as? ViewGroup)?.startAnimations()
        isExpanded = !isExpanded
    }

    private fun recalculateChecked() {
        itemsChecked = processes.count { it.isEnabled }
        _state = if (isExpanded) {
            when (itemsChecked) {
                0 -> false
                processes.size -> true
                else -> null
            }
        } else {
            val defaultProcesses = processes.filter { it.defaultSelection }
            when (defaultProcesses.count { it.isEnabled }) {
                0 -> false
                defaultProcesses.size -> true
                else -> null
            }
        }
    }

    override fun compareTo(other: DenyListRvItem) = comparator.compare(this, other)

    companion object {
        private val comparator = compareBy<DenyListRvItem>(
            { it.itemsChecked == 0 },
            { it.info }
        )
    }

}

class ProcessRvItem(
    val process: ProcessInfo,
    private val toggleProcess: (ProcessRvItem, Boolean) -> Unit,
) : ObservableRvItem(), DiffItem<ProcessRvItem> {

    override val layoutRes get() = R.layout.item_hide_process_md2

    val displayName = if (process.isIsolated) "(isolated) ${process.name}" else process.name

    @get:Bindable
    var isEnabled
        get() = process.isEnabled
        set(value) {
            if (value != process.isEnabled) toggleProcess(this, value)
        }

    val defaultSelection get() =
        process.isIsolated || process.isAppZygote || process.name == process.packageName

    override fun itemSameAs(other: ProcessRvItem) =
        process.name == other.process.name && process.packageName == other.process.packageName

    override fun contentSameAs(other: ProcessRvItem) =
        process.isEnabled == other.process.isEnabled
}
