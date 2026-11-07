package com.pocketprint.ui.queue

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pocketprint.data.queue.LocalPrintJob
import com.pocketprint.data.queue.LocalPrintJobTransitions
import com.pocketprint.databinding.ItemLocalPrintJobBinding

class LocalQueueAdapter(
    private val onCancel: (LocalPrintJob) -> Unit,
    private val onRetry: (LocalPrintJob) -> Unit
) : ListAdapter<LocalPrintJob, LocalQueueAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemLocalPrintJobBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemLocalPrintJobBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(job: LocalPrintJob) {
            binding.tvJobName.text = job.displayName
            binding.tvJobState.text = job.state.name.replace('_', ' ')
            val page = if (job.totalPages > 0) " | item ${job.currentPage}/${job.totalPages}" else ""
            binding.tvJobOptions.text =
                "${job.printOptions.qualityProfile.displayName} | ${job.printOptions.paperSize.name} | " +
                    "${job.printOptions.colorMode.displayName}$page | ${job.progressPercent}%"
            binding.progressJob.progress = job.progressPercent
            val detail = job.recoveryDetail ?: job.failureDetail
            binding.tvJobDetail.visibility = if (detail.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.tvJobDetail.text = detail.orEmpty()
            binding.btnCancelJob.visibility = if (job.state.isTerminal) View.GONE else View.VISIBLE
            binding.btnCancelJob.setOnClickListener { onCancel(job) }
            binding.btnRetryJob.visibility = if (LocalPrintJobTransitions.canRetrySafely(job)) View.VISIBLE else View.GONE
            binding.btnRetryJob.setOnClickListener { onRetry(job) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<LocalPrintJob>() {
        override fun areItemsTheSame(oldItem: LocalPrintJob, newItem: LocalPrintJob) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: LocalPrintJob, newItem: LocalPrintJob) = oldItem == newItem
    }
}
