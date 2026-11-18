package com.aynthor.simulator.launcher

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aynthor.simulator.R

class AppLauncherAdapter(
    private val onAppClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppLauncherAdapter.AppViewHolder>() {

    private val items = mutableListOf<AppInfo>()

    fun submitList(newItems: List<AppInfo>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_grid, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.appIcon)
        private val labelView: TextView = itemView.findViewById(R.id.appLabel)

        fun bind(app: AppInfo) {
            iconView.setImageDrawable(app.icon)
            labelView.text = app.label

            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = false

            itemView.setOnClickListener {
                onAppClick(app)
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
