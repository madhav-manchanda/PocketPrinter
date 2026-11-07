package com.pocketprint.ui.main

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pocketprint.data.model.LogEntry
import com.pocketprint.data.model.LogLevel
import com.pocketprint.databinding.ItemLogEntryBinding

class LogAdapter : ListAdapter<LogEntry, LogAdapter.LogViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogEntryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LogViewHolder(
        private val binding: ItemLogEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: LogEntry) {
            binding.tvLogLine.text = entry.displayString
            val color = when (entry.level) {
                LogLevel.ERROR -> Color.parseColor("#F87171") // Red
                LogLevel.WARN -> Color.parseColor("#FBBF24")  // Amber
                LogLevel.DEBUG -> Color.parseColor("#94A3B8") // Gray
                LogLevel.INFO -> {
                    if (entry.tag == "USB") {
                        Color.parseColor("#38BDF8") // Sky blue for USB
                    } else {
                        Color.parseColor("#E2E8F0") // Slate for general
                    }
                }
            }
            binding.tvLogLine.setTextColor(color)
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<LogEntry>() {
        override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean {
            return oldItem.timestamp == newItem.timestamp && oldItem.message == newItem.message
        }

        override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean {
            return oldItem == newItem
        }
    }
}
