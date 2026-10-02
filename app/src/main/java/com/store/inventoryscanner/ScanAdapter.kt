package com.store.inventoryscanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ScanAdapter(private var items: List<ScanRecord>) :
    RecyclerView.Adapter<ScanAdapter.ScanViewHolder>() {

    class ScanViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvCustomCode: TextView = view.findViewById(R.id.tvItemCustomCode)
        val tvName: TextView = view.findViewById(R.id.tvItemName)
        val tvQty: TextView = view.findViewById(R.id.tvItemQty)
        val tvTime: TextView = view.findViewById(R.id.tvItemTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScanViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scan_record, parent, false)
        return ScanViewHolder(view)
    }

    override fun onBindViewHolder(holder: ScanViewHolder, position: Int) {
        val item = items[position]
        holder.tvCustomCode.text = item.customCode
        holder.tvName.text = item.name
        holder.tvQty.text = item.qty.toString()
        holder.tvTime.text = item.lastTime
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<ScanRecord>) {
        items = newItems
        notifyDataSetChanged()
    }
}
