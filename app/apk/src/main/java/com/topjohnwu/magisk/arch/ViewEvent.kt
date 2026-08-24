package com.topjohnwu.magisk.arch

import android.content.Context





abstract class ViewEvent

interface ContextExecutor {
    operator fun invoke(context: Context)
}

interface ActivityExecutor {
    operator fun invoke(activity: UIActivity<*>)
}

interface FragmentExecutor {
    operator fun invoke(fragment: BaseFragment<*>)
}
