package site.leos.apps.lespas.album

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class CoverViewModel : ViewModel() {
    private val cover = MutableLiveData<Cover>()
    private val isWorking = MutableLiveData<Boolean>()

    fun getCover(): LiveData<Cover> { return cover }
    fun setCover(newCover: Cover) {
        cover.value = newCover
    }

    fun settingCover() { isWorking.value = true }
    fun notSettingCover() { isWorking.value = false }
    fun isSettingCover(): LiveData<Boolean> { return isWorking }
}
