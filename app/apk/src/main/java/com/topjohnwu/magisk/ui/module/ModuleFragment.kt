package com.topjohnwu.magisk.ui.module

import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.MenuProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.BaseFragment
import com.topjohnwu.magisk.arch.viewModel
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.di.ServiceLocator
import com.topjohnwu.magisk.core.download.DownloadEngine
import com.topjohnwu.magisk.core.repository.ModuleRepository
import com.topjohnwu.magisk.core.repository.RepositoryCandidate
import com.topjohnwu.magisk.core.repository.RepositoryModule
import com.topjohnwu.magisk.core.utils.MediaStoreUtils.displayName
import com.topjohnwu.magisk.databinding.FragmentModuleMd2Binding
import com.topjohnwu.magisk.dialog.OnlineModuleInstallDialog
import com.topjohnwu.magisk.view.MagiskDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            R.attr.colorPrimary,
            Color.MAGENTA,
        )
        listOf(R.id.action_module_search, R.id.action_repository_search).forEach { itemId ->
            menu.findItem(itemId).icon?.mutate()?.let { icon ->
                DrawableCompat.setTint(icon, accent)
            }
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
        menu.findItem(R.id.action_repository_search).isVisible =
            Config.repositorySearcherEnabled
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_repository_search) {
            showRepositoryDialog()
            return true
        }
        return false
    }

    override fun onBackPressed(): Boolean {
        if (::installedSearchView.isInitialized && !installedSearchView.isIconified) {
            installedSearchView.isIconified = true
            viewModel.searchInstalled("")
            return true
        }
        return super.onBackPressed()
    }

    private fun showRepositoryDialog() {
        val host = activity ?: return
        val context = requireContext()
        val repository = ModuleRepository(ServiceLocator.networkService)
        val adapter = RepositoryAdapter(
            onDownload = { module ->
                DownloadEngine.startWithActivity(
                    host,
                    OnlineModuleInstallDialog.Module(module.asOnlineModule(), false),
                )
            },
            onInstall = { module ->
                DownloadEngine.startWithActivity(
                    host,
                    OnlineModuleInstallDialog.Module(module.asOnlineModule(), true),
                )
            },
        )
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = (resources.displayMetrics.heightPixels * 0.65f).toInt()
        }
        val search = SearchView(context).apply {
            queryHint = getString(CoreR.string.repository_search_hint)
            isIconified = false
        }
        val progress = ProgressBar(context).apply {
            isIndeterminate = true
        }
        val status = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextAppearance(R.style.AppearanceFoundation_Body)
        }
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            clipToPadding = false
        }
        container.addView(
            search,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            progress,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            status,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        var candidates = emptyList<RepositoryCandidate>()
        var searchJob: Job? = null
        val runSearch: (String) -> Unit = { rawQuery ->
            searchJob?.cancel()
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                progress.visibility = View.VISIBLE
                status.visibility = View.GONE
                val words = rawQuery.trim().lowercase().split(Regex("\\s+"))
                    .filter(String::isNotBlank)
                val matches = withContext(Dispatchers.Default) {
                    candidates.asSequence()
                        .filter { candidate ->
                            if (words.isEmpty()) true else {
                                val searchable = listOf(
                                    candidate.id,
                                    candidate.name,
                                    candidate.author,
                                    candidate.description,
                                ).joinToString(" ").lowercase()
                                words.all(searchable::contains)
                            }
                        }
                        .take(50)
                        .toList()
                }
                val modules = withContext(Dispatchers.IO) { repository.resolve(matches) }
                progress.visibility = View.GONE
                adapter.submit(modules)
                status.apply {
                    visibility = if (modules.isEmpty()) View.VISIBLE else View.GONE
                    setText(CoreR.string.repository_no_results)
                }
            }
        }
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                runSearch(query.orEmpty())
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                runSearch(newText.orEmpty())
                return true
            }
        })

        val dialog = MagiskDialog(host).apply {
            setTitle(CoreR.string.repository_searcher)
            setView(container)
            setButton(MagiskDialog.ButtonType.NEGATIVE) {
                text = android.R.string.cancel
            }
        }
        val loadJob = viewLifecycleOwner.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            status.visibility = View.GONE
            candidates = withContext(Dispatchers.IO) {
                repository.loadSources(Config.moduleRepositoryUrls)
            }
            if (candidates.isEmpty()) {
                progress.visibility = View.GONE
                status.visibility = View.VISIBLE
                status.setText(CoreR.string.repository_load_failed)
            } else {
                runSearch(search.query.toString())
            }
        }
        dialog.setOnDismissListener {
            loadJob.cancel()
            searchJob?.cancel()
        }
        dialog.show()
    }

    override fun onPreBind(binding: FragmentModuleMd2Binding) = Unit

}

private class RepositoryAdapter(
    private val onDownload: (RepositoryModule) -> Unit,
    private val onInstall: (RepositoryModule) -> Unit,
) : RecyclerView.Adapter<RepositoryAdapter.Holder>() {

    private var modules = emptyList<RepositoryModule>()

    fun submit(value: List<RepositoryModule>) {
        modules = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        val spacing = (8 * context.resources.displayMetrics.density).toInt()
        val card = MaterialCardView(context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, spacing / 2, 0, spacing / 2) }
            strokeWidth = (resources.displayMetrics.density).toInt().coerceAtLeast(1)
            strokeColor = MaterialColors.getColor(
                this,
                MaterialR.attr.colorOutline,
                Color.GRAY,
            )
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val title = TextView(context).apply {
            setTextAppearance(R.style.AppearanceFoundation_Body)
            setTypeface(typeface, Typeface.BOLD)
        }
        val metadata = TextView(context).apply {
            setTextAppearance(R.style.AppearanceFoundation_Caption_Variant)
        }
        val description = TextView(context).apply {
            setTextAppearance(R.style.AppearanceFoundation_Caption_Variant)
            maxLines = 4
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val download = MaterialButton(context).apply {
            setText(CoreR.string.download)
            isAllCaps = false
        }
        val install = MaterialButton(context).apply {
            setText(CoreR.string.install)
            isAllCaps = false
        }
        buttons.addView(download)
        buttons.addView(install)
        content.addView(title)
        content.addView(metadata)
        content.addView(description)
        content.addView(buttons)
        card.addView(content)
        return Holder(card, title, metadata, description, download, install)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val module = modules[position]
        holder.title.text = module.name
        holder.metadata.text = holder.itemView.context.getString(
            CoreR.string.module_version_author,
            module.version,
            module.author.ifBlank { module.id },
        )
        holder.description.apply {
            text = module.description
            visibility = if (module.description.isBlank()) View.GONE else View.VISIBLE
        }
        holder.download.setOnClickListener { onDownload(module) }
        holder.install.setOnClickListener { onInstall(module) }
    }

    override fun getItemCount() = modules.size

    class Holder(
        view: View,
        val title: TextView,
        val metadata: TextView,
        val description: TextView,
        val download: MaterialButton,
        val install: MaterialButton,
    ) : RecyclerView.ViewHolder(view)
}
