package site.leos.apps.lespas.sync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData

class ActionViewModel(application: Application): AndroidViewModel(application) {
    val allActions: LiveData<List<Action>> = ActionRepository(application).pendingActionsFlow().asLiveData()
}