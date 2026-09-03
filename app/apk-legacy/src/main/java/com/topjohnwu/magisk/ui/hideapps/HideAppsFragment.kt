package com.topjohnwu.magisk.ui.hideapps

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.BaseFragment
import com.topjohnwu.magisk.arch.viewModel
import com.topjohnwu.magisk.core.ktx.hideKeyboard
import com.topjohnwu.magisk.databinding.FragmentHideAppsMd2Binding
import kotlinx.coroutines.launch
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.addItemSpacing
import rikka.recyclerview.fixEdgeEffect
import com.topjohnwu.magisk.core.R as CoreR

class HideAppsFragment : BaseFragment<FragmentHideAppsMd2Binding>(), MenuProvider {

    override val layoutRes = R.layout.fragment_hide_apps_md2
    override val viewModel by viewModel<HideAppsViewModel>()

    private val targetAdapter = TargetAdapter { packageName ->
        viewModel.togglePackage(packageName)
    }
    private lateinit var searchView: SearchView

    override fun onStart() {
        super.onStart()
        updateTitle(viewModel.mode.value)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.appList.apply {
            adapter = targetAdapter
            addEdgeSpacing(top = R.dimen.l_50, bottom = R.dimen.l1)
            addItemSpacing(R.dimen.l1, R.dimen.l_50, R.dimen.l1)
            fixEdgeEffect()
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState != RecyclerView.SCROLL_STATE_IDLE) activity?.hideKeyboard()
                }
            })
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.rows.collect { rows ->
                        targetAdapter.submitList(rows)
                        binding.loading.visibility = View.GONE
                    }
                }
                launch {
                    viewModel.mode.collect { mode ->
                        updateTitle(mode)
                        activity?.invalidateOptionsMenu()
                    }
                }
            }
        }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_hide_apps, menu)
        searchView = menu.findItem(R.id.action_search).actionView as SearchView
        searchView.queryHint = getString(CoreR.string.hide_apps_search)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.setQuery(query.orEmpty())
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.setQuery(newText.orEmpty())
                return true
            }
        })
    }

    override fun onPrepareMenu(menu: Menu) {
        menu.findItem(R.id.action_viewer_whitelist).isVisible =
            viewModel.mode.value == HideAppsViewModel.ListMode.HIDDEN
        menu.findItem(R.id.action_show_system).isChecked = viewModel.showSystem
        menu.findItem(R.id.action_show_OS).apply {
            isChecked = viewModel.showOs
            isEnabled = viewModel.showSystem
        }
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_viewer_whitelist) {
            viewModel.setMode(HideAppsViewModel.ListMode.VIEWER_WHITELIST)
            return true
        }
        if (item.itemId == R.id.action_show_system) {
            viewModel.setShowSystem(!item.isChecked)
            item.isChecked = !item.isChecked
            activity?.invalidateOptionsMenu()
            return true
        }
        if (item.itemId == R.id.action_show_OS) {
            viewModel.setShowOs(!item.isChecked)
            item.isChecked = !item.isChecked
            return true
        }
        return false
    }

    override fun onBackPressed(): Boolean {
        if (::searchView.isInitialized && !searchView.isIconified) {
            searchView.isIconified = true
            return true
        }
        if (viewModel.mode.value == HideAppsViewModel.ListMode.VIEWER_WHITELIST) {
            viewModel.setMode(HideAppsViewModel.ListMode.HIDDEN)
            return true
        }
        return super.onBackPressed()
    }

    private fun updateTitle(mode: HideAppsViewModel.ListMode) {
        activity?.setTitle(
            if (mode == HideAppsViewModel.ListMode.HIDDEN) CoreR.string.hide_apps_title
            else CoreR.string.hide_apps_viewer_whitelist_title
        )
    }
}

private class TargetAdapter(
    private val onToggle: (String) -> Unit,
) : ListAdapter<TargetRow, TargetAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_hide_apps_target, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.app_icon)
        private val label: TextView = view.findViewById(R.id.app_label)
        private val packageName: TextView = view.findViewById(R.id.package_name)
        private val checkBox: CheckBox = view.findViewById(R.id.package_checked)

        fun bind(row: TargetRow) {
            icon.setImageDrawable(row.app.icon)
            label.text = row.app.label
            packageName.text = row.app.packageName
            checkBox.isChecked = row.checked
            itemView.setOnClickListener { onToggle(row.app.packageName) }
            checkBox.setOnClickListener { onToggle(row.app.packageName) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<TargetRow>() {
        override fun areItemsTheSame(oldItem: TargetRow, newItem: TargetRow) =
            oldItem.app.packageName == newItem.app.packageName

        override fun areContentsTheSame(oldItem: TargetRow, newItem: TargetRow) =
            oldItem.app.label == newItem.app.label && oldItem.checked == newItem.checked
    }
}
