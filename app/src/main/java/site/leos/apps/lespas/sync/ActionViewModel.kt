package site.leos.apps.lespas.sync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ActionViewModel(application: Application): AndroidViewModel(application) {
    private val actionRepository = ActionRepository(application)
    val allActions: LiveData<List<Action>> = actionRepository.pendingActionsFlow().asLiveData()
    fun addActions(actions: List<Action>) = viewModelScope.launch(Dispatchers.IO) { actionRepository.addActions(actions) }
    fun addActions(action: Action) = viewModelScope.launch(Dispatchers.IO) { actionRepository.addActions(action) }
}