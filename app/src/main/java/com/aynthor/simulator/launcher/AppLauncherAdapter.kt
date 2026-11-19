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
    private val onAppLongClick: ((AppInfo) -> Unit)? = null
) : RecyclerView.Adapter<AppLauncherAdapter.AppViewHolder>() {

    private val items = mutableListOf<AppInfo>()
    var selectedPosition: Int = -1
        private set
    var isLightTheme: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

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
        holder.bind(items[position], position == selectedPosition)
    }

    override fun getItemCount(): Int = items.size

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.appIcon)
        private val labelView: TextView = itemView.findViewById(R.id.appLabel)

        fun bind(app: AppInfo, isSelected: Boolean) {
            iconView.setImageDrawable(app.icon)
            labelView.text = app.label
            labelView.setTextColor(if (isLightTheme) Color.parseColor("#18181B") else Color.parseColor("#F0F0F5"))

            val bg = GradientDrawable().apply {
                cornerRadius = 18f
                if (isSelected) {
                    setColor(if (isLightTheme) Color.parseColor("#DBEAFE") else Color.parseColor("#1E3A5F"))
                    setStroke(4, Color.parseColor("#4C9AFF"))
                } else {
                    setColor(if (isLightTheme) Color.parseColor("#E4E4E7") else Color.parseColor("#1A1A22"))
                    setStroke(1, if (isLightTheme) Color.parseColor("#D4D4D8") else Color.parseColor("#2A2A35"))
                }
            }
            itemView.background = bg

            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = false

            itemView.setOnClickListener {
                onAppClick(app)
            }

            itemView.setOnLongClickListener {
                onAppLongClick?.invoke(app)
                onAppLongClick != null
            }

            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_BUTTON_A ||
                        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    onAppClick(app)
                    true
                } else {
                    false
                }
            }
        }
    }
}
