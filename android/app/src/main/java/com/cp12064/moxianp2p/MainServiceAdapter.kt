package com.cp12064.moxianp2p

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 主页的 NAS 服务网格 tile
 * emoji + 名字 点击直接进服务（内置客户端或 WebView）
 */
class MainServiceAdapter(
    private val items: List<NasService>,
    private val onClick: (NasService) -> Unit,
) : RecyclerView.Adapter<MainServiceAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_service_tile, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val svc = items[position]
        h.tvIcon.text = svc.type.emoji
        h.tvName.text = svc.name
        h.itemView.setOnClickListener { onClick(svc) }
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvIcon: TextView = v.findViewById(R.id.tv_icon)
        val tvName: TextView = v.findViewById(R.id.tv_name)
    }
}
