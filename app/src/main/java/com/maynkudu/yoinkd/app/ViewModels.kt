package com.maynkudu.yoinkd.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

@Composable
inline fun <reified T : ViewModel> screenViewModel(noinline create: () -> T): T {
    val factory = remember { viewModelFactory { initializer { create() } } }
    return viewModel(factory = factory)
}

@Composable
inline fun <reified T : ViewModel> scopedViewModel(key: String, noinline create: () -> T): T {
    val owner = remember(key) { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val factory = remember(key) { viewModelFactory { initializer { create() } } }
    return viewModel(viewModelStoreOwner = owner, key = key, factory = factory)
}
