package com.topjohnwu.magisk.databinding

import androidx.databinding.Observable
import androidx.databinding.PropertyChangeRegistry








interface ObservableHost : Observable {

    var callbacks: PropertyChangeRegistry?






    fun notifyChange() {
        synchronized(this) {
            callbacks ?: return
        }.notifyCallbacks(this, 0, null)
    }






    fun notifyPropertyChanged(fieldId: Int) {
        synchronized(this) {
            callbacks ?: return
        }.notifyCallbacks(this, fieldId, null)
    }

    override fun addOnPropertyChangedCallback(callback: Observable.OnPropertyChangedCallback) {
        synchronized(this) {
            callbacks ?: PropertyChangeRegistry().also { callbacks = it }
        }.add(callback)
    }

    override fun removeOnPropertyChangedCallback(callback: Observable.OnPropertyChangedCallback) {
        synchronized(this) {
            callbacks ?: return
        }.remove(callback)
    }
}

fun ObservableHost.addOnPropertyChangedCallback(
    fieldId: Int,
    removeAfterChanged: Boolean = false,
    callback: () -> Unit
) {
    addOnPropertyChangedCallback(object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (fieldId == propertyId) {
                callback()
                if (removeAfterChanged)
                    removeOnPropertyChangedCallback(this)
            }
        }
    })
}














inline fun <reified T> ObservableHost.set(
    new: T, old: T, setter: (T) -> Unit, fieldId: Int, afterChanged: (T) -> Unit = {}) {
    if (old != new) {
        setter(new)
        notifyPropertyChanged(fieldId)
        afterChanged(new)
    }
}

inline fun <reified T> ObservableHost.set(
    new: T, old: T, setter: (T) -> Unit, vararg fieldIds: Int, afterChanged: (T) -> Unit = {}) {
    if (old != new) {
        setter(new)
        fieldIds.forEach { notifyPropertyChanged(it) }
        afterChanged(new)
    }
}
