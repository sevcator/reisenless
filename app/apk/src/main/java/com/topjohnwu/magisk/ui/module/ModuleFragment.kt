package com.topjohnwu.magisk.ui.module

import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.appcompat.R as AppCompatR
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.MenuProvider
import com.google.android.material.color.MaterialColors
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.BaseFragment
import com.topjohnwu.magisk.arch.viewModel
import com.topjohnwu.magisk.core.utils.MediaStoreUtils.displayName
import com.topjohnwu.magisk.databinding.FragmentModuleMd2Binding
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.addInvalidateItemDecorationsObserver
import rikka.recyclerview.addItemSpacing
import rikka.recyclerview.fixEdgeEffect
import com.topjohnwu.magisk.core.R as CoreR

class ModuleFragment : BaseFragment<FragmentModuleMd2Binding>(), MenuProvider {

    override val layoutRes = R.layout.fragment_module_md2
    override val viewModel by viewModel<ModuleViewModel>()
    private lateinit var installedSearchView: SearchView

    override fun onStart() {
        super.onStart()
        activity?.title = resources.getString(CoreR.string.modules)
        viewModel.data.observe(this) {
            it ?: return@observe
            val displayName = runCatching { it.displayName }.getOrNull() ?: return@observe
            viewModel.requestInstallLocalModule(it, displayName)
            viewModel.data.value = null
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.moduleList.apply {
            addEdgeSpacing(top = R.dimen.l_50, bottom = R.dimen.l1)
            addItemSpacing(R.dimen.l1, R.dimen.l_50, R.dimen.l1)
            fixEdgeEffect()
            post { addInvalidateItemDecorationsObserver() }
        }
        viewModel.hasInstalledModules.observe(viewLifecycleOwner) {
            activity?.invalidateOptionsMenu()
        }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_module, menu)
        val accent = MaterialColors.getColor(
            requireView(),
            AppCompatR.attr.colorPrimary,
            Color.MAGENTA,
        )
        menu.findItem(R.id.action_module_search).icon?.mutate()?.let { icon ->
            DrawableCompat.setTint(icon, accent)
        }
        installedSearchView = menu.findItem(R.id.action_module_search).actionView as SearchView
        installedSearchView.queryHint = getString(CoreR.string.module_search_installed)
        installedSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.searchInstalled(query.orEmpty())
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.searchInstalled(newText.orEmpty())
                return true
            }
        })
    }

    override fun onPrepareMenu(menu: Menu) {
        menu.findItem(R.id.action_module_search).isVisible =
            viewModel.hasInstalledModules.value == true
    }

    override fun onMenuItemSelected(item: MenuItem) = false

    override fun onBackPressed(): Boolean {
        if (::installedSearchView.isInitialized && !installedSearchView.isIconified) {
            installedSearchView.isIconified = true
            viewModel.searchInstalled("")
            return true
        }
        return super.onBackPressed()
    }

    override fun onPreBind(binding: FragmentModuleMd2Binding) = Unit

}
