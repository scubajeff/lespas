package site.leos.apps.lespas.muzei

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumViewModel

class LesPasArtProviderSettingActivity: AppCompatActivity() {
    private lateinit var exclusionAdapter: ExclusionAdapter
    private lateinit var preferRadioGroup: RadioGroup
    private var exclusionList = mutableSetOf<String>()
    private lateinit var sp: SharedPreferences
    private var lastPreferSetting = PREFER_RANDOM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_muzei_setting)
        preferRadioGroup = findViewById(R.id.preference_group)
        exclusionAdapter = ExclusionAdapter().apply {
            findViewById<RecyclerView>(R.id.album_list).adapter = this
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }

        sp = PreferenceManager.getDefaultSharedPreferences(this)
        exclusionList = sp.getStringSet(EXCLUSION_LIST_KEY, mutableSetOf<String>()) ?: mutableSetOf()

        lastPreferSetting = sp.getInt(PREFER_KEY, PREFER_RANDOM)
        preferRadioGroup.check(when(lastPreferSetting) {
            PREFER_LATEST-> R.id.prefer_latest
            PREFER_TODAY_IN_HISTORY-> R.id.prefer_day_in_history
            else-> R.id.prefer_random
        })

        ViewModelProvider(this).get(AlbumViewModel::class.java).allAlbumsByEndDate.observe(this, {
            for (album in it) album.shareId = if (exclusionList.contains(album.id)) 1 else 0
            exclusionAdapter.submitList(it.toMutableList())
        })
    }

    @SuppressLint("ApplySharedPref")
    override fun onPause() {
        val currentPreferSetting = when(preferRadioGroup.checkedRadioButtonId) {
            R.id.prefer_latest-> PREFER_LATEST
            R.id.prefer_day_in_history-> PREFER_TODAY_IN_HISTORY
            else-> PREFER_RANDOM
        }

        sp.edit().putStringSet(EXCLUSION_LIST_KEY, exclusionAdapter.getExclusionList()).putInt(PREFER_KEY, currentPreferSetting).commit()

        // TODO Change immediately
        //if (currentPreferSetting != lastPreferSetting) contentResolver.call(ProviderContract.getContentUri(getString(R.string.muzei_authority)), LesPasArtProvider.UPDATE_CALL, null, null)

        super.onPause()
    }

    class ExclusionAdapter: ListAdapter<Album, ExclusionAdapter.ExclusionViewHolder>(AlbumDiffCallback()) {
        inner class ExclusionViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bind(item: Album) {
                itemView.findViewById<CheckBox>(R.id.album_name).apply {
                    text = item.name
                    isChecked = item.shareId == 1
                    setOnCheckedChangeListener { _, isChecked ->
                        item.shareId = if (isChecked) 1 else 0
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExclusionViewHolder =
            ExclusionViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_muzei_exclude, parent, false))

        override fun onBindViewHolder(holder: ExclusionViewHolder, position: Int) {
            holder.bind(currentList[position])
        }

        override fun onViewRecycled(holder: ExclusionViewHolder) {
            super.onViewRecycled(holder)
            holder.itemView.findViewById<CheckBox>(R.id.album_name).setOnCheckedChangeListener(null)
        }

        fun getExclusionList(): MutableSet<String> {
            val exclusionList = mutableSetOf<String>()
            for (album in currentList) if (album.shareId == 1) exclusionList.add(album.id)
            return exclusionList
        }
    }

    class AlbumDiffCallback: DiffUtil.ItemCallback<Album>() {
        override fun areItemsTheSame(oldItem: Album, newItem: Album): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Album, newItem: Album): Boolean = oldItem.id == newItem.id
    }

    companion object {
        const val EXCLUSION_LIST_KEY = "MUZEI_EXCLUSION_SET"
        const val PREFER_KEY = "MUZEI_PREFER"
        const val PREFER_LATEST = 0
        const val PREFER_TODAY_IN_HISTORY = 1
        const val PREFER_RANDOM = 2
    }
}