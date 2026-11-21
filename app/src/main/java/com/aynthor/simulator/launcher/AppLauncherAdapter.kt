package com.aynthor.simulator.launcher

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aynthor.simulator.R

class AppLauncherAdapter(
    private val onAppClick: (AppInfo) -> Unit,
    private val onAppLongClick: ((AppInfo) -> Unit)? = null,
    private val onSelectionChanged: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<AppLauncherAdapter.AppViewHolder>() {

    private val items = mutableListOf<AppInfo>()
    private val checkedPackages = LinkedHashSet<String>()

    var multiSelectMode: Boolean = false
        private set

    var selectedPosition: Int = -1
        private set

    var isLightTheme: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    fun enterMultiSelect(initialPackage: String? = null) {
        multiSelectMode = true
        checkedPackages.clear()
        if (!initialPackage.isNullOrBlank()) {
            checkedPackages.add(initialPackage)
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(checkedPackages.size)
    }

    fun exitMultiSelect() {
        if (!multiSelectMode && checkedPackages.isEmpty()) return
        multiSelectMode = false
        checkedPackages.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    fun togglePackageSelection(packageName: String) {
        if (!multiSelectMode) return
        if (!checkedPackages.add(packageName)) {
            checkedPackages.remove(packageName)
        }
        val idx = items.indexOfFirst { it.packageName == packageName }
        if (idx >= 0) notifyItemChanged(idx) else notifyDataSetChanged()
        onSelectionChanged?.invoke(checkedPackages.size)
    }

    fun toggleSelectAllVisible() {
        if (!multiSelectMode) return
        val visiblePkgs = items.map { it.packageName }
        val allVisibleSelected = visiblePkgs.isNotEmpty() && visiblePkgs.all { checkedPackages.contains(it) }
        if (allVisibleSelected) {
            checkedPackages.removeAll(visiblePkgs.toSet())
        } else {
            checkedPackages.addAll(visiblePkgs)
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(checkedPackages.size)
    }

    fun isAllVisibleSelected(): Boolean {
        return items.isNotEmpty() && items.all { checkedPackages.contains(it.packageName) }
    }

    fun getSelectedPackages(): List<String> = checkedPackages.toList()

    fun getSelectedCount(): Int = checkedPackages.size

    fun submitList(newItems: List<AppInfo>) {
        items.clear()
        items.addAll(newItems)
        if (selectedPosition >= items.size) {
            selectedPosition = if (items.isEmpty()) -1 else items.size - 1
        }
        notifyDataSetChanged()
    }

    fun setSelectedPosition(pos: Int) {
        val clamped = when {
            items.isEmpty() -> -1
            pos < 0 -> 0
            pos >= items.size -> items.size - 1
            else -> pos
        }
        if (clamped != selectedPosition) {
            val prev = selectedPosition
            selectedPosition = clamped
            if (prev in items.indices) notifyItemChanged(prev)
            if (clamped in items.indices) notifyItemChanged(clamped)
        }
    }

    fun getSelectedItem(): AppInfo? {
        return if (selectedPosition in items.indices) items[selectedPosition] else items.firstOrNull()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_grid, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = items[position]
        val isChecked = multiSelectMode && checkedPackages.contains(app.packageName)
        holder.bind(app, position == selectedPosition, isChecked)
    }

    override fun getItemCount(): Int = items.size

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.appIcon)
        private val labelView: TextView = itemView.findViewById(R.id.appLabel)
        private val badgeView: TextView = itemView.findViewById(R.id.appSelectBadge)

        fun bind(app: AppInfo, isFocusSelected: Boolean, isChecked: Boolean) {
            iconView.setImageDrawable(app.icon)
            labelView.text = app.label
            labelView.setTextColor(if (isLightTheme) Color.parseColor("#18181B") else Color.parseColor("#F0F0F5"))

            val highlighted = if (multiSelectMode) isChecked else isFocusSelected
            val bg = GradientDrawable().apply {
                cornerRadius = 18f
                if (highlighted) {
                    setColor(if (isLightTheme) Color.parseColor("#DBEAFE") else Color.parseColor("#1E3A5F"))
                    setStroke(4, Color.parseColor("#4C9AFF"))
                } else {
                    setColor(if (isLightTheme) Color.parseColor("#E4E4E7") else Color.parseColor("#1A1A22"))
                    setStroke(1, if (isLightTheme) Color.parseColor("#D4D4D8") else Color.parseColor("#2A2A35"))
                }
            }
            itemView.background = bg

            if (multiSelectMode) {
                badgeView.visibility = View.VISIBLE
                val badgeBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (isChecked) {
                        setColor(Color.parseColor("#4C9AFF"))
                    } else {
                        setColor(if (isLightTheme) Color.parseColor("#22000000") else Color.parseColor("#33000000"))
                        setStroke(3, if (isLightTheme) Color.parseColor("#71717A") else Color.parseColor("#88FFFFFF"))
                    }
                }
                badgeView.background = badgeBg
                badgeView.text = if (isChecked) "✓" else ""
            } else {
                badgeView.visibility = View.GONE
            }

            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = false

            itemView.setOnClickListener {
                if (multiSelectMode) {
                    togglePackageSelection(app.packageName)
                } else {
                    onAppClick(app)
                }
            }

            itemView.setOnLongClickListener {
                if (multiSelectMode) {
                    togglePackageSelection(app.packageName)
                    true
                } else {
                    onAppLongClick?.invoke(app)
                    onAppLongClick != null
                }
            }

            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_BUTTON_A ||
                        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    if (multiSelectMode) {
                        togglePackageSelection(app.packageName)
                    } else {
                        onAppClick(app)
                    }
                    true
                } else {
                    false
                }
            }
        }
    }
}
